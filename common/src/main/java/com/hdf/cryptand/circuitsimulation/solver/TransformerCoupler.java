package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.TransformerPrimaryHalf;
import com.hdf.cryptand.circuitsimulation.model.composite.TransformerSecondaryHalf;

/**
 * 变压器耦合协调器（2026-08-13，互感双半模型）。
 * <p>
 * 原边/副边是两个【独立网表】（各自 Network、各自求解器、各自缓存），互感
 * 通过半模型同步函数 {@link TransformerPrimaryHalf#syncWith()} /
 * {@link TransformerSecondaryHalf#syncWith()} 交换值——任意一边调用等价、
 * 线程安全（共享锁）。
 * <p>
 * 本协调器做【Gauss-Seidel 串行迭代】（收敛更快、无并行竞态），可运行在
 * 变压器线程/协调线程（纯数据计算，无 Level 访问）。迭代流程：
 * <pre>
 *   do {
 *     解原边 → primary.recordPrimary → primary.syncWith()   （V1→副边受控源）
 *     解副边 → secondary.recordSecondary → secondary.syncWith()（I2→原边受控源）
 *     收敛判断：平滑 V1 复数差（相对值）< ε
 *     振荡检测：delta 增大 → 自动降 ω
 *   } while (!收敛 && it < MAX_ITER);
 * </pre>
 * 不收敛（返回 null）→ 调用方回退【合并求解】（保留 IdealTransformerModel 整模型）。
 */
public final class TransformerCoupler {

    /** 收敛判据（相对值：|ΔV1| / |V1| < ε） */
    public static final double EPS = 1e-6;
    /** 迭代上限（超限 → 不收敛 → 回退合并求解） */
    public static final int MAX_ITER = 40;
    /** 初始松弛因子 */
    public static final double OMEGA_INIT = 0.6;
    /** 松弛下限（振荡自适应降到此处为止） */
    public static final double OMEGA_MIN = 0.2;

    private final TransformerPrimaryHalf primary;
    private final TransformerSecondaryHalf secondary;
    private final Network primaryNet;
    private final Network secondaryNet;

    /** 诊断：每轮打印 V1/I2/delta/omega（自测/调试用） */
    public volatile boolean verbose;

    // ===== 收敛状态（协调线程写 / 主线程读，volatile） =====
    private volatile boolean converged;
    private volatile int iterations;
    private volatile double lastDelta;
    private volatile double omegaUsed = OMEGA_INIT;
    private volatile SolveResult lastPrimaryResult;
    private volatile SolveResult lastSecondaryResult;

    public TransformerCoupler(TransformerPrimaryHalf primary, TransformerSecondaryHalf secondary,
                              Network primaryNet, Network secondaryNet) {
        this.primary = primary;
        this.secondary = secondary;
        this.primaryNet = primaryNet;
        this.secondaryNet = secondaryNet;
        // 建立耦合（互持引用 + 共享同步锁）——幂等
        primary.link(secondary);
    }

    /** 是否收敛（最近一次 solve 结果） */
    public boolean converged() { return converged; }

    /** 迭代次数（最近一次 solve） */
    public int iterations() { return iterations; }

    /** 最后相对误差（最近一次 solve） */
    public double lastDelta() { return lastDelta; }

    /** 使用的松弛因子（最近一次 solve 结束值） */
    public double omegaUsed() { return omegaUsed; }

    public SolveResult lastPrimaryResult() { return lastPrimaryResult; }
    public SolveResult lastSecondaryResult() { return lastSecondaryResult; }

    public TransformerPrimaryHalf primary() { return primary; }
    public TransformerSecondaryHalf secondary() { return secondary; }

