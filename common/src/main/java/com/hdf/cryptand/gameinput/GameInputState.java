package com.hdf.cryptand.gameinput;

/**
 * 游戏输入控制器瞬时状态（一帧采样，轴值均为归一化 -1..1）。
 * <p>
 * {@code axes} 为 8 轴标准顺序（DirectInput DIJOYSTATE2 布局，跨后端一致）：
 * <pre>
 *   0 = X      （方向盘=转向角；手柄=左摇杆 X）   4 = Ry
 *   1 = Y      （方向盘=离合；手柄=左摇杆 Y）     5 = Rz
 *   2 = Z      （方向盘=油门；手柄=左扳机）       6 = Slider0（罗技档位杆）
 *   3 = Rx     （方向盘=刹车；手柄=右扳机）       7 = Slider1
 * </pre>
 * 方向盘语义便捷方法 {@link #steering()}/{@link #throttle()}/{@link #brake()}/
 * {@link #clutch()} 按常见罗技布局映射（G29：X=转向、Z=油门、Y=离合、Rx=刹车；
 * G27 等 Y/Rx 互换——原始 {@code axes} 仍可读）。
 *
 * @param connected      采样时设备是否连接
 * @param axes            8 轴归一化值（-1..1；踏板类轴未踩约 0，全踩约 1 或 -1，见便捷方法）
 * @param buttons         按钮位图（bit i = 1 表示按钮 i 按下；支持前 64 键）
 * @param pov             帽开关方向（-1 = 未按下；否则 0..35999，单位 0.01 度）
 * @param keys            当前按下的键盘按键（GLFW key code；仅键盘后端非空）
 * @param timestampNanos  采样时刻（System.nanoTime）
 */
public record GameInputState(
        boolean connected,
        float[] axes,
        long buttons,
        int pov,
        int[] keys,
        long timestampNanos) {

    /** 轴索引常量 */
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;
    public static final int AXIS_RX = 3;
    public static final int AXIS_RY = 4;
    public static final int AXIS_RZ = 5;
    public static final int AXIS_SLIDER0 = 6;
    public static final int AXIS_SLIDER1 = 7;
    /** 轴数量 */
    public static final int AXIS_COUNT = 8;

    /** 空状态（未连接设备时的安全默认） */
    public static GameInputState disconnected() {
        return new GameInputState(false, new float[AXIS_COUNT], 0L, -1, EMPTY_KEYS, 0L);
    }

    /** 空键盘表（复用，避免每次分配） */
    public static final int[] EMPTY_KEYS = new int[0];

    /** 键盘按键是否按下（GLFW key code；非键盘设备恒 false） */
    public boolean isKeyDown(int glfwKey) {
        if (keys == null) return false;
        for (int k : keys) {
            if (k == glfwKey) return true;
        }
        return false;
    }

    /** 取轴值（越界返回 0） */
    public float axis(int index) {
        if (axes == null || index < 0 || index >= axes.length) return 0f;
        return axes[index];
    }

    /** 按钮是否按下 */
    public boolean isButtonDown(int index) {
        return index >= 0 && index < 64 && (buttons & (1L << index)) != 0;
    }

    // ===== 方向盘语义便捷（映射见类注释；通用输入仍以 axes() 为准） =====

    /** 转向角（-1..1，左负右正；= axes[0]） */
    public float steering() {
        return axis(AXIS_X);
    }

    /** 油门踏板（0..1；= (axes[2]+1)/2，G29 油门在 Z 轴） */
    public float throttle() {
        return pedal(axis(AXIS_Z));
    }

    /** 刹车踏板（0..1；= (axes[3]+1)/2，G29 刹车在 Rx 轴） */
    public float brake() {
        return pedal(axis(AXIS_RX));
    }

    /** 离合踏板（0..1；= (axes[1]+1)/2，G29 离合在 Y 轴） */
    public float clutch() {
        return pedal(axis(AXIS_Y));
    }

    private static float pedal(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
