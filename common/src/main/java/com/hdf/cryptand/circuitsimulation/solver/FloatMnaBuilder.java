package com.hdf.cryptand.circuitsimulation.solver;

/**
 * float 版实数 MNA 装配器（DC/时域，float 求解器用）。
 * <p>
 * 求解热路径用 float（内存减半 + 缓存友好 + 潜在 SIMD）；魔法数字/恒定
 * 参数在 {@code Element.stampRealFloat} 里由 double 计算后转 float。
 */
public final class FloatMnaBuilder {

    public final float[][] g;
    public final float[] b;
    public final int size;

    public FloatMnaBuilder(int size) {
        this.size = size;
        this.g = new float[size][size];
        this.b = new float[size];
    }

    public void addG(int r, int c, float v) {
        g[r][c] += v;
    }

    public void addG(int r, int c, double v) {
        g[r][c] += (float) v;
    }

    public void addB(int r, float v) {
        b[r] += v;
    }

    public void addB(int r, double v) {
        b[r] += (float) v;
    }
}
