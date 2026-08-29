package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.NonlinearPhasorElement;

/**
 * 谐波平衡法（Harmonic Balance, HB）相量求解器（2026-08-15 用户需求）。
 *
 * 把节点电压展开为基波 + 谐波的相量组 V_h（h=0..H），对非线性元件用
 * 【时域采样 → DFT】得到各谐波电流分量，迭代平衡：
 * <pre>
 *   Y_h · V_h = S_h - I_nl_h(V)      （每个谐波 h 一个线性 MNA 系统）
 *   I_nl_h    = DFT[ i_nl( v_ab(t) ) ]（非线性电流谐波，全谐波重构时域后 DFT）
 * </pre>
 * 线性元件每谐波独立装配（stampComplex(h·ω)）；非线性元件在右侧作为电流源。
 * 欠松弛迭代（V ← V + α(V' - V)）提升收敛稳定性。
 *
 * 结果：voltages[] = 全谐波合成 RMS；complex[] = 基波相量（兼容现有设备）；
 * 相比分段线性化：能捕获谐波畸变（整流/倍频），真正"非线性相量计算"。
 */
public class HarmonicBalanceSolver implements Solver {

    public static final double GMIN = 1e-7;
    private static final int MAX_ITER = 120;
    private static final double TOL = 1e-6;
    /** 欠松弛系数（收敛慢时调小） */
    private static final double RELAX = 0.6;

    /** 谐波阶数（含基波；可配置） */
    public static int harmonics = 5;

    @Override
    public SolveMode mode() {
        return SolveMode.COMPLEX_AC;
    }

    @Override
    public SolveResult solve(Network net) {
        long t0 = System.nanoTime();
        if (net == null) return new SolveResult(new double[0], false, 0, 0, SolveMode.COMPLEX_AC);
        int H = Math.max(1, harmonics);
        int n = net.nodeCount();
        int g = net.groundNode;
        double omega = 2 * Math.PI * Math.max(net.frequency, 1e-4);
        int samples = 2 * H + 1; // 采样点数（Nyquist）

        // 谐波电压 V[h][i]
        Complex[][] V = new Complex[H + 1][n];
        for (int h = 0; h <= H; h++) {
            for (int i = 0; i < n; i++) V[h][i] = Complex.ZERO;
        }
        // 初始：基波线性解（非线性暂忽略，作初值加速收敛）
        Complex[] base = solveLinear(net, omega, g);
        if (base != null && base.length >= n) {
            for (int i = 0; i < n; i++) V[1][i] = base[i];
        }

        int iter = 0;
        boolean converged = false;
        for (iter = 0; iter < MAX_ITER; iter++) {
            Complex[][] Vn = new Complex[H + 1][n];
            double maxDelta = 0;
            for (int h = 0; h <= H; h++) {
                ComplexMnaBuilder m = new ComplexMnaBuilder(n);
                double omegaH = h * omega;
                for (Element e : net.elements()) {
                    if (e instanceof NonlinearPhasorElement) continue; // 非线性走右侧源
                    try { e.stampComplex(m, omegaH); } catch (Throwable ignored) { }
                }
                if (g >= 0 && g < n) {
                    m.clearRowCol(g);
                    m.addY(g, g, new Complex(1, 0));
                    m.b[g] = Complex.ZERO;
                }
                for (int i = 0; i < n; i++) {
                    if (i == g) continue;
                    m.addY(i, i, new Complex(GMIN, 0));
                }
                // 非线性电流谐波 → 右侧源
                for (Element e : net.elements()) {
                    if (!(e instanceof NonlinearPhasorElement nle)) continue;
                    int a = e.nodeA(), b = e.nodeB();
                    if (a < 0 || b < 0) continue;
                    Complex iH = nonlinearHarmonic(nle, a, b, V, h, omega, samples);
                    if (iH == null) continue;
                    m.addB(a, iH.neg()); // 电流从 a→b 注入
                    m.addB(b, iH);
                }
                Complex[] vh = ComplexMnaSolver.solveMatrix(m);
                if (vh == null || vh.length < n) {
                    for (int i = 0; i < n; i++) Vn[h][i] = V[h][i];
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    // 欠松弛
                    Complex target = vh[i];
                    Vn[h][i] = V[h][i].add(target.sub(V[h][i]).scale(RELAX));
                    maxDelta = Math.max(maxDelta, Vn[h][i].sub(V[h][i]).abs());
                }
            }
            for (int h = 0; h <= H; h++) V[h] = Vn[h];
            if (maxDelta < TOL) {
                converged = true;
                break;
            }
        }

        double[] rms = PhasorNonlinearity.harmonicRms(V, n);
        long nanos = System.nanoTime() - t0;
        SolveResult res = new SolveResult(rms, V[1], null, null, converged, iter, nanos,
                SolveMode.COMPLEX_AC);
        try {
            TerminalRecorder.record(net, res);
        } catch (Throwable ignored) {
        }
        return res;
    }

    /** 非线性元件在 h 谐波下的电流相量（全谐波重构时域 → 元件电流 → DFT） */
    private static Complex nonlinearHarmonic(NonlinearPhasorElement nle, int a, int b,
                                             Complex[][] V, int h, double omega, int samples) {
        double[] vs = new double[samples];
        for (int m = 0; m < samples; m++) {
            double va = 0, vb = 0;
            for (int k = 0; k < V.length; k++) {
                if (V[k][a] == null || V[k][b] == null) continue;
                double th = k * omega * PhasorNonlinearity.sampleTime(omega, m, samples);
                va += V[k][a].re * Math.cos(th) - V[k][a].im * Math.sin(th);
                vb += V[k][b].re * Math.cos(th) - V[k][b].im * Math.sin(th);
            }
            vs[m] = va - vb;
        }
        double[] is;
        try {
            is = nle.timeDomainCurrent(vs, omega);
        } catch (Throwable ignored) {
            return Complex.ZERO;
        }
        Complex[] spec = PhasorNonlinearity.dft(is, V.length - 1, omega);
        return (h >= 0 && h < spec.length) ? spec[h] : Complex.ZERO;
    }

    /** 纯线性基波求解（初始值；非线性元件忽略） */
    private static Complex[] solveLinear(Network net, double omega, int g) {
        int n = net.nodeCount();
        ComplexMnaBuilder m = new ComplexMnaBuilder(n);
        for (Element e : net.elements()) {
            if (e instanceof NonlinearPhasorElement) continue;
            try { e.stampComplex(m, omega); } catch (Throwable ignored) { }
        }
        if (g >= 0 && g < n) {
            m.clearRowCol(g);
            m.addY(g, g, new Complex(1, 0));
            m.b[g] = Complex.ZERO;
        }
        for (int i = 0; i < n; i++) {
            if (i == g) continue;
            m.addY(i, i, new Complex(GMIN, 0));
        }
        return ComplexMnaSolver.solveMatrix(m);
    }
}
