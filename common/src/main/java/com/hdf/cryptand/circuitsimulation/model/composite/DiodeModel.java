package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.DiodeElement;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 二极管复合模型（2026-08-12 纳入本架构）。
 * 组合 {@link DiodeElement}（非线性 PN 结分段线性）+ 温度（结温 → Vth 修正）。
 * 两端口（a=阳极、b=阴极），无控制端。
 */
public class DiodeModel extends SemiconductorModel {

    private final DiodeElement diode;

    public DiodeModel(int a, int b, double vth, double rForward, double rReverse,
                      ThermalModel thermal) {
        super(new Element[]{new DiodeElement(a, b, vth, rForward, rReverse)},
                thermal, a, b);
        this.diode = (DiodeElement) decompose()[0];
    }

    /** 默认硅二极管 */
    public DiodeModel(int a, int b, ThermalModel thermal) {
        this(a, b, 0.7, 0.05, 1_000_000.0, thermal);
    }

    /** 导通损耗（正向 I²·RF，峰值→平均 /2）；截止 0 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        boolean fwd = diode.vPrev > diode.vthAtTemp(diode.temperatureCelsius);
        if (!fwd) return 0;
        double v = va.sub(vb).abs();
        double i = v / diode.rForward;
        return i * i * diode.rForward / 2.0;
    }

    @Override
    public String toString() {
        return "DiodeModel{" + nodeA + "-" + nodeB + " Vth=" + diode.vth + "}";
    }
}
