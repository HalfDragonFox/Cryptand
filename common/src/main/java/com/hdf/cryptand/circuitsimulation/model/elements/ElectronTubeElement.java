package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 电子管（2026-08-12 纳入本架构：PowerGrid ElectronTubeWire 等效）。
 * <p>
 * 三端子：a = Anode(阳极)、b = Cathode(阴极)、c = Grid(栅极)。
 * 真空管特性（非线性，时间相关状态迭代判定）：
 *   - 截止：栅压 Vg &le; Vcutoff → 阳极-阴极开路（大电阻，无板流）
 *   - 导通：Vg &gt; Vcutoff → 阳极受控电流源 Ia = gm·(Vg - Vcutoff)
 *     （跨导 gm = μ/Rp，μ=放大系数、Rp=内阻）
 * <p>
 * 【时间参数】stampRealAt(m, dt, t)：用上一轮栅压（vControlPrev）判工作区。
 */
public class ElectronTubeElement extends SemiconductorElement {

    /** 放大系数 μ（魔法数字） */
    public final double mu;
    /** 阳极内阻 Rp（Ω） */
    public final double rp;
    /** 截止栅压（V，负值 = 负偏截止） */
    public final double vCutoff;
    /** 截止时阳极漏电阻（Ω） */
    public final double rAOff;

    public ElectronTubeElement(int anode, int cathode, int grid,
                               double mu, double rp, double vCutoff, double rAOff) {
        super(anode, cathode, grid);
        this.mu = mu;
        this.rp = Math.max(rp, 1e-3);
        this.vCutoff = vCutoff;
        this.rAOff = Math.max(rAOff, 1e6);
    }

    /** 默认三极电子管（μ=20，Rp=10kΩ，截止 -1V） */
    public ElectronTubeElement(int a, int b, int c) {
        this(a, b, c, 20.0, 10_000.0, -1.0, 10_000_000.0);
    }

    @Override
    public void stampRealAt(MnaBuilder m, double dt, double t) {
        this.t = t;
        double vg = vControlPrev; // 时间相关状态：上一轮栅-阴极电压
        boolean on = vg > vCutoff;
        if (!on) {
            // ===== 截止：阳极-阴极开路（漏电阻）=====
            double g = 1.0 / rAOff;
            m.addG(a, a, g); m.addG(b, b, g);
            m.addG(a, b, -g); m.addG(b, a, -g);
            // 栅极-阴极：高阻
            double gg = 1.0 / rAOff;
            m.addG(c, c, gg); m.addG(b, b, gg);
            m.addG(c, b, -gg); m.addG(b, c, -gg);
            return;
        }
        // ===== 导通：阳极受控电流源 Ia = (Vg - Vcutoff)·μ/Rp = gm·(Vg-Vcutoff) =====
        double gm = mu / rp;
        double ia = gm * (vg - vCutoff);
        // 阳极-阴极小导纳（内阻）
        double gA = 1.0 / rp;
        m.addG(a, a, gA); m.addG(b, b, gA);
        m.addG(a, b, -gA); m.addG(b, a, -gA);
        m.addB(a, ia);
        m.addB(b, -ia);
        // 栅极-阴极：高阻（栅极几乎无电流）
        double gg = 1.0 / rAOff;
        m.addG(c, c, gg); m.addG(b, b, gg);
        m.addG(c, b, -gg); m.addG(b, c, -gg);
    }

    @Override
    public String toString() {
        return "ElectronTube{Anode=" + a + " Cathode=" + b + " Grid=" + c
                + " μ=" + mu + " Rp=" + rp + "}";
    }
}
