package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.NonlinearPhasorElement;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态相量法（Dynamic Phasor Method）相量求解器（2026-08-15 用户需求）。
 *
 * 传统相量假设幅值/相位恒定；动态相量把相量 V(t) 视为【时间的慢变包络】，
 * 元件方程在相量域含包络导数项 dV/dt：
 * <pre>
 *   电容 : I = C·jω·V + C·(dV/dt)   →  Y = C(jω + 1/dt)，源 = C/dt·V_old
 *   电感 : V = (jωL + L/dt)·I - L/dt·I_old
 *          →  Y = 1/(jωL + L/dt)，源 = Y·(L/dt·I_old)
 *   非线性: I = I0(V) + Y(V)·V（当前包络工作点线性化）
 * </pre>
 * 每步（固定节拍 dt）用显式欧拉推进包络：dV/dt ≈ (V_new - V_old)/dt，
 * 装配含包络导数项的线性矩阵 → 求解 → 更新包络历史。包络慢变 → 大步长
 * 稳定（相比全时域无需 ns 级步长），同时保留 L/C 的瞬态响应——这就是
 * "相量 + 时间推进"，替代伪时域。
 *
 * 状态（包络历史）按 Network 持久（ConcurrentHashMap）；首个 solve 无状态
 * 时退化为纯相量稳态。
 */
public class DynamicPhasorSolver implements Solver {

    public static final double GMIN = 1e-7;

    /** 每网络动态相量状态（包络历史） */
    private static final ConcurrentHashMap<Network, State> STATES = new ConcurrentHashMap<>();

    /** 动态相量状态：节点包络电压历史 + 电感电流包络历史 */
    static final class State {
        Complex[] vOld;
        Map<Element, Complex> iOld = new IdentityHashMap<>();
    }

    @Override
    public SolveMode mode() {
        return SolveMode.COMPLEX_AC;
    }

    /** 清空全部动态相量状态（世界切换/网络重建清理） */
    public static void clearStates() {
        STATES.clear();
    }

    @Override
    public SolveResult solve(Network net) {
        long t0 = System.nanoTime();
        if (net == null) return new SolveResult(new double[0], false, 0, 0, SolveMode.COMPLEX_AC);
        int n = net.nodeCount();
        int g = net.groundNode;
        double omega = 2 * Math.PI * Math.max(net.frequency, 1e-4);
        double dt = Math.max(net.dt, 1e-3);

        State st = STATES.computeIfAbsent(net, k -> new State());
        if (st.vOld == null || st.vOld.length != n) {
            st.vOld = new Complex[n];
            for (int i = 0; i < n; i++) st.vOld[i] = Complex.ZERO;
        }

        ComplexMnaBuilder m = new ComplexMnaBuilder(n);
        for (Element e : net.elements()) {
            stampElement(m, e, st, omega, dt);
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
        if (vn == null || vn.length < n) vn = st.vOld;

        // 更新状态
        Complex[] vNew = new Complex[n];
        for (int i = 0; i < n; i++) vNew[i] = vn[i].abs() < 1e15 ? vn[i] : Complex.ZERO;
        st.vOld = vNew;
        // 电感电流包络更新：I_new = Y·(V_ab) + Y·(L/dt·I_old)（从装配反推）
        for (Element e : net.elements()) {
            if (!(e instanceof Inductor ind)) continue;
            int a = e.nodeA(), b = e.nodeB();
            double L = ind.inductance;
            Complex z = new Complex(L / dt, omega * L);
            Complex y = new Complex(1, 0).div(z);
            Complex iOld = st.iOld.get(e);
            if (iOld == null) iOld = Complex.ZERO;
            Complex vab = vNew[a].sub(vNew[b]);
            Complex iNew = vab.mul(y).add(iOld.scale(L / dt).mul(y));
            st.iOld.put(e, iNew);
        }

        double[] mag = new double[n];
        for (int i = 0; i < n; i++) mag[i] = vNew[i].abs() / Math.sqrt(2.0); // RMS
        long nanos = System.nanoTime() - t0;
        SolveResult res = new SolveResult(mag, vNew, true, 1, nanos, SolveMode.COMPLEX_AC);
        try {
            TerminalRecorder.record(net, res);
        } catch (Throwable ignored) {
        }
        return res;
    }

    /** 装配单个元件（含 L/C 包络导数项 + 非线性工作点） */
    private static void stampElement(ComplexMnaBuilder m, Element e, State st,
                                     double omega, double dt) {
        int a = e.nodeA(), b = e.nodeB();
        if (a < 0 || b < 0) return;
        try {
            if (e instanceof Capacitor cap) {
                // I = C(jω + 1/dt)·V_new - C/dt·V_old
                double C = cap.capacitance;
                Complex y = new Complex(C / dt, omega * C);
                Complex hist = st.vOld[a].sub(st.vOld[b]).scale(C / dt);
                m.addY(a, a, y);
                m.addY(b, b, y);
                m.addY(a, b, y.neg());
                m.addY(b, a, y.neg());
                m.addB(a, hist);
                m.addB(b, hist.neg());
                return;
            }
            if (e instanceof Inductor ind) {
                // Y = 1/(jωL + L/dt)，源 = Y·(L/dt·I_old)
                double L = ind.inductance;
                Complex z = new Complex(L / dt, omega * L);
                Complex y = new Complex(1, 0).div(z);
                Complex iOld = st.iOld.get(e);
                if (iOld == null) iOld = Complex.ZERO;
                Complex hist = iOld.scale(L / dt).mul(y);
                m.addY(a, a, y);
                m.addY(b, b, y);
                m.addY(a, b, y.neg());
                m.addY(b, a, y.neg());
                m.addB(a, hist.neg());
                m.addB(b, hist);
                return;
            }
            if (e instanceof NonlinearPhasorElement nle) {
                // 当前包络工作点线性化：I = I0(V_old) + Y(V_old)·V_new
                Complex vab = st.vOld[a].sub(st.vOld[b]);
                Complex y = nle.equivalentAdmittance(vab, omega);
                Complex i0 = nle.equivalentCurrent(vab, omega);
                m.addY(a, a, y);
                m.addY(b, b, y);
                m.addY(a, b, y.neg());
                m.addY(b, a, y.neg());
                m.addB(a, i0.neg());
                m.addB(b, i0);
                return;
            }
            e.stampComplex(m, omega);
        } catch (Throwable ignored) {
        }
    }
}
