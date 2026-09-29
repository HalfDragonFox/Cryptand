package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.NonlinearPhasorElement;

/**
 * 分段线性化相量求解器（2026-08-15 用户需求）。
 *
 * 非线性元件（二极管/三极管/压控电阻等）在相量域用【工作点线性化】建模：
 * 在每个迭代步，把非线性元件替换为
 *   I = I0(V_k) + Y(V_k)·V          （Y = dI/dV 小信号导纳，I0 = 切线补偿源）
 * 然后用线性相量 MNA 求解，得到新电压 V_{k+1}；更新工作点后迭代
 * （牛顿-拉夫森），直到 |V_{k+1} - V_k| < tol 收敛。
 *
 * 只求【基波】相量（不含谐波）。纯线性元件照常 stampComplex。
 * 相比伪时域：不推进能量状态，直接在相量域求稳态工作点——非线性直接相量计算。
 */
public class PiecewiseLinearSolver implements Solver {

    public static final double GMIN = 1e-7;
    private static final int MAX_ITER = 80;
    private static final double TOL = 1e-7;

    @Override
    public SolveMode mode() {
        return SolveMode.COMPLEX_AC;
    }

    @Override
    public SolveResult solve(Network net) {
        long t0 = System.nanoTime();
        if (net == null) return new SolveResult(new double[0], false, 0, 0, SolveMode.COMPLEX_AC);
        int n = net.nodeCount();
        int g = net.groundNode;
        double omega = 2 * Math.PI * Math.max(net.frequency, 1e-4);

        // 初始工作点 V=0（或上次解；求解器无记忆 → 0）
        Complex[] v = new Complex[n];
        for (int i = 0; i < n; i++) v[i] = Complex.ZERO;

        int iter = 0;
        boolean converged = false;
        for (iter = 0; iter < MAX_ITER; iter++) {
            ComplexMnaBuilder m = new ComplexMnaBuilder(n);
            for (Element e : net.elements()) {
                if (e instanceof NonlinearPhasorElement nle) {
                    stampNonlinear(m, nle, e, v, omega);
                } else {
                    try { e.stampComplex(m, omega); } catch (Throwable ignored) { }
                }
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
            Complex[] vn = ComplexMnaSolver.solveMatrix(m);
            if (vn == null || vn.length < n) break;
            double d = 0;
            for (int i = 0; i < n; i++) {
                double dd = vn[i].sub(v[i]).abs();
                if (dd > d) d = dd;
            }
            // ⚠ 2026-08-30 审计 M7：发散保护——工作点振荡/爆炸时 d 巨大，
            // 继续迭代只污染 v；超物理上限 → 终止标记未收敛。
            if (d > 1e12) {
                converged = false;
                break;
            }
            v = vn;
            if (d < TOL) {
                converged = true;
                break;
            }
        }

        double[] mag = new double[n];
        for (int i = 0; i < n; i++) mag[i] = v[i].abs() / Math.sqrt(2.0); // RMS
        long nanos = System.nanoTime() - t0;
        SolveResult res = new SolveResult(mag, v, converged, iter, nanos, SolveMode.COMPLEX_AC);
        try {
            TerminalRecorder.record(net, res);
        } catch (Throwable ignored) {
        }
        return res;
    }

    /** 非线性元件：stamp 工作点导纳 Y + 补偿源 I0（I = I0 + Y·V，正方向 a→b） */
    private static void stampNonlinear(ComplexMnaBuilder m, NonlinearPhasorElement nle,
                                       Element e, Complex[] v, double omega) {
        int a = e.nodeA(), b = e.nodeB();
        if (a < 0 || b < 0 || a >= v.length || b >= v.length) return;
        Complex vab = v[a].sub(v[b]);
        Complex y = nle.equivalentAdmittance(vab, omega);
        Complex i0 = nle.equivalentCurrent(vab, omega);
        m.addY(a, a, y);
        m.addY(b, b, y);
        m.addY(a, b, y.neg());
        m.addY(b, a, y.neg());
        // 补偿源 I0 从 a→b 注入：a 流出、b 流入
        m.addB(a, i0.neg());
        m.addB(b, i0);
    }
}
