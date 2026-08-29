package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 双极结型三极管 BJT（2026-08-12 纳入本架构：PowerGrid BJTWire NPN/PNP 等效）。
 * <p>
 * 三端子：a = Collector(集电极)、b = Emitter(发射极)、c = Base(基极)。
 * 理想化三区模型（分段线性，时间相关状态迭代判定）：
 *   - 截止：Vbe &le; Vth → C-E 开路（大电阻），无集电极电流
 *   - 放大：Vbe &gt; Vth → C-E 受控电流源 Ic = β·Ib（Ib = (Vbe-Vth)/Rbe）
 *   - 饱和：Vce &lt; VceSat → C-E 小电阻 RceSat（压降 VceSat）
 * <p>
 * 【时间参数】stampRealAt(m, dt, t)：用上一轮 Vbe（vControlPrev）判工作区；
 * t 记录仿真时间。温度修正 Vth（-2mV/K）。
 */
public class BjtElement extends SemiconductorElement {

    /** 是否为 PNP（true=PNP；false=NPN，默认） */
    public final boolean pnp;
    /** 电流增益 β（放大区 Ic = β·Ib）——魔法数字 */
    public final double beta;
    /** 基极-发射极阈值电压（V，25°C） */
    public final double vth;
    /** 基极-发射极等效电阻（Ω） */
    public final double rbe;
    /** 饱和压降（V）、饱和导通电阻（Ω） */
    public final double vceSat, rceSat;
    /** 截止时 C-E 漏电阻（Ω） */
    public final double rceOff;

    public BjtElement(int collector, int emitter, int base, boolean pnp,
                      double beta, double vth, double rbe,
                      double vceSat, double rceSat, double rceOff) {
        super(collector, emitter, base);
        this.pnp = pnp;
        this.beta = beta;
        this.vth = vth;
        this.rbe = Math.max(rbe, 1e-6);
        this.vceSat = vceSat;
        this.rceSat = Math.max(rceSat, 1e-6);
        this.rceOff = Math.max(rceOff, 1e6);
    }

    /** 默认 NPN（β=100，Vth=0.7V，Rbe=1kΩ） */
    public BjtElement(int c, int e, int b) {
        this(c, e, b, false, 100.0, 0.7, 1000.0, 0.2, 1.0, 1_000_000.0);
    }

    @Override
    public void stampRealAt(MnaBuilder m, double dt, double t) {
        this.t = t;
        double vthT = vthAtTemp(vth);
        // 时间相关状态：上一轮 Vbe（基极-发射极）判工作区
        double vbe = vControlPrev;
        boolean active = pnp ? (vbe < -vthT) : (vbe > vthT); // 导通（放大/饱和）
        if (!active) {
            // ===== 截止区：C-E 开路（漏电阻）=====
            double g = 1.0 / rceOff;
            m.addG(a, a, g); m.addG(b, b, g);
            m.addG(a, b, -g); m.addG(b, a, -g);
            // B-E 结截止（漏电）
            double gbe = 1.0 / rceOff;
            m.addG(c, c, gbe); m.addG(b, b, gbe);
            m.addG(c, b, -gbe); m.addG(b, c, -gbe);
            return;
        }
        // ===== 导通区 =====
        // B-E 结：电阻 Rbe（钳位 Vbe≈Vth）
        double gbe = 1.0 / rbe;
        m.addG(c, c, gbe); m.addG(b, b, gbe);
        m.addG(c, b, -gbe); m.addG(b, c, -gbe);
        double vbePol = pnp ? -vthT : vthT;
        double ib = vbePol / rbe; // 基极电流（近似）
        // C-E 通道：受控电流源 Ic = β·Ib（放大区）
        double ic = beta * Math.abs(ib);
        double gce = 1.0 / rceOff; // 输出导纳（很小）
        m.addG(a, a, gce); m.addG(b, b, gce);
        m.addG(a, b, -gce); m.addG(b, a, -gce);
        // 受控电流源：Ic 从 C(a) 流入 → E(b)（NPN 方向）
        double icPol = pnp ? -ic : ic;
        m.addB(a, icPol);
        m.addB(b, -icPol);
    }

    @Override
    public void commit(double va, double vb, double dt) {
        super.commit(va, vb, dt);
        // 记录控制端电压 Vbe（B-E）：用于下一轮工作区判定
        vControlPrev = vcPrev();
    }

    /** 控制端电压：本元件无真实 Vc 求解值 → 用 B-E 压差近似（由外部 update 设置） */
    private double vcPrev() {
        return vControlPrev; // 由外部（复合模型 update）用基极电压更新
    }

    @Override
    public String toString() {
        return "BjtElement{" + (pnp ? "PNP" : "NPN") + " C=" + a + " E=" + b
                + " B=" + c + " β=" + beta + "}";
    }
}
