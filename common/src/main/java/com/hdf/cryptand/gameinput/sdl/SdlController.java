package com.hdf.cryptand.gameinput.sdl;

import com.hdf.cryptand.gameinput.ForceEffect;
import com.hdf.cryptand.gameinput.ForceParams;
import com.hdf.cryptand.gameinput.GameInputController;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import com.hdf.cryptand.gameinput.GameInputState;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * SDL3 游戏输入控制器（joystick + haptic 力反馈），2026-09-13 由 SDL2 迁移。
 * <p>
 * 轴读 {@code SDL_GetJoystickAxis}（Sint16 → ±1）；按钮 {@code SDL_GetJoystickButton}（bool）；
 * 方向键 {@code SDL_GetJoystickHat}（位掩码 → 0..35999 百分度）；每帧 {@code SDL_UpdateJoysticks()} 刷新。
 * <p>
 * 力反馈：懒打开（首次需要时 {@code SDL_OpenHapticFromJoystick}），效果参数更新沿用"删旧建新"
 * （SDL3 虽新增 {@code SDL_UpdateHapticEffect}，但各驱动支持不一，重建最稳）。结构体由
 * {@link SdlLib.HapticEffect}（JNA Union/Structure）承载——SDL3 的 HapticEffect 是 union，
 * 不能再用手工字节偏移。
 */
final class SdlController implements GameInputController {

    private static final float SDL_AXIS = 32767f;

    private final SdlBackend backend;
    private final SdlLib lib;
    private final int instanceId;
    private final Pointer joystick;   // SDL_Joystick*
    private final String id;
    private final String name;
    private final GameInputDeviceKind kind;
    private final int numAxes;
    private final int numButtons;
    private final int numHats;
    private volatile boolean closed;

    private Pointer haptic;           // SDL_Haptic*（懒打开）
    private final Map<ForceEffect, Integer> effectIds = new EnumMap<>(ForceEffect.class);
    private volatile ForceParams lastForce;
    /** 手柄马达可用性（懒探测一次） */
    private volatile boolean rumbleChecked;
    private volatile boolean rumbleAvailable;

