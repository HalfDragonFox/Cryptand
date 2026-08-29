package com.hdf.cryptand.circuitsimulation.solver;

/** 实数 MNA 装配器（时域/直流）。dense 实现，后续可换稀疏。 */
public final class MnaBuilder {
    public final double[][] g;
    public final double[] b;
    public final int size;

    public MnaBuilder(int size) {
        this.size = size;
        this.g = new double[size][size];
        this.b = new double[size];
    }

    public void addG(int r, int c, double v) { g[r][c] += v; }
    public void addB(int r, double v) { b[r] += v; }
}
