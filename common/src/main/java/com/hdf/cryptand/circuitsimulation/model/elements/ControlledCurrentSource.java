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
 * 受控电流源（互感双半模型迭代耦合用，2026-08-13）。
 * <p>
 * 值由【同步函数】外部写入（{@link #setValue}），求解线程 stamp 时读取——
 * volatile 字段保证多线程可见性（无锁：值写入原子、stamp 读最新值）。
 * AC 相量模式存复数（re/im）；DC/时域用实部。
 * 方向 a→b：电流为正则从 a 流向 b。
 */
public class ControlledCurrentSource extends AbstractElement {

    @Override public boolean isSource() { return true; }

    /** 相量值（volatile：同步线程写 / 求解线程 stamp 读） */
    public volatile double re;
    public volatile double im;

    public ControlledCurrentSource(int a, int b) {
        super(a, b);
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
        double i = re;
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        m.addB(nodeA, new Complex(re, im));
        m.addB(nodeB, new Complex(-re, -im));
    }

    // ===== float 求解器 =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        m.addB(nodeA, (float) re);
        m.addB(nodeB, (float) -re);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        m.addB(nodeA, new FloatComplex((float) re, (float) im));
        m.addB(nodeB, new FloatComplex((float) -re, (float) -im));
    }

    @Override
    public ElementType type() { return ElementType.CONTROLLED_CURRENT_SOURCE; }

    @Override
    public double[] params() { return new double[]{re, im, nodeA, nodeB}; }

    @Override
    public String toString() {
        return "ControlledCurrentSource{" + nodeA + "->" + nodeB
                + " I=(" + re + (im >= 0 ? "+" : "") + im + "j)}";
    }
}
