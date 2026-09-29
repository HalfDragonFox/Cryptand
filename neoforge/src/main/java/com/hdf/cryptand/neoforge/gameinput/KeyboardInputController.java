/**
 * ===== 键盘控制器（GLFW 采样 → 不可变快照）=====
 *
 * 主线程采样（{@link #sampleOnMainThread()}），后台线程读 {@link #getState()}。
 * 键位复用 {@link com.hdf.cryptand.gameinput.GameInputState#keys()}（GLFW key code 列表）。
 */

package com.hdf.cryptand.neoforge.gameinput;

import com.hdf.cryptand.gameinput.GameInputController;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import com.hdf.cryptand.gameinput.GameInputState;
import com.hdf.cryptand.gameinput.ForceParams;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

final class KeyboardInputController implements GameInputController {

    /** GLFW 键码扫描区间：[SPACE=32, LAST=348]，覆盖字母/数字/功能键/小键盘/修饰键 */
    private static final int FIRST_KEY = GLFW.GLFW_KEY_SPACE;
    private static final int LAST_KEY = GLFW.GLFW_KEY_LAST;
    /** 单帧最多记录的按键数（超过就截断；换挡/开火这类场景远用不到 16 个） */
    private static final int MAX_KEYS = 16;

    private volatile GameInputState cached = GameInputState.disconnected();
    private volatile boolean open = true;

    @Override
    public String id() {
        return KeyboardInputBackend.PREFIX + ":main";
    }

    @Override
    public String name() {
        return "键盘";
    }

    @Override
    public GameInputDeviceKind kind() {
        return GameInputDeviceKind.KEYBOARD;
    }

    @Override
    public boolean isConnected() {
        return open;
    }

    /** 后台线程读快照（零阻塞，永不抛异常） */
    @Override
    public GameInputState getState() {
        return cached;
    }

    @Override
    public boolean hasForceFeedback() {
        return false;   // 键盘没有力反馈
    }

    @Override
    public void setForce(ForceParams params) {
        throw new UnsupportedOperationException("键盘不支持力反馈");
    }

    @Override
    public void setForceEnabled(boolean enabled) {
    }

    @Override
    public void stopForce() {
    }

    @Override
    public void close() {
        open = false;
        cached = GameInputState.disconnected();
    }

    /** 重新打开（设备池在引用计数归零后关闭，再次申请时复用同一实例） */
    void reopen() {
        open = true;
    }

    /** ★ 主线程每 tick：把整片键盘状态采成一个不可变快照。 */
    void sampleOnMainThread() {
        if (!open) {
            cached = GameInputState.disconnected();
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        long handle = window == null ? 0L : window.getWindow();
        if (handle == 0L) {
            cached = GameInputState.disconnected();
            return;
        }
        int[] keys = new int[MAX_KEYS];
        int n = 0;
        for (int key = FIRST_KEY; key <= LAST_KEY && n < MAX_KEYS; key++) {
            if (GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS) {
                keys[n++] = key;
            }
        }
        cached = new GameInputState(true, new float[GameInputState.AXIS_COUNT], 0L, -1,
                n == 0 ? GameInputState.EMPTY_KEYS : Arrays.copyOf(keys, n), System.nanoTime());
    }
}
