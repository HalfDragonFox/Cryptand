package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 场效应管 VFET（2026-08-12 纳入本架构：PowerGrid VFETWire 等效）。
 * <p>
 * 三端子：a = Drain(漏极)、b = Source(源极)、c = Gate(栅极)。
 * 理想化两区模型（时间相关状态迭代判定）：
 *   - 截止：Vgs &le; Vth → D-S 开路（大电阻）
 *   - 导通：Vgs &gt; Vth → D-S 小电阻 Rds（欧姆区近似）
 * <p>
 * 【时间参数】stampRealAt(m, dt, t)：用上一轮 Vgs（vControlPrev）判工作区。
 */
public class VfetElement extends SemiconductorElement {

    /** 是否 N 沟道（true=N 沟道；false=P 沟道，默认 N） */
    public final boolean nChannel;
    /** 阈值电压（V，N 沟道典型 2V） */
    public final double vth;
    /** 导通电阻 Rds（Ω，欧姆区近似）——魔法数字 */
    public final double rdsOn;
    /** 截止漏电阻（Ω） */
    public final double rdsOff;

    public VfetElement(int drain, int source, int gate, boolean nChannel,
                       double vth, double rdsOn, double rdsOff) {
        super(drain, source, gate);
        this.nChannel = nChannel;
        this.vth = vth;
        this.rdsOn = Math.max(rdsOn, 1e-6);
        this.rdsOff = Math.max(rdsOff, 1e6);
    }

    /** 默认 N 沟道（Vth=2V，Rds=1Ω） */
    public VfetElement(int d, int s, int g) {
        this(d, s, g, true, 2.0, 1.0, 1_000_000.0);
    }

    @Override
    public void stampRealAt(MnaBuilder m, double dt, double t) {
        this.t = t;
        double vgs = vControlPrev; // 时间相关状态：上一轮栅-源电压
        boolean on = nChannel ? (vgs > vth) : (vgs < -vth);
        double r = on ? rdsOn : rdsOff;
        double g = 1.0 / r;
        m.addG(a, a, g);
        m.addG(b, b, g);
        m.addG(a, b, -g);
        m.addG(b, a, -g);
        // 栅极-源极：高阻（栅极几乎无电流）
        double ggs = 1.0 / rdsOff;
        m.addG(c, c, ggs);
        m.addG(b, b, ggs);
        m.addG(c, b, -ggs);
        m.addG(b, c, -ggs);
    }

    @Override
    public String toString() {
        return "VfetElement{" + (nChannel ? "N" : "P") + "FET D=" + a + " S=" + b
                + " G=" + c + " Vth=" + vth + "}";
    }
}
