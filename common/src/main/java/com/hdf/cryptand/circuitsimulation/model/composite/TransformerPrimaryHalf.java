package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.ControlledCurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

/**
 * 变压器【原边半模型】（互感双半模型，2026-08-13）。
 * <p>
 * 与原边网络同一网表（独立于副边网表）。拓扑：
 * <pre>
 *   pa1 --[rCp 铜阻]-- a1 --[Llp 漏感]-- x --[Lm 磁化]-- pa2
 *                        └──[rCore 铁损]──┘（并联 x-pa2）
 *   受控电流源 (x, pa2)：I1r = -I2·ratio（副边电流折算，由同步函数写入）
 * </pre>
 * 原边理想变电压 V1 = V(x) - V(pa2)——求解后由 {@link #recordPrimary} 记录，
 * 同步函数 {@link #syncWith()} 把它折算给副边受控电压源，同时读回副边电流
 * I2 折算写入本侧受控电流源。同步函数【任意一边调用等价】且【线程安全】
 * （两半共享同一把锁，无死锁；受控源值 volatile）。
 */
public class TransformerPrimaryHalf extends CompositeElement {

    /** 端口端子节点（引擎 id） */
    public final int pa1, pa2;
    /** 内部节点：a1=铜阻后、x=漏感后（理想变原边） */
    public final int a1, x;
    /** 绕组参数（固定魔法数字） */
    public final double rCp, rCore, llp, lm, l1, m;
    /** 匝比 n = 副边/原边（V2 = V1·n，I1 = -I2·n） */
    public final double ratio;

    /** 受控电流源（本侧耦合执行器） */
    private final ControlledCurrentSource src;
    /** 另一半引用 + 共享同步锁（link 时建立，线程安全） */
    private TransformerSecondaryHalf secondary;
    private final Object syncLock;

    /** 最近记录的 V1（原边理想变电压，volatile：同步函数跨线程读） */
    private volatile double v1Re, v1Im;
    /** 松弛因子（0~1，振荡时协调器调低；volatile 线程安全） */
    public volatile double omega = 0.6;
    /** 上一轮平滑 V1（同步函数内松弛用；锁内访问，无需 volatile） */
    private double v1PrevRe, v1PrevIm;

    /**
     * @param pa1/pa2 原边端子节点
     * @param x       漏感后内部节点（理想变原边）
     * @param a1      铜阻后内部节点
     * @param rCp     原边铜阻 Ω
     * @param rCore   铁损电阻 Ω
     * @param l1      原边自感 H（L1）
     * @param m       互感 H
     * @param ratio   匝比 n = 副边/原边
     */
    public TransformerPrimaryHalf(int pa1, int pa2, int a1, int x,
                                  double rCp, double rCore, double l1, double m, double ratio) {
        super(build(pa1, pa2, a1, x, rCp, rCore, l1, m, ratio));
        this.pa1 = pa1; this.pa2 = pa2; this.a1 = a1; this.x = x;
        this.rCp = rCp; this.rCore = rCore;
        this.l1 = l1; this.m = m; this.ratio = ratio;
        this.llp = Math.max(l1 - m / ratio, l1 * 1e-6); // 原边漏感
        this.lm = m / ratio;                            // 磁化电感
        this.syncLock = new Object();
        // 从 decompose 中找回受控电流源（build 里创建，这里再定位）
        ControlledCurrentSource found = null;
        for (com.hdf.cryptand.circuitsimulation.model.Element e : decompose()) {
            if (e instanceof ControlledCurrentSource cs) { found = cs; break; }
        }
        this.src = found == null ? new ControlledCurrentSource(x, pa2) : found;
    }

