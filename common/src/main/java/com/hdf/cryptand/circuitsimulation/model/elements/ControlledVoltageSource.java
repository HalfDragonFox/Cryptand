package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplex;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 受控电压源（互感双半模型迭代耦合用，2026-08-13）。
 * <p>
 * 本 MNA 框架是纯节点导纳形式（无电流变量行）→ 电压源用【诺顿等效】：
 * V 串内阻 R → 电流源 V/R 并联电导 1/R。内阻 R 取小值（接近理想），
 * 保证 (a,b) 间压降 ≈ 设定值。值由【同步函数】外部写入（volatile 线程安全）。
 * AC 相量模式存复数（re/im）；DC/时域用实部。
 */
public class ControlledVoltageSource extends AbstractElement {

    @Override public boolean isSource() { return true; }

    /** 相量值（volatile：同步线程写 / 求解线程 stamp 读） */
    public volatile double re;
    public volatile double im;

    /** 诺顿内阻（Ω，小值接近理想电压源；构造固定，防奇异） */
    public final double seriesResistance;

    public ControlledVoltageSource(int a, int b, double seriesResistance) {
        super(a, b);
        this.seriesResistance = Math.max(seriesResistance, 1e-6);
        this.re = 0;
        this.im = 0;
    }

    /** 同步函数写入（线程安全：volatile 原子写） */
    public void setValue(Complex v) {
        this.re = v.re;
        this.im = v.im;
    }

    /** 同步函数写入（线程安全） */
    public void setValue(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public Complex value() { return new Complex(re, im); }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        double g = 1.0 / seriesResistance;
        double i = re * g; // 诺顿电流源，方向 a→b
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        double g = 1.0 / seriesResistance;
        Complex i = new Complex(re * g, im * g);
        m.addY(nodeA, nodeA, new Complex(g, 0));
        m.addY(nodeB, nodeB, new Complex(g, 0));
        m.addY(nodeA, nodeB, new Complex(-g, 0));
        m.addY(nodeB, nodeA, new Complex(-g, 0));
        m.addB(nodeA, i);
        m.addB(nodeB, i.neg());
    }

    // ===== float 求解器 =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        float g = (float) (1.0 / seriesResistance);
        float i = (float) (re * g);
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
        m.addY(nodeA, nodeA, new FloatComplex(g, 0));
        m.addY(nodeB, nodeB, new FloatComplex(g, 0));
        m.addY(nodeA, nodeB, new FloatComplex(-g, 0));
        m.addY(nodeB, nodeA, new FloatComplex(-g, 0));
        m.addB(nodeA, new FloatComplex((float) (re * g), (float) (im * g)));
        m.addB(nodeB, new FloatComplex((float) (-re * g), (float) (-im * g)));
    }

    @Override
    public ElementType type() { return ElementType.CONTROLLED_VOLTAGE_SOURCE; }

    @Override
    public double[] params() { return new double[]{re, im, seriesResistance, nodeA, nodeB}; }

    @Override
    public String toString() {
        return "ControlledVoltageSource{" + nodeA + "->" + nodeB
                + " V=(" + re + (im >= 0 ? "+" : "") + im + "j) R=" + seriesResistance + "}";
    }
}
