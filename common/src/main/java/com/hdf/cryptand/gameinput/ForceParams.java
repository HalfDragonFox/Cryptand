package com.hdf.cryptand.gameinput;

/**
 * 力反馈效果参数（不可变；经 {@link GameInputController#setForce} 下发到设备；
 * 方向盘专属扩展能力，非力反馈设备忽略/拒绝）。
 * <p>
 * 字段含义随效果类型不同而取舍（未用字段忽略即可），推荐用静态工厂构造：
 * <pre>{@code
 * wheel.setForce(ForceParams.spring(0, 8000));        // 弹簧回中：中心 0，系数 8000
 * wheel.setForce(ForceParams.damper(6000));           // 阻尼 6000
 * wheel.setForce(ForceParams.constant(4000));         // 恒定力 4000（顺时针）
 * wheel.setForce(ForceParams.sine(3000, 50));         // 正弦：幅度 3000，周期 50ms
 * wheel.stopForce();                                  // 停止当前效果
 * }</pre>
 * 所有系数/幅度取值范围 {@code -10000..10000}（饱和/系数 {@code 0..10000}）。
 *
 * @param effect             效果类型
 * @param magnitude          幅度（CONSTANT/SINE；-10000..10000）
 * @param offset             中心偏移（SPRING；0..10000）
 * @param positiveCoef       正向系数（SPRING/DAMPER/FRICTION/INERTIA；0..10000）
 * @param negativeCoef       负向系数（同上；0..10000）
 * @param positiveSaturation 正向饱和（SPRING；0..10000）
 * @param negativeSaturation 负向饱和（SPRING；0..10000）
 * @param periodMs           周期（SINE；毫秒）
 * @param phaseDeg           相位（SINE；0..359）
 * @param durationMs         持续时间（毫秒；0 = 持续到 stopForce/新效果）
 * @param gain               全局增益（0..10000；0 = 默认 10000）
 */
public record ForceParams(
        ForceEffect effect,
        int magnitude,
        int offset,
        int positiveCoef,
        int negativeCoef,
        int positiveSaturation,
        int negativeSaturation,
        int periodMs,
        int phaseDeg,
        int durationMs,
        int gain) {

    /** 无限时长 */
    public static final int INFINITE = 0;
    /** 默认增益（10000 = 100%） */
    public static final int MAX_GAIN = 10000;

    /** 恒定力（正值顺时针；-10000..10000） */
    public static ForceParams constant(int magnitude) {
        return new ForceParams(ForceEffect.CONSTANT, clamp(magnitude), 0,
                0, 0, 0, 0, 0, 0, INFINITE, MAX_GAIN);
    }

    /** 弹簧回中力（offset=回中偏置 0..10000；coefficient=刚度 0..10000） */
    public static ForceParams spring(int offset, int coefficient) {
        return new ForceParams(ForceEffect.SPRING, 0, clamp(offset),
                clamp(coefficient), clamp(coefficient), MAX_GAIN, MAX_GAIN, 0, 0, INFINITE, MAX_GAIN);
    }

    /** 阻尼力（coefficient 0..10000） */
    public static ForceParams damper(int coefficient) {
        return new ForceParams(ForceEffect.DAMPER, 0, 0,
                clamp(coefficient), clamp(coefficient), 0, 0, 0, 0, INFINITE, MAX_GAIN);
    }

    /**
     * 惯性力（coefficient 0..10000；力 ∝ 轴向加速度）。
     * <p>用于"颠簸 / 落地 / 上下坡"的冲击通道：加速度突变越猛，手感越硬。
     */
    public static ForceParams inertia(int coefficient) {
        return new ForceParams(ForceEffect.INERTIA, 0, 0,
                clamp(coefficient), clamp(coefficient), 0, 0, 0, 0, INFINITE, MAX_GAIN);
    }

    /** 正弦周期力（magnitude 0..10000；periodMs 毫秒） */
    public static ForceParams sine(int magnitude, int periodMs) {
        return new ForceParams(ForceEffect.SINE, clamp(magnitude), 0,
                0, 0, 0, 0, Math.max(1, periodMs), 0, INFINITE, MAX_GAIN);
    }

    private static int clamp(int v) {
        return Math.max(-10000, Math.min(10000, v));
    }
}