    /**
     * 迭代求解原边+副边两个独立网表（Gauss-Seidel 串行）。
     * <p>线程安全：本方法可运行在变压器线程（纯数据计算）；内部通过半模型
     * 共享锁同步值交换，不受外部并发调用影响（多协调器实例各自独立）。
     *
     * @return [原边结果, 副边结果]；任一子网求解失败或不收敛 → null（回退合并）
     */
    public SolveResult[] solve() {
        converged = false;
        iterations = 0;
        lastDelta = Double.MAX_VALUE;
        double w = OMEGA_INIT;
        primary.omega = w;
        // 求解器按各自网络频率选择（float 优先自动回退 double）
        Solver ps = Solvers.create(primaryNet);
        Solver ss = Solvers.create(secondaryNet);
        SolveResult r1 = null, r2 = null;
        double prevV1Re = 0, prevV1Im = 0, prevI2Re = 0, prevI2Im = 0;
        double prevDelta = Double.MAX_VALUE;
        for (int it = 0; it < MAX_ITER; it++) {
            // ① 解原边（受控源用上轮 I1r）
            r1 = ps.solve(primaryNet);
            if (r1 == null || !r1.converged) return null;
            primary.recordPrimary(r1);
            // ② 原边 → 副边（同步函数：任意一边调用等价；锁内写 V2c + I1r）
            primary.syncWith();
            // ③ 解副边（受控源用新 V2c）
            r2 = ss.solve(secondaryNet);
            if (r2 == null || !r2.converged) return null;
            secondary.recordSecondary(r2, angularFrequency(primaryNet));
            // ④ 副边 → 原边（同步函数：写 I1r + 刷新 V2c）
            secondary.syncWith();
            // ⑤ 收敛判断：同时监控 V1（原边）与 I2（副边）的复数差（相对值）
            Complex v1 = primary.v1();   // 平滑后
            Complex i2 = secondary.i2();
            double v1abs = v1.abs();
            double i2abs = i2.abs();
            double dV = Math.hypot(v1.re - prevV1Re, v1.im - prevV1Im)
                    / (v1abs > 1e-12 ? v1abs : 1.0);
            double dI = Math.hypot(i2.re - prevI2Re, i2.im - prevI2Im)
                    / (i2abs > 1e-12 ? i2abs : 1.0);
            double delta = Math.max(dV, dI);
            prevV1Re = v1.re; prevV1Im = v1.im;
            prevI2Re = i2.re; prevI2Im = i2.im;
            lastDelta = delta;
            iterations = it + 1;
            // 自适应松弛：振荡（delta 不降反升）→ 降 ω
            if (it > 1 && delta > prevDelta * 1.2) {
                w = Math.max(w * 0.7, OMEGA_MIN);
                primary.omega = w;
            }
            prevDelta = delta;
            omegaUsed = w;
            if (verbose) {
                Complex vb = complexAt(r2, secondary.b1);
                Complex vy = complexAt(r2, secondary.y);
                com.hdf.cryptand.circuitsimulation.model.elements.ControlledVoltageSource cv =
                        secondary.voltageSource();
                System.out.printf("  iter=%2d V1=(%8.4f,%8.4f) w=%5.2f vsrc=(%8.4f,%8.4f) V(b1)=(%8.4f,%8.4f) V(y)=(%8.4f,%8.4f) I2=(%8.4f,%8.4f) dV=%.3e dI=%.3e%n",
                        it + 1, v1.re, v1.im, primary.omega, cv.re, cv.im,
                        vb.re, vb.im, vy.re, vy.im, i2.re, i2.im, dV, dI);
            }
            if (delta < EPS) {
                converged = true;
                break;
            }
        }
        lastPrimaryResult = r1;
        lastSecondaryResult = r2;
        return converged ? new SolveResult[]{r1, r2} : null;
    }

    private static double angularFrequency(Network net) {
        return net == null ? 0 : 2 * Math.PI * Math.max(net.frequency, 1e-6);
    }

    private static Complex complexAt(SolveResult res, int id) {
        if (res == null || id < 0) return Complex.ZERO;
        if (res.complex != null && id < res.complex.length && res.complex[id] != null) {
            return res.complex[id];
        }
        if (res.voltages != null && id < res.voltages.length) {
            return new Complex(res.voltages[id], 0);
        }
        return Complex.ZERO;
    }
}