    SdlController(SdlBackend backend, SdlLib lib, int instanceId, Pointer joystick,
                  String name, GameInputDeviceKind kind) {
        this.backend = backend;
        this.lib = lib;
        this.instanceId = instanceId;
        this.joystick = joystick;
        this.id = SdlBackend.PREFIX + ":" + instanceId;
        this.name = name;
        this.kind = kind;
        this.numAxes = lib.joystickNumAxes(joystick);
        this.numButtons = lib.joystickNumButtons(joystick);
        this.numHats = lib.joystickNumHats(joystick);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public GameInputDeviceKind kind() {
        return kind;
    }

    @Override
    public boolean isConnected() {
        return !closed && lib.joystickConnected(joystick);
    }

    @Override
    public GameInputState getState() throws IOException {
        if (closed) return GameInputState.disconnected();
        lib.updateJoysticks();
        if (!lib.joystickConnected(joystick)) return GameInputState.disconnected();

        float[] axes = new float[GameInputState.AXIS_COUNT];
        for (int i = 0; i < Math.min(GameInputState.AXIS_COUNT, numAxes); i++) {
            axes[i] = clamp(lib.joystickGetAxis(joystick, i) / SDL_AXIS, -1f, 1f);
        }
        long buttons = 0L;
        for (int i = 0; i < Math.min(64, numButtons); i++) {
            if (lib.joystickGetButton(joystick, i)) {
                buttons |= (1L << i);
            }
        }
        int pov = numHats > 0 ? hatToPov(lib.joystickGetHat(joystick, 0)) : -1;
        // 键盘通道由独立的 KeyboardInputBackend 提供；手柄/方向盘这里恒为空
        return new GameInputState(true, axes, buttons, pov, GameInputState.EMPTY_KEYS,
                System.nanoTime());
    }

    /** SDL_HAT_* 位掩码 → POV 角度（百分度；-1 = 居中） */
    private static int hatToPov(int hat) {
        if (hat == 0) return -1;
        if (hat == SdlLib.SDL_HAT_UP) return 0;
        if (hat == (SdlLib.SDL_HAT_UP | SdlLib.SDL_HAT_RIGHT)) return 4500;
        if (hat == SdlLib.SDL_HAT_RIGHT) return 9000;
        if (hat == (SdlLib.SDL_HAT_RIGHT | SdlLib.SDL_HAT_DOWN)) return 13500;
        if (hat == SdlLib.SDL_HAT_DOWN) return 18000;
        if (hat == (SdlLib.SDL_HAT_DOWN | SdlLib.SDL_HAT_LEFT)) return 22500;
        if (hat == SdlLib.SDL_HAT_LEFT) return 27000;
        if (hat == (SdlLib.SDL_HAT_LEFT | SdlLib.SDL_HAT_UP)) return 31500;
        return -1;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    @Override
    public boolean hasForceFeedback() {
        if (closed) return false;
        return ensureHapticQuiet() || ensureRumbleQuiet();
    }

    /**
     * 手柄马达（rumble）可用性：haptic 效果多见于力反馈方向盘，手柄通常只有马达。
     * 用"0 力度 1ms"试探，SDL 返回 true 表示支持。
     */
    private boolean ensureRumbleQuiet() {
        if (rumbleChecked) {
            return rumbleAvailable;
        }
        rumbleChecked = true;
        try {
            rumbleAvailable = lib.rumbleJoystick(joystick, 0, 0, 1);
        } catch (Throwable t) {
            rumbleAvailable = false;
        }
        return rumbleAvailable;
    }

    /** 尝试打开 haptic（不抛异常）；SDL3 无 SDL_JoystickIsHaptic，只能尝试打开 */
    private boolean ensureHapticQuiet() {
        if (haptic != null) return true;
        Pointer h = lib.hapticOpenFromJoystick(joystick);
        if (h == null) return false;
        haptic = h;
        return true;
    }

    @Override
    public void setForce(ForceParams params) throws IOException {
        ensureOpen();
        if (!ensureHapticQuiet()) {
            // 无 haptic（常见于手柄）→ 退化为马达震动：力度映射到马达强度
            if (ensureRumbleQuiet()) {
                int strength = Math.abs(params.magnitude()) > 0
                        ? Math.abs(params.magnitude()) : Math.abs(params.positiveCoef());
                int motor = (int) (Math.min(10000, strength) / 10000f * 65535f);
                if (!lib.rumbleJoystick(joystick, motor, motor, 250)) {
                    throw new IOException("SDL_RumbleJoystick 失败: " + name);
                }
                lastForce = params;
                return;
            }
            throw new IOException("设备不支持力反馈（haptic 与 rumble 均不可用）: " + name);
        }
        ForceEffect effect = params.effect();
        SdlLib.HapticEffect data = SdlLib.buildEffect(params);
        Integer existing = effectIds.get(effect);
        if (existing != null) {
            // 优先【原地更新】——避免每秒几十次 destroy/create；重建一旦失败就会把效果漏在设备上（力停不掉）
            if (lib.hapticUpdateEffect(haptic, existing, data)
                    && lib.hapticRunEffect(haptic, existing, SdlLib.SDL_HAPTIC_INFINITY)) {
                lastForce = params;
                return;
            }
            // 个别驱动不支持更新 → 退回"删旧建新"，但务必先停再销毁
            lib.hapticStopEffect(haptic, existing);
            lib.hapticDestroyEffect(haptic, existing);
            effectIds.remove(effect);
        }
        int newId = lib.hapticCreateEffect(haptic, data);
        if (newId < 0) {
            throw new IOException("SDL_CreateHapticEffect(" + effect + ") 失败（设备可能不支持该效果）");
        }
        effectIds.put(effect, newId);
        if (!lib.hapticRunEffect(haptic, newId, SdlLib.SDL_HAPTIC_INFINITY)) {
            throw new IOException("SDL_RunHapticEffect(" + effect + ") 失败");
        }
        lastForce = params;
    }

    @Override
    public void setForceEnabled(boolean enabled) throws IOException {
        if (enabled) {
            if (lastForce != null) {
                setForce(lastForce);
            }
        } else {
            stopForce();
        }
    }

    /**
     * 彻底清空设备上的所有效果（含我们已失去 id 记录、再也停不掉的残留效果）。
     * <p>为什么必须做：{@code setForce} 老实现是"删旧建新"，一旦 destroy / create 失败，
     * 那个效果就永久留在设备上继续推方向盘（用户实测"顶在 -180°、只有插拔 USB 才恢复"），
     * 并且一直占着效果槽位，最后连新效果都建不出来。
     */
    private void purgeHapticEffects() {
        if (haptic == null) {
            return;
        }
        for (int id : lib.hapticGetEffects(haptic)) {
            lib.hapticStopEffect(haptic, id);
            lib.hapticDestroyEffect(haptic, id);
        }
        effectIds.clear();
    }

    @Override
    public void stopForce() throws IOException {
        if (closed) return;
        if (haptic != null) {
            // ① 一次停掉设备上的【全部】效果 —— 包括我们丢失了 id 记录、已经管不到的残留效果。
            //    这是"不拔插 USB 也能救回方向盘"的关键（见 SdlLib#hapticStopEffects）。
            lib.hapticStopEffects(haptic);
            // ② 再逐个停一遍兜底（老版本 SDL 没有 StopHapticEffects 符号时靠这里）
            for (int id : effectIds.values()) {
                lib.hapticStopEffect(haptic, id);
            }
        }
        // ③ 销毁设备上的一切效果（释放槽位；含失去追踪的残留）
        purgeHapticEffects();
        // 马达震动也要停（0 力度）
        if (rumbleChecked && rumbleAvailable) {
            try {
                lib.rumbleJoystick(joystick, 0, 0, 0);
            } catch (Throwable ignored) {
            }
        }
        lastForce = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            stopForce();
        } catch (Throwable ignored) {
        }
        if (haptic != null) {
            for (int id : effectIds.values()) {
                lib.hapticDestroyEffect(haptic, id);
            }
            effectIds.clear();
            lib.hapticClose(haptic);
            haptic = null;
        }
        lib.joystickClose(joystick);
        backend.sdlRelease(lib);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("设备已关闭: " + name);
        }
    }
}
