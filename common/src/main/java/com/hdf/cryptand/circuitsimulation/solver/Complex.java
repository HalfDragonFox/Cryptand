package com.hdf.cryptand.circuitsimulation.solver;

/** 不可变复数（相量域用）。 */
public final class Complex {
    public final double re;
    public final double im;

    public Complex(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public static final Complex ZERO = new Complex(0, 0);

    public Complex add(Complex o) { return new Complex(re + o.re, im + o.im); }
    public Complex sub(Complex o) { return new Complex(re - o.re, im - o.im); }
    public Complex mul(Complex o) {
        return new Complex(re * o.re - im * o.im, re * o.im + im * o.re);
    }
    public Complex scale(double s) { return new Complex(re * s, im * s); }
    public Complex neg() { return new Complex(-re, -im); }

    /** 复数除法 a/b（SPICE 风格：乘以共轭再除模平方） */
    public Complex div(Complex o) {
        double d = o.re * o.re + o.im * o.im;
        return new Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
    }

    public double abs() { return Math.hypot(re, im); }

    /** 极坐标 → 直角坐标，angle 为弧度 */
    public static Complex fromPolar(double magnitude, double angleRad) {
        return new Complex(magnitude * Math.cos(angleRad), magnitude * Math.sin(angleRad));
    }

    @Override
    public String toString() {
        return "(" + re + (im < 0 ? "-" : "+") + Math.abs(im) + "i)";
    }
}
