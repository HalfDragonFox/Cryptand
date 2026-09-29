package com.hdf.cryptand.gameinput;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通用游戏输入后端自测（2026-09-08）：
 * <pre>./gradlew :common:runInputTest</pre>
 * 无硬件也可运行：验证 ForceParams 工厂 / GameInputState / 配置门控 / 后端注册表
 * （fake 后端前缀路由）/ SDL2 后端降级探测 / GameInputManager 生命周期。
 */
public final class GameInputSelfTest {

    private static final List<String> FAILS = new CopyOnWriteArrayList<>();
    private static int passed = 0;

    public static void main(String[] args) {
        System.out.println("=== GameInput 通用输入后端自测 ===");
        try {
            testLoadUnload();
            testForceParams();
            testState();
            testConfigGate();
            testRegistryAndProbe();
            testSdlProbe();
            testManagerLifecycle();
        } catch (Throwable t) {
            FAILS.add("自测异常: " + t);
            t.printStackTrace();
        }
        System.out.println();
        if (FAILS.isEmpty()) {
            System.out.println("✅ 全部通过 (" + passed + " 项)");
        } else {
            FAILS.forEach(f -> System.out.println("❌ FAIL: " + f));
            throw new AssertionError(FAILS.size() + " 项失败");
        }
    }

    private static void testLoadUnload() {
        // 懒加载生命周期：初始未加载 → load → unload → 使用自动重载
        check("生命周期: 初始未加载", !GameInputProvider.isLoaded(), "false");
        GameInputProvider.load();
        check("生命周期: load 后已加载", GameInputProvider.isLoaded(), "true");
        GameInputProvider.load();
        check("生命周期: load 幂等", GameInputProvider.isLoaded(), "true");
        GameInputProvider.unload();
        check("生命周期: unload 后未加载", !GameInputProvider.isLoaded(), "false");
        check("生命周期: unload 清空后端", GameInputProvider.backends().isEmpty(), "0");
        GameInputProvider.listDevices(); // 使用自动懒加载（配置默认开 → 注册 SDL）
        check("生命周期: 使用自动重载", GameInputProvider.isLoaded(), "true");
        GameInputProvider.unload();
    }

    private static void testForceParams() {
        ForceParams spring = ForceParams.spring(0, 8000);
        check("力反馈: spring 类型", spring.effect() == ForceEffect.SPRING, "SPRING");
        check("力反馈: spring 系数", spring.positiveCoef() == 8000 && spring.negativeCoef() == 8000, "8000");
        check("力反馈: spring 增益", spring.gain() == ForceParams.MAX_GAIN, "10000");
        ForceParams constant = ForceParams.constant(4000);
        check("力反馈: constant 幅度", constant.effect() == ForceEffect.CONSTANT && constant.magnitude() == 4000, "4000");
        check("力反馈: damper 系数", ForceParams.damper(6000).positiveCoef() == 6000, "6000");
        check("力反馈: sine 周期", ForceParams.sine(3000, 50).periodMs() == 50, "50ms");
        check("力反馈: 幅度钳制", ForceParams.constant(20000).magnitude() == 10000, "10000");
    }

    private static void testState() {
        GameInputState s = new GameInputState(true,
                new float[]{0.5f, 0.2f, 0.8f, 0.1f, 0f, 0f, 0f, 0f}, 0b101L, -1,
                new int[]{87}, 123L);   // 87 = GLFW_KEY_W
        check("状态: 轴索引", Math.abs(s.axis(GameInputState.AXIS_X) - 0.5f) < 1e-6f
                && Math.abs(s.axis(GameInputState.AXIS_Z) - 0.8f) < 1e-6f, "0.5/0.8");
        check("状态: 方向盘便捷", Math.abs(s.steering() - 0.5f) < 1e-6f
                && Math.abs(s.throttle() - 0.8f) < 1e-6f
                && Math.abs(s.brake() - 0.1f) < 1e-6f, "steering/throttle/brake");
        check("状态: 按钮位图", s.isButtonDown(0) && s.isButtonDown(2) && !s.isButtonDown(1), "bits 0,2");
        check("状态: 越界轴=0", s.axis(99) == 0f, "0");
        check("状态: 断开默认", !GameInputState.disconnected().connected(), "disconnected");
    }

