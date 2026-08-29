package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.ControlledVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

/**
 * 变压器【副边半模型】（互感双半模型，2026-08-13）。
 * <p>
 * 与副边网络同一网表（独立于原边网表）。拓扑：
 * <pre>
 *   pb1 --[rCs 铜阻]-- b1 --[Lls 漏感]-- y
 *   受控电压源 (y, pb2)：V2c = V1·ratio（原边电压折算；诺顿：V 串 Rv → 电流源 ∥ 电导）
 * </pre>
 * 副边电流 I2 = (V(b1) - V(y)) / (j·ω·Lls)（流过漏感 = 理想变副边电流）——
 * 求解后由 {@link #recordSecondary} 记录，同步函数 {@link #syncWith()} 把它
 * 折算写入原边受控电流源，同时读回原边电压折算本侧受控电压源。同步函数
 * 【任意一边调用等价】且【线程安全】（与原边半共享同一把锁，无死锁）。
 */
public class TransformerSecondaryHalf extends CompositeElement {

    /** 端口端子节点（引擎 id） */
    public final int pb1, pb2;
    /** 内部节点：b1=铜阻后、y=漏感后（理想变副边） */
    public final int b1, y;
    /** 绕组参数（固定魔法数字） */
    public final double rCs, lls, l2, m;
    /** 匝比 n = 副边/原边（V2 = V1·n，I1 = -I2·n） */
    public final double ratio;
    /** 受控电压源诺顿内阻（Ω，小值接近理想） */
    public final double sourceResistance;

    /** 受控电压源（本侧耦合执行器） */
    private final ControlledVoltageSource vsrc;
    /** 另一半引用 + 共享同步锁（link 时建立，线程安全） */
    private TransformerPrimaryHalf primary;
    private Object syncLock = new Object();

    /** 最近记录的 I2（副边电流，volatile：同步函数跨线程读） */
    private volatile double i2Re, i2Im;

    /**
     * @param pb1/pb2 副边端子节点
     * @param b1      铜阻后内部节点
     * @param y       漏感后内部节点（理想变副边）
     * @param rCs     副边铜阻 Ω
     * @param l2      副边自感 H（L2）
     * @param m       互感 H
     * @param ratio   匝比 n = 副边/原边
     * @param sourceResistance 受控电压源诺顿内阻（默认 0.01）
     */
    public TransformerSecondaryHalf(int pb1, int pb2, int b1, int y,
                                    double rCs, double l2, double m, double ratio,
                                    double sourceResistance) {
        super(build(pb1, pb2, b1, y, rCs, l2, m, ratio, sourceResistance));
        this.pb1 = pb1; this.pb2 = pb2; this.b1 = b1; this.y = y;
        this.rCs = rCs; this.l2 = l2; this.m = m; this.ratio = ratio;
        this.lls = Math.max(l2 - ratio * m, l2 * 1e-6); // 副边漏感
        this.sourceResistance = Math.max(sourceResistance, 1e-6);
        ControlledVoltageSource found = null;
        for (com.hdf.cryptand.circuitsimulation.model.Element e : decompose()) {
            if (e instanceof ControlledVoltageSource cv) { found = cv; break; }
        }
        this.vsrc = found == null ? new ControlledVoltageSource(y, pb2, this.sourceResistance) : found;
    }

    private static com.hdf.cryptand.circuitsimulation.model.Element[] build(
            int pb1, int pb2, int b1, int y,
            double rCs, double l2, double m, double ratio, double sourceResistance) {
        double lls = Math.max(l2 - ratio * m, l2 * 1e-6);
        return new com.hdf.cryptand.circuitsimulation.model.Element[]{
                new Resistor(pb1, b1, Math.max(rCs, 1e-9)),     // 副边铜阻
                new Inductor(b1, y, lls),                       // 副边漏感
                new ControlledVoltageSource(y, pb2, sourceResistance) // 受控电压源（V2c）
        };
    }

    /** 与原边半共享同步锁（link 时由原边半调用，保证两半同一把锁） */
    void adoptLock(Object lock) {
        this.syncLock = lock == null ? new Object() : lock;
    }

    /** 设置原边半引用（link 时由原边半调用——双向引用，保证同步函数完整生效） */
    void linkFrom(TransformerPrimaryHalf p) {
        this.primary = p;
    }

    /** 求解后记录副边电流 I2 = (V(b1)-V(y)) / (jωLls)（volatile 原子写） */
    public void recordSecondary(SolveResult res, double omega) {
        Complex vb = complexAt(res, b1);
        Complex vy = complexAt(res, y);
        double xl = omega * lls;
        double dvRe = vb.re - vy.re;
        double dvIm = vb.im - vy.im;
        if (xl <= 1e-12) { this.i2Re = this.i2Im = 0; return; }
        // I = (Vb - Vy) / (j·ωL) = -j·(Vb-Vy)/(ωL) → re = dvIm/xl, im = -dvRe/xl
        this.i2Re = dvIm / xl;
        this.i2Im = -dvRe / xl;
    }

    /** 同步函数【对称双向交换】——任意一边调用等价（共享锁内完成两侧写入）。
     *  调用场景：
     *    - 变压器线程/协调器串行迭代：解副边后调本方法
     *    - 两侧求解线程各自调用（Jacobi 并行）：每侧解完调一次
     *  线程安全：共享锁（无死锁）+ volatile 字段。 */
    public void syncWith() {
        synchronized (syncLock) {
            Complex v1 = primary == null ? Complex.ZERO : primary.v1(); // 对方最近 V1（已平滑）
            Complex i2 = new Complex(i2Re, i2Im);                       // 本侧最近 I2
            vsrc.setValue(v1.re * ratio, v1.im * ratio);                // V2c = V1·n（本侧受控电压源）
            if (primary != null) {
                // I1r = -I2·n（写对方受控电流源）
                primary.source().setValue(-i2.re * ratio, -i2.im * ratio);
            }
        }
    }

    /** 最近记录的原边电压 V1（由对方半模型持有；本方法从引用读取） */
    Complex v1() {
        return primary == null ? Complex.ZERO : primary.v1();
    }

    /** 最近记录的副边电流 I2（协调器收敛判断用） */
    public Complex i2() { return new Complex(i2Re, i2Im); }

    /** 受控电压源（协调器可读当前注入值） */
    public ControlledVoltageSource voltageSource() { return vsrc; }

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

    @Override
    public void reset() {
        super.reset();
        this.i2Re = this.i2Im = 0;
        vsrc.setValue(0, 0);
    }

    @Override
    public ElementType type() { return ElementType.COMPOSITE; }

    @Override
    public String toString() {
        return "TransformerSecondaryHalf{" + pb1 + "-" + pb2 + " L2=" + l2 + " M=" + m
                + " n=" + ratio + " I2=(" + i2Re + (i2Im >= 0 ? "+" : "") + i2Im + "j)}";
    }
}
