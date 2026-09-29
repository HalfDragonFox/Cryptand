/**
 * ===== 键盘输入后端（2026-09-14）=====
 *
 * 用户需求："需要支持比如键盘、手柄、方向盘等常见类型" + "常见设备最好都上"。
 *
 * <p><b>为什么不用 SDL</b>：SDL3 有键盘 API（{@code SDL_GetKeyboardState}），但它返回的是
 * <b>SDL 自己事件泵维护</b>的按键状态 —— 只有键盘事件发到 SDL 创建的窗口时才会更新。
 * MC 的窗口是 GLFW 建的，SDL 收不到任何键盘事件，那张表永远是全 0；SDL 的
 * joystick/gamepad 枚举里也不含键盘。⇒ 键盘必须走 GLFW。
 *
 * <p><b>线程约束</b>：GLFW 不是线程安全的，{@code glfwGetKey} 只能在主线程调用。
 * 因此采样由主线程每 tick 驱动（{@link #pumpMainThread()}），结果写进
 * {@link KeyboardInputController} 的不可变快照；设备池的后台线程只读快照 ——
 * 与 SDL 设备走的是同一套"无锁快照"约定。
 */

package com.hdf.cryptand.neoforge.gameinput;

import com.hdf.cryptand.gameinput.GameInputBackend;
import com.hdf.cryptand.gameinput.GameInputController;
import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;

import java.util.List;

public final class KeyboardInputBackend implements GameInputBackend {

    /** 设备 id 前缀（{@code GameInputProvider.open} 按它路由） */
    public static final String PREFIX = "keyboard";

    /**
     * 控制器懒加载：{@code KeyboardInputController} 引用 {@code Minecraft}/{@code GLFW}
     * （纯客户端类），专用服务端加载本类时绝不能触发它的类初始化。
     */
    private static volatile KeyboardInputController controller;

    /**
     * 设备名用英文：name 是"设备物理名"（与 "Logitech G29…" 一致，本来就不本地化），
     * 而且设备池用 name 生成稳定 id（{@code stableId}）—— 中文名会被 slug 成 "device"。
     */
    private static final GameInputDeviceInfo INFO = new GameInputDeviceInfo(
            PREFIX + ":main", "Keyboard", GameInputDeviceKind.KEYBOARD, false);

    private static KeyboardInputController controller() {
        KeyboardInputController c = controller;
        if (c == null) {
            synchronized (KeyboardInputBackend.class) {
                c = controller;
                if (c == null) {
                    c = new KeyboardInputController();
                    controller = c;
                }
            }
        }
        return c;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public boolean available() {
        return true;   // GLFW 由 MC 保证已就绪，无需加载原生库
    }

    @Override
    public String lastError() {
        return "";
    }

    @Override
    public List<GameInputDeviceInfo> listDevices() {
        return List.of(INFO);
    }

    @Override
    public GameInputController open(GameInputDeviceInfo info) {
        KeyboardInputController c = controller();
        c.reopen();
        return c;
    }

    /** 主线程每 tick 调用：采样一次键盘（GLFW 只能在主线程碰）。 */
    public static void pumpMainThread() {
        controller().sampleOnMainThread();
    }

    // 主线程事件见 KeyboardInputEvents（GLFW 与 SDL 两条键盘路径统一挂在那里）

    public KeyboardInputBackend() {
    }
}