    private static com.hdf.cryptand.circuitsimulation.model.Element[] build(
            int pa1, int pa2, int a1, int x,
            double rCp, double rCore, double l1, double m, double ratio) {
        double llp = Math.max(l1 - m / ratio, l1 * 1e-6);
        double lm = m / ratio;
        return new com.hdf.cryptand.circuitsimulation.model.Element[]{
                new Resistor(pa1, a1, Math.max(rCp, 1e-9)),   // 原边铜阻
                new Inductor(a1, x, llp),                     // 原边漏感
                new Inductor(x, pa2, lm),                     // 磁化电感
                new Resistor(x, pa2, Math.max(rCore, 1e-6)),  // 铁损
                new ControlledCurrentSource(x, pa2)           // 受控电流源（I1r）
        };
    }

    /** 与副边半建立耦合（互相持引用 + 共享同步锁）——必须调用一次。
     *  ⚠ 必须【双向】设置：只设本侧 secondary 会导致副边半 primary==null →
     *  secondary.syncWith 里把受控源清 0（2026-08-13 实测发散根因）。 */
    public void link(TransformerSecondaryHalf sec) {
        this.secondary = sec;
        sec.linkFrom(this); // 设置反向引用（副边半的 primary）
        sec.adoptLock(this.syncLock); // 共享同一把锁（两半 syncWith 用同一锁 → 无死锁）
    }

    /** 求解后记录原边理想变电压 V1 = V(x) - V(pa2)（volatile 原子写） */
    public void recordPrimary(SolveResult res) {
        Complex vx = complexAt(res, x);
        Complex vp = complexAt(res, pa2);
        this.v1Re = vx.re - vp.re;
        this.v1Im = vx.im - vp.im;
    }

    /** 同步函数【对称双向交换】——任意一边调用等价（共享锁内完成两侧写入）。
     *  调用场景：
     *    - 变压器线程/协调器串行迭代：解原边后调本方法
     *    - 两侧求解线程各自调用（Jacobi 并行）：每侧解完调一次
     *  线程安全：共享锁（无死锁）+ volatile 字段。
     *  <p>【松弛平滑】：V1 是主动变量（振荡源），写入副边受控源前做
     *  V1_used = V1_prev + ω·(V1_new - V1_prev)，平滑后的值也存回 v1()
     *  （协调器收敛判断用平滑值）。ω 由协调器自适应调整（振荡 → 降低）。 */
    public void syncWith() {
        synchronized (syncLock) {
            // 松弛平滑 V1
            double sv1Re = v1PrevRe + omega * (v1Re - v1PrevRe);
            double sv1Im = v1PrevIm + omega * (v1Im - v1PrevIm);
            v1PrevRe = sv1Re;
            v1PrevIm = sv1Im;
            v1Re = sv1Re;
            v1Im = sv1Im;
            Complex i2 = secondary == null ? Complex.ZERO : secondary.i2(); // 对方最近 I2
            if (secondary != null) {
                // V2c = V1·n（写对方受控电压源）
                secondary.voltageSource().setValue(sv1Re * ratio, sv1Im * ratio);
            }
            // I1r = -I2·n（写本侧受控电流源）
            src.setValue(-i2.re * ratio, -i2.im * ratio);
        }
    }

    /** 最近记录的副边电流 I2（由对方半模型持有；本方法从引用读取） */
    Complex i2() {
        return secondary == null ? Complex.ZERO : secondary.i2();
    }

    /** 最近记录的原边理想变电压 V1（协调器收敛判断用） */
    public Complex v1() { return new Complex(v1Re, v1Im); }

    /** 受控电流源（协调器可读当前注入值） */
    public ControlledCurrentSource source() { return src; }

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
        this.v1Re = this.v1Im = 0;
        this.v1PrevRe = this.v1PrevIm = 0;
        src.setValue(0, 0);
    }

    @Override
    public ElementType type() { return ElementType.COMPOSITE; }

    @Override
    public String toString() {
        return "TransformerPrimaryHalf{" + pa1 + "-" + pa2 + " L1=" + l1 + " M=" + m
                + " n=" + ratio + " V1=(" + v1Re + (v1Im >= 0 ? "+" : "") + v1Im + "j)}";
    }
}
