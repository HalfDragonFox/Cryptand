package com.hdf.cryptand.gameinput;

import com.hdf.cryptand.gameinput.sdl.SdlBackend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 游戏输入门面（2026-09-08 通用输入后端：后端注册表 + 能力入口，全静态）。
 * <p>
 * common 侧【不自动加载任何后端/dll】——由平台层按配置显式注册（懒加载 + 显式指定）：
 * <pre>{@code
 * // 平台层（neoforge commonSetup / fabric onInitialize）按配置注册：
 * GameInputProvider.clearBackends();
 * if (config.enableGameInput) {
 *     GameInputProvider.registerSdl();   // SDL2 跨平台后端（唯一后端）
 * }
 *
 * // 使用侧：
 * List<GameInputDeviceInfo> devices = GameInputProvider.listDevices();
 * GameInputController c = GameInputProvider.open(devices.get(0));
 * GameInputState s = c.getState();
 * c.setForce(ForceParams.spring(0, 8000));   // 方向盘力反馈
 * c.close();
 * }</pre>
 * 生命周期记账请用 {@link GameInputManager}（acquire/release/组/退出游戏 shutdown）。
 */
public final class GameInputProvider {

    private static final List<GameInputBackend> BACKENDS = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded = false;

    private GameInputProvider() {
    }

    // ==================== 子包加载/卸载（懒加载生命周期） ====================

    /**
     * 加载 gameinput 子包（幂等）：按 {@link GameInputConfig#isInputEnabled()} 注册
     * SDL2 后端（SDL2 库本身懒加载：首次使用才加载 dll）。使用前需加载方可正常
     * 初始化使用——本门面在 listDevices/open 前自动懒加载；显式调用用于提前就绪。
     */
    public static void load() {
        if (loaded) return;
        loaded = true;
        if (GameInputConfig.isInputEnabled()) {
            registerSdl();
        }
    }

    /**
     * 卸载 gameinput 子包（幂等）：关闭全部已登记实例（经 {@link GameInputManager#DEFAULT}）
     * 并清空后端注册；之后可再次 {@link #load} 或由使用自动懒加载。用于节省内存/兼容场景。
     */
    public static void unload() {
        if (!loaded) return;
        GameInputManager.DEFAULT.closeAll();
        clearBackends();
        loaded = false;
    }

    /** gameinput 子包是否已加载 */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void ensureLoaded() {
        if (!loaded) load();
    }

    // ==================== 后端注册（平台按配置调用） ====================

    /** 显式注册后端（同一后端类型只允许注册一次，重复注册忽略） */
    public static void registerBackend(GameInputBackend backend) {
        if (backend == null) return;
        for (GameInputBackend b : BACKENDS) {
            if (b.getClass() == backend.getClass()) return; // 同类型去重
        }
        BACKENDS.add(backend);
    }

    /**
     * SDL2 跨平台后端（Linux/macOS/Windows 统一路径：evdev/IOKit HID/DirectInput+XInput；
     * 需系统 SDL2 库；懒加载）。当前唯一后端（2026-09-08 用户定稿：其余后端废弃）。
     */
    public static void registerSdl() {
        registerBackend(new SdlBackend());
    }

    /** SDL2 跨平台后端（指定库名/路径） */
    public static void registerSdl(String libName) {
        registerBackend(new SdlBackend(libName));
    }

    /** 清空全部后端（平台重载配置时调用，防重复注册） */
    public static void clearBackends() {
        BACKENDS.clear();
    }

    /** 当前已注册后端列表 */
    public static List<GameInputBackend> backends() {
        return new ArrayList<>(BACKENDS);
    }

    // ==================== 能力入口 ====================

    /** 枚举全部已注册且可用后端的设备（未加载自动懒加载；子包未启用返回空列表） */
    public static List<GameInputDeviceInfo> listDevices() {
        ensureLoaded();
        if (!GameInputConfig.isInputEnabled()) return List.of();
        List<GameInputDeviceInfo> out = new ArrayList<>();
        for (GameInputBackend b : BACKENDS) {
            if (b.available()) {
                try {
                    out.addAll(b.listDevices());
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 打开指定设备（未加载自动懒加载；按设备 id 前缀路由到对应后端；子包未启用抛 IllegalStateException） */
    public static GameInputController open(GameInputDeviceInfo info) throws IOException {
        ensureLoaded();
        ensureEnabled();
        for (GameInputBackend b : BACKENDS) {
            if (b.available() && info != null && info.id() != null
                    && info.id().startsWith(b.prefix() + ":")) {
                return b.open(info);
            }
        }
        throw new IOException("无可用后端处理设备: " + (info != null ? info.id() : "null")
                + "（已注册: " + BACKENDS + "）");
    }

    /** 便捷：打开第一个可用设备（未加载自动懒加载；无设备抛 IOException） */
    public static GameInputController openFirst() throws IOException {
        ensureLoaded();
        ensureEnabled();
        List<GameInputDeviceInfo> devices = listDevices();
        if (devices.isEmpty()) {
            throw new IOException("未检测到游戏输入设备（已注册后端均无可枚举设备）");
        }
        return open(devices.get(0));
    }

    private static void ensureEnabled() {
        if (!GameInputConfig.isInputEnabled()) {
            throw new IllegalStateException("游戏输入未启用（核心配置 enableGameInput=false，config/cryptand/common.toml）");
        }
    }
}
