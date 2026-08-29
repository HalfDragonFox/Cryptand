package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.WaveformGroup;

import java.util.Map;

/**
 * 求解结果。
 * REAL_DC:  voltages[] 为瞬时电压，complex == null
 * COMPLEX_AC: voltages[] 存幅值（|V|），complex 存完整相量（实部/虚部）
 * <p>多频叠加（2026-08-13 PLC 核心）：voltages[] 存【合成 RMS】= √(Σ|V_k|²/2)
 * （驱动设备/万用表）；complex[] 存【主导频率】相量（兼容现有设备逻辑）；
 * toneVoltages 存每频率相量组（频谱/PLC 解调用）；waveforms 存网络波形组。
 */
public final class SolveResult {
    public final double[] voltages;
    public final boolean converged;
    public final int iterations;
    public final long solveNanos;
    public final SolveMode mode;
    /** 仅 COMPLEX_AC 模式非空：主导频率完整相量 */
    public final Complex[] complex;
    /** 多频叠加：每频率 → 节点相量数组（可 null） */
    public final Map<Double, Complex[]> toneVoltages;
    /** 多频叠加：网络波形组（可 null） */
    public final WaveformGroup waveforms;
    /** 求解来源网表的结构指纹（2026-08-24 防线：调用方校验 res 与 ctx.network 同源；0=未知） */
    public volatile long networkHash;

    public SolveResult(double[] voltages, boolean converged, int iterations, long solveNanos, SolveMode mode) {
        this(voltages, null, null, null, converged, iterations, solveNanos, mode);
    }

    public SolveResult(double[] voltages, Complex[] complex, boolean converged,
                       int iterations, long solveNanos, SolveMode mode) {
        this(voltages, complex, null, null, converged, iterations, solveNanos, mode);
    }

    /** 多频叠加完整构造 */
    public SolveResult(double[] voltages, Complex[] complex, Map<Double, Complex[]> toneVoltages,
                       WaveformGroup waveforms, boolean converged,
                       int iterations, long solveNanos, SolveMode mode) {
        this.voltages = voltages;
        this.complex = complex;
        this.toneVoltages = toneVoltages;
        this.waveforms = waveforms;
        this.converged = converged;
        this.iterations = iterations;
        this.solveNanos = solveNanos;
        this.mode = mode;
    }

    public double voltageAt(Node n) {
        return voltages[n.id];
    }

    /** COMPLEX_AC 模式下返回完整相量；REAL_DC 模式返回 null */
    public Complex voltageAtComplex(Node n) {
        return complex == null ? null : complex[n.id];
    }

    @Override
    public String toString() {
        return "SolveResult{mode=" + mode + ", converged=" + converged
                + ", iters=" + iterations + ", " + solveNanos / 1000 + "µs"
                + (toneVoltages != null ? ", tones=" + toneVoltages.size() : "") + "}";
    }
}
