package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.ElectronTubeElement;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 电子管复合模型（2026-08-12 纳入本架构：PowerGrid ElectronTube）。
 * 组合 {@link ElectronTubeElement}（三端子 A/C/G，真空管非线性）+ 温度。
 * 端口：nodeA=Anode、nodeB=Cathode；控制端=Grid（{@link #setControlVoltage}）。
 */
public class ElectronTubeModel extends SemiconductorModel {

    private final ElectronTubeElement tube;

    public ElectronTubeModel(int anode, int cathode, int grid,
                             double mu, double rp, double vCutoff, ThermalModel thermal) {
        super(new Element[]{new ElectronTubeElement(anode, cathode, grid,
                mu, rp, vCutoff, 10_000_000.0)},
                thermal, anode, cathode);
        this.tube = (ElectronTubeElement) decompose()[0];
    }

    /** 默认三极电子管（μ=20，Rp=10kΩ） */
    public ElectronTubeModel(int a, int b, int g, ThermalModel thermal) {
        this(a, b, g, 20.0, 10_000.0, -1.0, thermal);
    }

    /** 设置栅-阴极电压（Vg，工作区判定） */
    @Override
    public void setControlVoltage(double vg) {
        tube.vControlPrev = vg;
    }

    /** 导通损耗：阳极电流 Ia × 板压 Va 近似（受控源功耗） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        boolean on = tube.vControlPrev > tube.vCutoff;
        if (!on) return 0;
        double gm = tube.mu / tube.rp;
        double ia = gm * (tube.vControlPrev - tube.vCutoff);
        double vaPlate = va.sub(vb).abs();
        return ia * vaPlate / 2.0;
    }

    @Override
    public String toString() {
        return "ElectronTubeModel{A=" + nodeA + " C=" + nodeB + " G=" + tube.c
                + " μ=" + tube.mu + "}";
    }
}