    private static void testConfigGate() throws Exception {
        // 默认：子包启用
        check("门控: 默认子包启用", GameInputConfig.isInputEnabled(), "true");

        GameInputConfig.configure(false);
        check("门控: 关闭后枚举空", GameInputProvider.listDevices().isEmpty(), "empty");
        boolean threw = false;
        try {
            GameInputProvider.open(new GameInputDeviceInfo("fake:1", "Fake", GameInputDeviceKind.WHEEL, true));
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("门控: 关闭后 open 拒绝", threw, "IllegalStateException");
        boolean mgrThrew = false;
        try {
            new GameInputManager().acquire(new GameInputDeviceInfo("fake:1", "Fake", GameInputDeviceKind.WHEEL, true));
        } catch (IllegalStateException e) {
            mgrThrew = true;
        }
        check("门控: 管理类 acquire 拒绝", mgrThrew, "IllegalStateException");

        GameInputConfig.configure(true);
        check("门控: 恢复子包启用", GameInputConfig.isInputEnabled(), "true");
    }

    private static void testRegistryAndProbe() {
        GameInputProvider.clearBackends();
        // 未注册任何后端：枚举空、open 报无后端
        check("注册表: 默认无后端", GameInputProvider.backends().isEmpty(), "0");
        check("注册表: 无后端枚举空", GameInputProvider.listDevices().isEmpty(), "empty");

        // 注册唯一后端 SDL2（本机无 SDL2.dll → 懒加载失败但注册表可用）
        GameInputProvider.registerSdl();
        check("注册表: 注册后 1 后端", GameInputProvider.backends().size() == 1, "1");
        boolean sdlAvail = GameInputProvider.backends().get(0).available();
        System.out.println("ℹ SDL2 后端可用=" + sdlAvail
                + (sdlAvail ? "" : "（原因: " + GameInputProvider.backends().get(0).lastError() + "）"));
        check("注册表: available 可调用", sdlAvail == true || sdlAvail == false, String.valueOf(sdlAvail));

        // 重复注册去重
        GameInputProvider.registerSdl();
        check("注册表: 重复注册去重", GameInputProvider.backends().size() == 1, "1");

        // fake 后端 + 前缀路由
        GameInputProvider.registerBackend(new FakeBackend());
        check("注册表: fake 注册后 2 后端", GameInputProvider.backends().size() == 2, "2");
        List<GameInputDeviceInfo> devices = GameInputProvider.listDevices();
        check("注册表: 枚举合并", devices.size() >= 1 && devices.stream().anyMatch(d -> d.id().startsWith("fake:")), devices.toString());
        try {
            GameInputController c = GameInputProvider.open(new GameInputDeviceInfo("fake:1", "Fake", GameInputDeviceKind.WHEEL, true));
            check("注册表: 前缀路由 open", c != null && c.id().equals("fake:1"), "fake:1");
            c.close();
        } catch (IOException e) {
            check("注册表: 前缀路由 open", false, String.valueOf(e));
        }

        GameInputProvider.clearBackends();
        check("注册表: 清空", GameInputProvider.backends().isEmpty(), "0");
    }

    private static void testSdlProbe() {
        // SDL2 跨平台后端探测：本机（Windows）无 SDL2.dll → 优雅降级（诊断原因）
        com.hdf.cryptand.gameinput.sdl.SdlBackend sb = new com.hdf.cryptand.gameinput.sdl.SdlBackend();
        boolean avail = sb.available();
        System.out.println("ℹ SDL2 后端可用=" + avail + (avail ? "" : "（原因: " + sb.lastError() + "）"));
        check("SDL: available 可调用", avail == true || avail == false, String.valueOf(avail));
        try {
            List<GameInputDeviceInfo> devices = sb.listDevices();
            check("SDL: 枚举不抛异常", true, devices.toString());
            System.out.println("ℹ SDL 设备 " + devices.size() + " 个" + (devices.isEmpty() ? "（无 SDL2 库/设备）" : " -> " + devices));
        } catch (Throwable t) {
            check("SDL: 枚举不抛异常", false, String.valueOf(t));
        }
    }

    private static void testManagerLifecycle() throws Exception {
        GameInputProvider.clearBackends();
        GameInputProvider.registerBackend(new FakeBackend());
        GameInputManager mgr = new GameInputManager();

        GameInputController c1 = mgr.acquireFirst();
        check("管理: acquire 成功", c1 != null && c1.isConnected(), "open");
        check("管理: size=1", mgr.size() == 1, "1");

        GameInputController c2 = mgr.acquire("DRIVER", new GameInputDeviceInfo("fake:2", "Fake 2", GameInputDeviceKind.GAMEPAD, false));
        check("管理: 组申请 size=2", mgr.size() == 2, "2");
        check("管理: controllersOf(DRIVER)=1", mgr.controllersOf("DRIVER").size() == 1, "1");
        check("管理: UUID 分配", mgr.idOf(c1) != null && !mgr.idOf(c1).equals(mgr.idOf(c2)), "uuid");
        check("管理: 组归属", mgr.groupOf(c2).equals("DRIVER") && mgr.groupOf(c1).equals(GameInputManager.DEFAULT_GROUP), "DRIVER/default");

        boolean rel = mgr.release(c1);
        check("管理: release 先关闭", rel && !c1.isConnected(), "closed");
        check("管理: release 后 size=1", mgr.size() == 1, "1");
        check("管理: 重复 release=false", !mgr.release(c1), "false");

        UUID id2 = mgr.idOf(c2);
        check("管理: release(uuid)", mgr.release(id2) && !c2.isConnected(), "closed");
        check("管理: release(uuid) 后 empty", mgr.isEmpty(), "empty");

        GameInputController c3 = mgr.acquire(new GameInputDeviceInfo("fake:3", "Fake 3", GameInputDeviceKind.WHEEL, true));
        GameInputController c4 = mgr.acquire("X", new GameInputDeviceInfo("fake:4", "Fake 4", GameInputDeviceKind.JOYSTICK, false));
        mgr.shutdown();
        check("管理: shutdown 全部关闭", !c3.isConnected() && !c4.isConnected(), "closed");
        check("管理: shutdown 清空", mgr.size() == 0 && mgr.isShutdown(), "empty");
        boolean threw = false;
        try {
            mgr.acquire(new GameInputDeviceInfo("fake:5", "Fake 5", GameInputDeviceKind.OTHER, false));
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("管理: shutdown 后拒绝申请", threw, "IllegalStateException");
        GameInputProvider.clearBackends();
    }

    // ==================== fake 后端 ====================

    private static final class FakeBackend implements GameInputBackend {
        @Override
        public String prefix() {
            return "fake";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String lastError() {
            return null;
        }

        @Override
        public List<GameInputDeviceInfo> listDevices() {
            return List.of(new GameInputDeviceInfo("fake:1", "Fake 1", GameInputDeviceKind.WHEEL, true));
        }

        @Override
        public GameInputController open(GameInputDeviceInfo info) {
            return new FakeController(info);
        }
    }

    private static final class FakeController implements GameInputController {
        private final GameInputDeviceInfo info;
        private volatile boolean closed;

        FakeController(GameInputDeviceInfo info) {
            this.info = info;
        }

        @Override public String id() { return info.id(); }
        @Override public String name() { return info.name(); }
        @Override public GameInputDeviceKind kind() { return info.kind(); }
        @Override public boolean isConnected() { return !closed; }
        @Override public GameInputState getState() { return closed ? GameInputState.disconnected()
                : new GameInputState(true, new float[8], 0L, -1, GameInputState.EMPTY_KEYS, 1L); }
        @Override public boolean hasForceFeedback() { return info.forceFeedback(); }
        @Override public void setForce(ForceParams params) { }
        @Override public void setForceEnabled(boolean enabled) { }
        @Override public void stopForce() { }
        @Override public void close() { closed = true; }
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("✅ " + label + " (" + detail + ")");
        } else {
            FAILS.add(label + " -> " + detail);
            System.out.println("❌ FAIL: " + label + " -> " + detail);
        }
    }
}
