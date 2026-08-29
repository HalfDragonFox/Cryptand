package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.WaveformGroup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多频叠加求解器（2026-08-13 PLC 核心）。
 * <p>
 * 线性叠加定理：多频网络对每个频率分量【独立求解】（同一矩阵结构、不同
 * omega），总响应 = 各频率响应叠加：
 * <pre>
 *   v_k = solve(矩阵(ω_k))                  // 每频率一次 MNA（源按频率选择性注入）
 *   RMS(i) = √(Σ_k |v_k(i)|² / 2)          // 节点 i 合成 RMS（驱动设备/万用表）
 *   v(i, t) = Σ_k |v_k(i)|·sin(2πf_k t + φ_k)  // 任意时刻采样（波形记录）
 * </pre>
 * 频率来源：{@link Network#waveforms()}（显式波形组）。源按 {@link Element#activeAt}
 * 判断是否在本频率完整注入（工频源只注入主导频率、载波源只注入匹配频率）。
 * <p>
 * 结果：voltages[] = 合成 RMS（兼容现有设备/回写逻辑）；complex[] = 主导频率
 * 相量；toneVoltages = 每频率相量组（频谱/PLC 解调）；waveforms = 波形组。
 */
public class MultiToneSolver implements Solver {

    /** 防奇异 GMin（与 ComplexMnaSolver 一致） */
    private static final double GMIN = 1e-7;

    @Override
    public SolveMode mode() { return SolveMode.COMPLEX_AC; }

    @Override
    public SolveResult solve(Network net) {
        long t0 = System.nanoTime();
        WaveformGroup wg = net == null ? null : net.waveforms();
        if (wg == null || wg.count() == 0 || !wg.isMultiTone()) {
            // 非多频 → 回退单频相量求解
            return new ComplexMnaSolver().solve(net);
        }
        int n = net.nodeCount();
        int g = net.groundNode;
        double domF = wg.dominantFrequency();

        Map<Double, Complex[]> tones = new LinkedHashMap<>();
        double[] rms = new double[n];
        int solvedTones = 0;
        for (WaveformGroup.Component c : wg.components()) {
            double f = c.frequency;
            if (f <= 0) continue; // DC 偏置分量暂不独立求解（叠加直流另计）
            double omega = 2 * Math.PI * f;
            ComplexMnaBuilder m = new ComplexMnaBuilder(n);
            for (Element e : net.elements()) {
                if (e.activeAt(omega, domF)) e.stampComplex(m, omega);
                else e.stampComplexPassive(m, omega);
            }
            // 固定接地
            if (g >= 0 && g < n) {
                m.clearRowCol(g);
                m.addY(g, g, new Complex(1, 0));
                m.b[g] = Complex.ZERO;
            }
            // GMin 兜底
            for (int i = 0; i < n; i++) {
                if (i == g) continue;
                m.addY(i, i, new Complex(GMIN, 0));
            }
            Complex[] v = ComplexMnaSolver.solveMatrix(m);
            if (v == null || v.length < n) continue;
            tones.put(f, v);
            solvedTones++;
            for (int i = 0; i < n; i++) {
                double a = v[i].abs();
                rms[i] += a * a / 2.0;
            }
        }
        if (solvedTones == 0) {
            return new ComplexMnaSolver().solve(net); // 无有效频率 → 回退
        }
        for (int i = 0; i < n; i++) rms[i] = Math.sqrt(rms[i]);

        long nanos = System.nanoTime() - t0;
        // 主导频率相量（兼容现有设备逻辑：读 complex[id].abs()）
        Complex[] dom = tones.get(domF);
        SolveResult result = new SolveResult(rms, dom, tones, wg, true, solvedTones, nanos,
                SolveMode.COMPLEX_AC);
        // 端子测试点回填（2026-08-13 用户架构：求解后自动写端子电压，天然正确）
        TerminalRecorder.record(net, result);
        return result;
    }
}
