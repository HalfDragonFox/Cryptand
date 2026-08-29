package com.hdf.cryptand.circuitsimulation.solver;

/**
 * float 版不可变复数（相量域，float 求解器用）。
 * <p>
 * float 求解器（FloatComplexMnaSolver）专用——求解热路径用 float 提升
 * 内存带宽/缓存/SIMD 收益；魔法数字等恒定参数仍用 double 计算后转 float
 * （见 Element.stampComplexFloat）。double 求解器仍用 {@link Complex}。
 */
public final class FloatComplex {
    public final float re;
    public final float im;

    public FloatComplex(float re, float im) {
        this.re = re;
        this.im = im;
    }

    public static final FloatComplex ZERO = new FloatComplex(0, 0);

    public FloatComplex add(FloatComplex o) {
        return new FloatComplex(re + o.re, im + o.im);
    }

    public FloatComplex sub(FloatComplex o) {
        return new FloatComplex(re - o.re, im - o.im);
    }

    public FloatComplex mul(FloatComplex o) {
        return new FloatComplex(re * o.re - im * o.im, re * o.im + im * o.re);
    }

    /** 复数除法（SPICE 风格：乘以共轭再除模平方） */
    public FloatComplex div(FloatComplex o) {
        float d = o.re * o.re + o.im * o.im;
        return new FloatComplex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
    }

    public float abs() {
        return (float) Math.hypot(re, im);
    }

    /** 转 double（显示/回写用） */
    public Complex toDouble() {
        return new Complex(re, im);
    }

    /** 从 double 转 float（魔法数字/恒定参数入矩阵前） */
    public static FloatComplex fromDouble(double re, double im) {
        return new FloatComplex((float) re, (float) im);
    }

    @Override
    public String toString() {
        return "(" + re + (im < 0 ? "-" : "+") + Math.abs(im) + "i)";
    }
}
