package com.hdf.cryptand.neoforge.gameinput;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.core.paths.CryptandPaths;
import com.hdf.cryptand.neoforge.gameinput.config.ConfigGameInput;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ===== 硬件 I/O（游戏输入）子包入口 =====
 * 后端实现在 common（com.hdf.cryptand.gameinput，<b>SDL3</b> 懒加载）；本子包承载其配置。
 *
 * 本地库解析顺序（2026-09-13 用户定稿）：
 * <ol>
 *   <li><b>{@code <gameDir>/cryptand/libs/}</b> —— 玩家/整合包可替换（最高优先级，
 *       放新版 SDL3 库即生效，无需改 jar）；</li>
 *   <li><b>mod 内置</b> —— classpath {@code /natives/<platform>/} 解压到
 *       {@code <gameDir>/cryptand/libs/natives/<platform>/}（次优先，不覆盖玩家文件）；</li>
 *   <li>工作目录 / {@code natives/} / {@code sdl/}（dev 环境便利）；</li>
 *   <li>系统库 / PATH / {@code -Djna.library.path}（最终兜底）。</li>
 * </ol>
 * 启动时同时创建通用工作区 {@code cryptand/{libs,temp}}（见 {@link CryptandPaths}）。
 * ⚠ Windows 的 {@code LoadLibrary} 在 SafeDllSearchMode 下<em>不搜索</em>当前工作目录，
 * 所以必须显式 {@code addSearchPath} + {@code jna.library.path}。
 */
@CryptandSubpackage(id = "gameinput", order = 95)
public final class GameInputEntry implements SubpackageEntry {

    public static final GameInputEntry INSTANCE = new GameInputEntry();

    /**
     * 键盘后端总开关 —— <b>已启用</b>。
     * <p>用户 2026-09-14 定稿："键盘走 SDL，虽然需要焦点运行，但是不做留空，还是实现代码，
     * 默认 GLFW 好了" + "SDL 不做留空，也实现对应代码保证对齐" + "为焦点窗口这个特性做支持"。
     * <p>于是注册<b>两个</b>键盘后端，各自实现同一套 {@code GameInputBackend} SPI：
     * <ul>
     *   <li>{@link KeyboardInputBackend} —— GLFW（<b>默认</b>，用 MC 自己的窗口，无焦点限制）；</li>
     *   <li>{@link com.hdf.cryptand.gameinput.sdl.SdlKeyboardBackend} —— SDL 自建窗口
     *       （只有被绑定时才创建/显示，聚焦那个窗口即可用）。</li>
     * </ul>
     */
    private static final boolean ENABLE_KEYBOARD_BACKEND = true;

    /** SDL3 库在各平台的候选文件名（不再包含 SDL2 —— 用户要求彻底弃用旧版本） */
    private static final String[] LIB_NAMES = {
            "SDL3", "SDL3.dll", "libSDL3.so.0", "libSDL3.so", "libSDL3.dylib"
    };

    /** 判定"目录里确实有 SDL3 库"用的实际文件名 */
    private static final String[] LIB_FILE_NAMES = {
            "SDL3.dll", "libSDL3.so.0", "libSDL3.so", "libSDL3.dylib"
    };

