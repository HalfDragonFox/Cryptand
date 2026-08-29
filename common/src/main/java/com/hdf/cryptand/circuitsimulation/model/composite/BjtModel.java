package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.BjtElement;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 三极管复合模型（2026-08-12 纳入本架构：PowerGrid BJT NPN/PNP）。
 * 组合 {@link BjtElement}（三端子 C/B/E，三区非线性）+ 温度（结温 → Vth 修正）。
 * 端口：nodeA=Collector、nodeB=Emitter；控制端=Base（{@link #setControlVoltage}）。
 */
public class BjtModel extends SemiconductorModel {

    private final BjtElement bjt;

    public BjtModel(int collector, int emitter, int base, boolean pnp,
                    double beta, ThermalModel thermal) {
        super(new Element[]{new BjtElement(collector, emitter, base, pnp,
                beta, 0.7, 1000.0, 0.2, 1.0, 1_000_000.0)},
                thermal, collector, emitter);
        this.bjt = (BjtElement) decompose()[0];
    }

    /** 默认 NPN（β=100） */
    public BjtModel(int c, int e, int b, ThermalModel thermal) {
        this(c, e, b, false, 100.0, thermal);
    }

    /** 设置基极-发射极电压（Vbe，三端子工作区判定） */
    @Override
    public void setControlVoltage(double vbe) {
        bjt.vControlPrev = vbe;
    }

    /** 导通损耗：放大区 Ic²·Rce + 饱和区 Vce·I 近似 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        double vthT = bjt.vthAtTemp(bjt.vth);
        boolean active = bjt.pnp ? (bjt.vControlPrev < -vthT) : (bjt.vControlPrev > vthT);
        if (!active) return 0;
        double vce = va.sub(vb).abs();
        double ib = vthT / bjt.rbe;
        double ic = bjt.beta * Math.abs(ib);
        // 饱和时压降 VceSat；放大时按 Ic·Vce 近似（受控源功耗）
        return ic * Math.max(vce, bjt.vceSat) / 2.0;
    }

    @Override
    public String toString() {
        return "BjtModel{" + (bjt.pnp ? "PNP" : "NPN") + " C=" + nodeA
                + " E=" + nodeB + " B=" + bjt.c + " β=" + bjt.beta + "}";
    }
}
