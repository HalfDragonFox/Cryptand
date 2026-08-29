package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;

/**
 * 求解模式选择器。
 * <p>
 * 2026-08-14 用户决策【完全禁用时域】：统一相量 + 固定节拍 = 伪时域。
 * 无论频率标签如何（含 DC / 低频）一律返回 {@link SolveMode#COMPLEX_AC}：
 *   - AC：频率标签相量求解（ComplexMnaSolver，稳态幅值/相位）。
 *   - DC / 低频：同一相量求解器，ω 取等效小频率（电感≈短路、电容≈开路，
 *     见 {@link ComplexMnaSolver#DC_EQUIV_FREQ_HZ}），固定节拍推进能量状态。
 * <p>
 * 时域（{@link SolveMode#REAL_DC} / RealMnaSolver / stampRealAt 逐采样）
 * 核心代码保留定义但【不再被选择】，为后续内容预留。
 */
public final class SolverModeSelector {

    /** 默认频率阈值（Hz）：低于此值用实时时域，高于或等于用相量频域 */
    public static final double DEFAULT_FREQUENCY_THRESHOLD_HZ = 100.0;

    private SolverModeSelector() {}

    /**
     * 按频率标签 + 阈值选择求解模式。
     * <p>
     * 2026-08-14 用户决策【完全禁用时域】：统一相量 + 固定节拍 = 伪时域。
     * 无论频率标签如何（含 DC / 低频）一律返回 {@link SolveMode#COMPLEX_AC}：
     *   - AC：频率标签相量求解（ComplexMnaSolver，稳态幅值/相位）。
     *   - DC / 低频：同一相量求解器，电容用 Backward Euler 伴随（G=C/dt +
     *     vPrev 历史电流源）逐节拍充电（伪时域——用户 2026-08-21 要求
     *     "DC/AC 都走相量，用节拍达成伪时域"），电感同理。
     * <p>
     * 时域（{@link SolveMode#REAL_DC} / RealMnaSolver / stampRealAt 逐采样）
     * 核心代码保留定义但【不再被选择】，为后续内容预留。
     *
     * @param frequencyHz 网络频率标签（Hz）
     * @param thresholdHz 配置的切换阈值（Hz，已弃用，保留签名兼容）
     */
    public static SolveMode selectMode(double frequencyHz, double thresholdHz) {
        // 完全禁用时域（2026-08-14 + 2026-08-21 确认）：DC / 低频也走相量（伪时域）
        return SolveMode.COMPLEX_AC;
    }

    /** 按网络频率标签 + 阈值选择求解器实例（float 优先，能力不足自动回退 double） */
    public static Solver selectSolver(Network net, double thresholdHz) {
        return Solvers.create(selectMode(net.frequency, thresholdHz), net);
    }

    /** 用默认阈值选择求解器实例 */
    public static Solver selectSolver(Network net) {
        return selectSolver(net, DEFAULT_FREQUENCY_THRESHOLD_HZ);
    }

    /** 模式说明（调试/日志用） */
    public static String describe(Network net, double thresholdHz) {
        return "f=" + net.frequency + "Hz → " + selectMode(net.frequency, thresholdHz)
                + " (伪时域：统一相量"
                + (net.frequency > 0 ? "，AC 频率标签相量" : "，DC 等效小频率")
                + ")";
    }
}
