package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 直流电压源（戴维南 → 诺顿等效）。
 * 用内阻串联，内部转成 电导 + 电流源 参与 MNA，避免理想源需要额外行。
 * params: [voltage, seriesResistance]
 */
public class DcVoltageSource extends AbstractElement {

    @Override public boolean isSource() { return true; }
    public final double voltage;
    public final double seriesResistance;

    /** 开路标志（2026-08-23 电压源开路：一端接下游而两端无闭合回路 → 不注入，
     *  避免诺顿等效经 GMin 假回路 → 假电流/温度爆炸；孤立源不标记保持开路电压） */
    private volatile boolean openCircuit;

    public void setOpenCircuit(boolean open) { this.openCircuit = open; }

    public boolean isOpenCircuit() { return openCircuit; }

    public DcVoltageSource(int a, int b, double voltage, double seriesResistance) {
        super(a, b);
        this.voltage = voltage;
        this.seriesResistance = Math.max(seriesResistance, 1e-9);
    }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        double g = 1.0 / seriesResistance;
        if (openCircuit) {
            m.addG(nodeA, nodeA, g);
            m.addG(nodeB, nodeB, g);
            m.addG(nodeA, nodeB, -g);
            m.addG(nodeB, nodeA, -g);
            return;
        }
        double i = voltage * g;               // 诺顿电流源，方向 a→b
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        // 直流电压源在 AC 相量域：电压恒定（无 AC 分量）→ 对交流等效为短路，
        // 只呈现内阻（Thevenin 内阻对 AC 是纯电阻）。电池接在 AC 网络里时
        // 作为内阻电阻参与分压（真实物理：理想 DC 源对 AC 是零阻抗）。
        double g = 1.0 / seriesResistance;
        m.addY(nodeA, nodeA, new Complex(g, 0));
        m.addY(nodeB, nodeB, new Complex(g, 0));
        m.addY(nodeA, nodeB, new Complex(-g, 0));
        m.addY(nodeB, nodeA, new Complex(-g, 0));
    }

    // ===== float 求解器 =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        float g = (float) (1.0 / seriesResistance);
        if (openCircuit) {
            m.addG(nodeA, nodeA, g);
            m.addG(nodeB, nodeB, g);
            m.addG(nodeA, nodeB, -g);
            m.addG(nodeB, nodeA, -g);
            return;
        }
        float i = (float) (voltage * g);
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        float g = (float) (1.0 / seriesResistance);
        m.addY(nodeA, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(g, 0));
        m.addY(nodeB, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(g, 0));
        m.addY(nodeA, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(-g, 0));
        m.addY(nodeB, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(-g, 0));
    }

    @Override
    public ElementType type() { return ElementType.DC_VOLTAGE_SOURCE; }

    @Override
    public double[] params() { return new double[]{voltage, seriesResistance}; }
}
