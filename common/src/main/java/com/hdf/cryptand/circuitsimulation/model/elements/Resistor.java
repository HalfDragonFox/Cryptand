package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplex;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/** 线性电阻。DC/AC 相同（纯实数导纳）。 */
public class Resistor extends AbstractElement {
    /** 电阻值（volatile：求解线程读 / 主线程刷新；参数变化经 setter 发消息） */
    public volatile double resistance;

    /** 孤立标记（2026-08-21 孤立电容的 ESR 也开路——不参与求解，防源直驱
     *  ESR 到悬空端 → 巨大电流 → 发热爆炸） */
    public volatile boolean openCircuit;

    public Resistor(int a, int b, double resistance) {
        super(a, b);
        this.resistance = Math.max(resistance, 1e-9);
    }

    /** 设置孤立标记（孤立设备内部电阻 → 开路不参与求解） */
    public void setOpenCircuit(boolean v) { openCircuit = v; }

    /** 更新电阻值：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setResistance(double r) {
        double nr = Math.max(r, 1e-9);
        if (Double.compare(nr, resistance) != 0) {
            resistance = nr;
            notifyParamChanged();
        }
    }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        if (openCircuit) return; // 孤立：开路不参与求解
        double g = 1.0 / resistance;
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        if (openCircuit) return; // 孤立：开路不参与求解
        double g = 1.0 / resistance;
        Complex cg = new Complex(g, 0);
        m.addY(nodeA, nodeA, cg);
        m.addY(nodeB, nodeB, cg);
        m.addY(nodeA, nodeB, cg.neg());
        m.addY(nodeB, nodeA, cg.neg());
    }

    // ===== float 求解器（魔法数字 double 计算后转 float） =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        if (openCircuit) return; // 孤立：开路不参与求解
        float g = (float) (1.0 / resistance);
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        if (openCircuit) return; // 孤立：开路不参与求解
        float g = (float) (1.0 / resistance);
        m.addY(nodeA, nodeA, new FloatComplex(g, 0));
        m.addY(nodeB, nodeB, new FloatComplex(g, 0));
        m.addY(nodeA, nodeB, new FloatComplex(-g, 0));
        m.addY(nodeB, nodeA, new FloatComplex(-g, 0));
    }

    @Override
    public ElementType type() { return ElementType.RESISTOR; }

    @Override
    public double[] params() { return new double[]{resistance}; }
}
