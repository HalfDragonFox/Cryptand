package com.hdf.cryptand.gameinput.sdl;

import com.hdf.cryptand.gameinput.GameInputBackend;
import com.hdf.cryptand.gameinput.GameInputConfig;
import com.hdf.cryptand.gameinput.GameInputController;
import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 游戏输入后端实现：<b>SDL3</b>（libSDL3，跨平台——Linux evdev / macOS IOKit HID / Windows RawInput+XInput）。
 * <p>
 * 2026-09-13 由 SDL2 迁移到 SDL3（用户："不使用 SDL2 这种旧版本了"）。关键差异：
 * 设备标识改为 SDL3 的 <b>instance id</b>（{@code SDL_JoystickID}，id 形如 `sdl:2`）、
 * 枚举用 {@code SDL_GetJoysticks}、状态刷新用 {@code SDL_UpdateJoysticks}、
 * 力反馈 {@code SDL_OpenHapticFromJoystick / SDL_CreateHapticEffect}。
 * <p>
 * common 侧不主动加载：由平台按配置调用 {@code GameInputProvider.registerSdl()}；
 * 本地库解析（玩家可替换目录 → mod 内置 → 系统）见平台侧 gameinput 子包入口。
 */
public final class SdlBackend implements GameInputBackend {

    /** 设备 id 前缀 */
    public static final String PREFIX = "sdl";

    private final String libName;     // 显式指定的库名/路径（null = 自动探测）
    private volatile SdlLib lib;      // lazy init（懒加载）
    private volatile String initError;

    private final Object sdlLock = new Object();
    /** SDL 子系统是否已初始化（进程内【常驻】，不再 Quit —— 见 {@link #sdlRelease}） */
    private boolean initialized;

    public SdlBackend() {
        this(null);
    }

    /** 指定 SDL3 库名/路径（如 "/usr/lib/x86_64-linux-gnu/libSDL3.so.0"）；null = 自动探测 */
    public SdlBackend(String libName) {
        this.libName = libName;
    }