    private GameInputEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigGameInput.register();
    }

    @Override
    public boolean enabled() {
        try {
            return ConfigGameInput.SPEC.isLoaded()
                    ? ConfigGameInput.ENABLE_GAME_INPUT.get()
                    : ConfigLoad.preloadBoolean("gameinput", "enableGameInput", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "gameinput.toml#enableGameInput";
    }

    @Override
    public void init(IEventBus bus) {
        // ⚠ 2026-08-30 子包开关彻底生效：硬件 I/O 生命周期（输入/串口）原为
        // @EventBusSubscriber 自动注册（绕过开关）→ 改为本子包显式注册。
        try {
            // ⚠⚠ 2026-09-11 总线修复：两者监听 ServerStoppingEvent（【游戏总线】）——
            // 挂 MOD 总线会静默失效（服务器停止时输入/串口硬件不释放）。
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                    com.hdf.cryptand.neoforge.core.input.InputLifecycle.class);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                    com.hdf.cryptand.neoforge.core.serial.SerialLifecycle.class);
        } catch (Throwable ignored) {
        }

        // ===== 键盘后端（仅客户端）：GLFW 采样 + 每 tick 主线程驱动 =====
        // ⚠ 只在客户端注册：KeyboardInputController 引用 Minecraft/GLFW，专用服务端
        //   既没有窗口也没有这些类，注册了只会白占一个永远读不到东西的后端。
        //
        // ★ 开关 = ENABLE_KEYBOARD_BACKEND（用户 2026-09-14 定稿："键盘先强制部分留空，
        //   暂时不使用；如果自己的窗口有焦点时这个也可以的，不过键盘强制部分添加 GLFW 和
        //   SDL 强制两个部分，默认走 SDL"）。
        //   —— 设备强制类型里已经给键盘留了【键盘(SDL)】【键盘(GLFW)】两个槽位（默认 SDL），
        //      但设备枚举暂不提供键盘；等 SDL 侧的窗口/事件方案落地后把此常量改为 true，
        //      后端与采样事件一并生效，无需改其它代码。
        if (ENABLE_KEYBOARD_BACKEND && net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            try {
                com.hdf.cryptand.gameinput.GameInputProvider.registerBackend(
                        new KeyboardInputBackend());
                com.hdf.cryptand.gameinput.GameInputProvider.registerBackend(
                        new com.hdf.cryptand.gameinput.sdl.SdlKeyboardBackend());
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                        KeyboardInputEvents.class);
                log().info("[GameInput] 键盘后端已注册：GLFW（默认）+ SDL（焦点窗口），"
                        + "与手柄/方向盘同走 GameInputBackend SPI");
            } catch (Throwable t) {
                log().warn("[GameInput] 键盘后端注册失败: {}", String.valueOf(t));
            }
        }
    }

    /**
     * FMLCommonSetup（在 {@code CryptandNeoForge} 执行 {@code GameInputProvider.load()} 之后、
     * SDL3 库【实际加载之前】调用——库是懒加载）：建工作区 + 解析库来源 + 注入搜索路径 + 诊断。
     */
    @Override
    public void commonSetup() {
        // ===== 0) 通用工作区：<gameDir>/cryptand/{libs,temp} =====
        try {
            CryptandPaths.ensureLayout();
        } catch (final Throwable ignored) {
        }

        // ⚠ 库解析在【两端】都做（用户："服务端也解压 SDL3，万一有其他服务器需要用到的内容"）：
        //   库文件与 jna.library.path 在任何一端都就位，将来服务端侧若有需要可直接用；
        //   但【设备枚举】只在客户端 —— 方向盘/手柄插在玩家自己机器上（见下方诊断分支）。
        String source = null;

        // ===== 1) 玩家可替换目录：cryptand/libs（最高优先级）=====
        try {
            final Path libs = CryptandPaths.libs();
            if (containsSdlLib(libs)) {
                registerSearchPath(libs.toString());
                source = "玩家库目录 " + libs;
            }
        } catch (final Throwable ignored) {
        }

        // ===== 2) mod 内置 → cryptand/libs/natives/<platform>/ =====
        final String bundledDir = extractBundledNatives();
        if (bundledDir != null) {
            registerSearchPath(bundledDir);
            if (source == null) {
                source = "mod 内置 " + bundledDir;
            }
        }

        // ===== 3) dev 便利兜底：工作目录 / natives / sdl =====
        if (source == null) {
            final String userDir = System.getProperty("user.dir");
            if (userDir != null) {
                for (final String dir : new String[]{
                        userDir, join(userDir, "natives"), join(userDir, "sdl")}) {
                    if (hasSdlLib(dir)) {
                        registerSearchPath(dir);
                        source = "本地自备 " + dir;
                        break;
                    }
                }
            }
        }

        log().info("[GameInput] SDL3 库来源: {}", source != null
                ? source : "(无玩家/内置库 → 回退系统库 / PATH / -Djna.library.path)");

        // ===== 4) 启动诊断（仅客户端枚举设备；服务端只确认库已就位） =====
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                != net.neoforged.api.distmarker.Dist.CLIENT) {
            log().info("[GameInput] 专用服务端：SDL3 库已就位，跳过设备枚举（设备属于玩家客户端）");
            return;
        }
        try {
            com.hdf.cryptand.gameinput.GameInputConfig.configure(
                    ConfigGameInput.SPEC.isLoaded()
                            ? ConfigGameInput.ENABLE_GAME_INPUT.get()
                            : ConfigLoad.preloadBoolean("gameinput", "enableGameInput", true));
            final java.util.List<com.hdf.cryptand.gameinput.GameInputBackend> backends =
                    com.hdf.cryptand.gameinput.GameInputProvider.backends();
            if (backends.isEmpty()) {
                log().warn("[GameInput] 后端未注册（enableGameInput={}）",
                        com.hdf.cryptand.gameinput.GameInputConfig.isInputEnabled());
                return;
            }
            final com.hdf.cryptand.gameinput.GameInputBackend backend = backends.get(0);
            if (!backend.available()) {
                log().error("[GameInput] SDL3 不可用：{}（可把 SDL3 库放进 {} 或设置 -Djna.library.path）",
                        backend.lastError(), CryptandPaths.libs());
                return;
            }
            // ★ 设备枚举必须【投递到 SDL 线程】执行（2026-09-16 实测根因）：
            //   SDL3 的 Windows 设备由 DirectInput 后端提供，而 DirectInput 把 COM 单元钉在
            //   【首次执行 SDL_Init 的线程】上 —— 本预热跑在 Worker-Main-N（mod 加载 worker），
            //   若在这里 SDL_Init，之后在 Cryptand-SDL 线程 SDL_OpenJoystick 必然报
            //   "IDirectInputDevice8::SetCooperativeLevel() DirectX error 0x80070006 (E_HANDLE)"
            //   （独立探针实证：同线程 Init+Open 全部 OPEN-OK；跨线程 Init→Open 4/4 全失败，
            //    且与 SDL_UpdateJoysticks 无关）。
            //   投递之后：SDL_Init / 枚举 / 打开 / 采样 / 热插拔刷新全在同一条 SDL 线程，
            //   与"以前能正常连接方向盘"的状态一致，也符合本子包"SDL 调用只在 SDL 线程"的规则。
            com.hdf.cryptand.gameinput.sdl.SdlThread.post(() -> {
                try {
                    final int devices =
                            com.hdf.cryptand.gameinput.GameInputProvider.listDevices().size();
                    log().info("[GameInput] SDL3 就绪，枚举到 {} 个设备（线程 {}）",
                            devices, Thread.currentThread().getName());
                } catch (final Throwable t) {
                    log().warn("[GameInput] 设备枚举失败: {}", String.valueOf(t));
                }
            });
        } catch (final Throwable t) {
            log().warn("[GameInput] 诊断失败: {}", String.valueOf(t));
        }
    }

    // ==================== SDL 库来源解析 ====================

    /** 把目录加入全部 SDL3 候选库名的 JNA 搜索路径（并对齐 jna.library.path）；先加的优先 */
    private static void registerSearchPath(final String dir) {
        for (final String name : LIB_NAMES) {
            try {
                com.sun.jna.NativeLibrary.addSearchPath(name, dir);
            } catch (final Throwable ignored) {
            }
        }
        try {
            final String old = System.getProperty("jna.library.path", "");
            if (!old.contains(dir)) {
                System.setProperty("jna.library.path", old.isEmpty()
                        ? dir : old + File.pathSeparator + dir);
            }
        } catch (final Throwable ignored) {
        }
    }

    private static boolean containsSdlLib(final Path dir) {
        try {
            for (final String name : LIB_FILE_NAMES) {
                if (Files.isRegularFile(dir.resolve(name))) {
                    return true;
                }
            }
        } catch (final Throwable ignored) {
        }
        return false;
    }

    private static boolean hasSdlLib(final String dir) {
        try {
            for (final String name : LIB_FILE_NAMES) {
                if (new File(dir, name).isFile()) {
                    return true;
                }
            }
        } catch (final Throwable ignored) {
        }
        return false;
    }

    /**
     * 解压随 mod 分发的本机库：classpath {@code /natives/<platform>/<lib>} →
     * {@code <gameDir>/cryptand/libs/natives/<platform>/<lib>}（大小一致则跳过，避免每次写盘）。
     *
     * @return 解压目录绝对路径；该平台未内置（或失败）→ null（上层继续回退）
     */
    private static String extractBundledNatives() {
        try {
            final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            final boolean arm = arch.contains("aarch64") || arch.contains("arm64");
            final String platformDir;
            final String libName;
            if (os.contains("win")) {
                platformDir = arm ? "windows-arm64" : (arch.contains("64") ? "windows-x64" : "windows-x86");
                libName = "SDL3.dll";
            } else if (os.contains("mac") || os.contains("darwin")) {
                platformDir = arm ? "macos-arm64" : "macos-x64";
                libName = "libSDL3.dylib";
            } else {
                platformDir = arm ? "linux-arm64" : "linux-x64";
                libName = "libSDL3.so.0";
            }

            final String resource = "/natives/" + platformDir + "/" + libName;
            final byte[] data;
            try (InputStream in = GameInputEntry.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;   // 该平台未内置 → 回退系统库
                }
                data = in.readAllBytes();
            }

            final Path target = CryptandPaths.natives(platformDir).resolve(libName);
            if (!Files.isRegularFile(target) || Files.size(target) != data.length) {
                Files.createDirectories(target.getParent());
                Files.write(target, data);
            }
            return target.toAbsolutePath().getParent().toString();
        } catch (final Throwable t) {
            log().warn("[GameInput] 内置 natives 解压失败: {}", String.valueOf(t));
            return null;
        }
    }

    private static String join(final String dir, final String child) {
        return dir + File.separator + child;
    }

    private static org.apache.logging.log4j.Logger log() {
        try {
            return com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER;
        } catch (final Throwable t) {
            return org.apache.logging.log4j.LogManager.getLogger("cryptand");
        }
    }

    @Override
    public void tick(ServerLevel level) {
    }
}
