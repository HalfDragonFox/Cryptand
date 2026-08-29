package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.VfetElement;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 场效应管复合模型（2026-08-12 纳入本架构：PowerGrid VFET）。
 * 组合 {@link VfetElement}（三端子 D/S/G）+ 温度。
 * 端口：nodeA=Drain、nodeB=Source；控制端=Gate（{@link #setControlVoltage}）。
 */
public class VfetModel extends SemiconductorModel {

    private final VfetElement vfet;

    public VfetModel(int drain, int source, int gate, boolean nChannel,
                     double vth, ThermalModel thermal) {
        super(new Element[]{new VfetElement(drain, source, gate, nChannel,
                vth, 1.0, 1_000_000.0)},
                thermal, drain, source);
        this.vfet = (VfetElement) decompose()[0];
    }

    /** 默认 N 沟道（Vth=2V） */
    public VfetModel(int d, int s, int g, ThermalModel thermal) {
        this(d, s, g, true, 2.0, thermal);
    }

    /** 设置栅-源电压（Vgs，工作区判定） */
    @Override
    public void setControlVoltage(double vgs) {
        vfet.vControlPrev = vgs;
    }

    /** 导通损耗：Ids²·Rds（欧姆区）；截止 0 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        boolean on = vfet.nChannel ? (vfet.vControlPrev > vfet.vth)
                : (vfet.vControlPrev < -vfet.vth);
        if (!on) return 0;
        double v = va.sub(vb).abs();
        double i = v / vfet.rdsOn;
        return i * i * vfet.rdsOn / 2.0;
    }

    @Override
    public String toString() {
        return "VfetModel{" + (vfet.nChannel ? "N" : "P") + "FET D=" + nodeA
                + " S=" + nodeB + " G=" + vfet.c + " Vth=" + vfet.vth + "}";
    }
}