    private synchronized SdlLib ensureLib() {
        if (lib != null) return lib;
        if (!GameInputConfig.isInputEnabled()) {
            initError = "游戏输入未启用：gameinput.toml#enableGameInput=false";
            return null;
        }
        try {
            lib = SdlLib.load(libName);
        } catch (Throwable t) {
            initError = String.valueOf(t.getMessage());
        }
        return lib;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public boolean available() {
        return ensureLib() != null;
    }

    @Override
    public String lastError() {
        return initError;
    }

    @Override
    public List<GameInputDeviceInfo> listDevices() {
        List<GameInputDeviceInfo> out = new ArrayList<>();
        SdlLib l = ensureLib();
        if (l == null) return out;
        if (!sdlAcquire(l)) return out;
        try {
            // ★ 枚举前先 SDL_UpdateJoysticks()：SDL3 的【设备热插拔检测只在这一步里跑】
            //   （SDL_joystick.c 的 SDL_UpdateJoysticks 末尾逐个 driver 调 Detect()，HIDAPI 的
            //   HIDAPI_UpdateDevices() 也在其中）。SDL_GetJoysticks() 只是读 SDL 内部那张设备表，
            //   不刷新就永远停留在 SDL_Init 那一刻的快照 ⇒ 实测"新插的设备点【刷新设备】扫不到，
            //   必须重启客户端"。SDL 明确此函数线程安全。
            //   ⚠ 前提：SDL_Init 必须已在【同一条 SDL 线程】上完成 —— 否则 DirectInput 设备
            //   会因跨 COM 单元而 SDL_OpenJoystick 报 E_HANDLE（见 GameInputEntry.commonSetup 的注释）。
            l.updateJoysticks();
            for (int instanceId : l.joystickIds()) {
                String name = l.joystickNameForId(instanceId);
                int type = l.joystickTypeForId(instanceId);
                Probe probe = probe(l, instanceId);
                out.add(new GameInputDeviceInfo(
                        PREFIX + ":" + instanceId,
                        name == null || name.isBlank() ? ("SDL3 设备 " + instanceId) : name,
                        kindOf(type, name, probe),
                        probe.forceFeedback()));
            }
        } finally {
            sdlRelease(l);
        }
        return out;
    }

    /** 枚举时一次打开探测到的设备特征（力反馈 / 轴 / 按钮 / 帽） */
    private record Probe(boolean forceFeedback, int axes, int buttons, int hats) {
        static final Probe EMPTY = new Probe(false, 0, 0, 0);
    }

    /**
     * 打开探测：SDL3 取消了 {@code SDL_JoystickIsHaptic}，力反馈只能"打开 joystick → 试开 haptic"；
     * 顺带读轴/按钮/帽数量 —— 这些是<b>判定设备类型的关键特征</b>：
     * SDL3 的 {@code SDL_GetJoystickTypeForID} 对大量方向盘返回 UNKNOWN，仅靠名称会误判成"控制器"。
     */
    private static Probe probe(SdlLib l, int instanceId) {
        Pointer joystick = l.joystickOpen(instanceId);
        if (joystick == null) {
            return Probe.EMPTY;
        }
        try {
            boolean ffb = false;
            Pointer haptic = l.hapticOpenFromJoystick(joystick);
            if (haptic != null) {
                ffb = true;
                l.hapticClose(haptic);
            }
            return new Probe(ffb, l.joystickNumAxes(joystick),
                    l.joystickNumButtons(joystick), l.joystickNumHats(joystick));
        } catch (Throwable t) {
            return Probe.EMPTY;
        } finally {
            l.joystickClose(joystick);
        }
    }

    @Override
    public GameInputController open(GameInputDeviceInfo info) throws IOException {
        SdlLib l = ensureLib();
        if (l == null) {
            throw new IOException("SDL3 不可用: " + initError);
        }
        int instanceId = parseInstanceId(info);
        if (!sdlAcquire(l)) {
            throw new IOException("SDL3 初始化失败: " + l.lastError());
        }
        try {
            Pointer joystick = l.joystickOpen(instanceId);
            if (joystick == null) {
                throw new IOException("SDL_OpenJoystick(" + instanceId + ") 失败: " + l.lastError());
            }
            return new SdlController(this, l, instanceId, joystick, info.name(), info.kind());
        } catch (IOException e) {
            sdlRelease(l);
            throw e;
        } catch (Throwable t) {
            sdlRelease(l);
            throw new IOException("打开设备失败: " + t, t);
        }
    }

    /** 设备 id 形如 `sdl:<instanceId>` */
    private static int parseInstanceId(GameInputDeviceInfo info) throws IOException {
        String id = info == null ? null : info.id();
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon < 0) {
            throw new IOException("非法设备 id: " + id);
        }
        try {
            return Integer.parseInt(id.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("非法设备 id: " + id, e);
        }
    }

    /**
     * SDL3 设备类型（值已变：WHEEL=2 / THROTTLE=9）→ 通用类型。
     * <p>⚠ 实测：不少方向盘（如 PXN/FXN V12lite）的 {@code SDL_GetJoystickTypeForID} 返回 UNKNOWN，
     * 名称里也没有 wheel 字样 ⇒ 旧实现落到 OTHER（界面显示"控制器"）。现按三级判定：
     * <ol>
     *   <li>SDL 报告的类型；</li>
     *   <li>名称关键词（含国产品牌 PXN/FXN/莱仕达/九号 与主流 MOZA/Fanatec/Thrustmaster/Simagic…）；</li>
     *   <li><b>硬件特征兜底</b>：支持力反馈 + ≥3 轴 ⇒ 方向盘（手柄极少有 FFB）；
     *       多轴多键 ⇒ 摇杆；否则其它。</li>
     * </ol>
     */
    private static GameInputDeviceKind kindOf(int sdlType, String name, Probe probe) {
        if (sdlType == SdlLib.SDL_JOYSTICK_TYPE_WHEEL) return GameInputDeviceKind.WHEEL;
        if (sdlType == SdlLib.SDL_JOYSTICK_TYPE_GAMEPAD) return GameInputDeviceKind.GAMEPAD;
        if (sdlType == SdlLib.SDL_JOYSTICK_TYPE_FLIGHT_STICK
                || sdlType == SdlLib.SDL_JOYSTICK_TYPE_THROTTLE) {
            return GameInputDeviceKind.JOYSTICK;
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("wheel") || n.contains("steering") || n.contains("racing")
                || n.contains("方向盘")
                || n.contains("g29") || n.contains("g920") || n.contains("g923") || n.contains("g27")
                || n.contains("t300") || n.contains("t150") || n.contains("t248") || n.contains("tx ")
                || n.contains("pxn") || n.contains("fxn") || n.contains("v12") || n.contains("lite")
                || n.contains("moza") || n.contains("fanatec") || n.contains("thrustmaster")
                || n.contains("simagic") || n.contains("cammus") || n.contains("simucube")
                || n.contains("lite star") || n.contains("litez")) {
            return GameInputDeviceKind.WHEEL;
        }
        if (n.contains("pad") || n.contains("xbox") || n.contains("dualshock")
                || n.contains("dualsense") || n.contains("controller") || n.contains("手柄")) {
            return GameInputDeviceKind.GAMEPAD;
        }
        if (n.contains("stick") || n.contains("joystick") || n.contains("摇杆")) {
            return GameInputDeviceKind.JOYSTICK;
        }
        // 硬件特征兜底：能开 haptic（力反馈）且轴数够 → 方向盘；否则多轴多键 → 摇杆
        if (probe.forceFeedback() && probe.axes() >= 3) {
            return GameInputDeviceKind.WHEEL;
        }
        if (probe.axes() >= 3 && probe.buttons() >= 8) {
            return GameInputDeviceKind.JOYSTICK;
        }
        return GameInputDeviceKind.OTHER;
    }

    /** SDL_Init（进程内只做一次；返回 false = 初始化失败，错误信息写入 initError） */
    boolean sdlAcquire(SdlLib l) {
        synchronized (sdlLock) {
            if (initialized) {
                return true;
            }
            if (!l.sdlInit(SdlLib.SDL_INIT_JOYSTICK | SdlLib.SDL_INIT_HAPTIC)) {
                initError = "SDL_Init(JOYSTICK|HAPTIC) 失败: " + l.lastError();
                return false;
            }
            initialized = true;
            return true;
        }
    }

    /**
     * ⚠ <b>刻意不再 {@code SDL_QuitSubSystem}</b>。
     *
     * <p>旧实现按引用计数在计数归零时 Quit：枚举设备（`listDevices` → acquire/release）结束后
     * 子系统就被关掉，于是 <b>已拿到的 {@code SDL_JoystickID} 全部作废</b>；点"连接"时
     * `open` 再 `SDL_Init` 重新初始化，instance id 被重新分配 ⇒ 表现为
     * `SDL_OpenJoystick(6) 失败: Joystick 6 not found`（实测截图）。
     * <p>SDL 子系统保持常驻（进程退出时自然释放），instance id 在进程生命周期内稳定，
     * 设备开关只走 `SDL_OpenJoystick`/`SDL_CloseJoystick` 与 haptic 句柄。
     */
    void sdlRelease(SdlLib l) {
        // 故意留空：见方法注释（释放 SDL 子系统会让 instance id 失效）
    }
}
