package com.hdf.cryptand.circuitsimulation.solver;

/**
 * float 稠密复求解——native-only（OpenBLAS LAPACK cgesv）。
 *
 * 2026-08-12 用户要求删除 float 自研：float 是可选精度，native float 不可用时
 * 由 {@link Solvers} 工厂自动回退 double 求解器（不调用本类）。
 */
public final class DenseFloatComplexLU {

    private DenseFloatComplexLU() {
    }

    public static FloatComplex[] solve(FloatComplex[][] a, FloatComplex[] b) {
        int n = b.length;
        if (!NativeDense.isLoaded() || !NativeDense.isSingleLoaded()) {
            throw new IllegalStateException("Native LAPACK float unavailable");
        }
        float[] aRe = new float[n * n], aIm = new float[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                aRe[i * n + j] = a[i][j].re;
                aIm[i * n + j] = a[i][j].im;
            }
        }
        float[] bRe = new float[n], bIm = new float[n];
        for (int i = 0; i < n; i++) {
            bRe[i] = b[i].re;
            bIm[i] = b[i].im;
        }
        int[] ipiv = new int[n];
        int info = NativeDense.denseSolveComplexFloat(n, aRe, aIm, bRe, bIm, ipiv);
        if (info != 0) {
            throw new IllegalStateException("Singular matrix, LAPACK info=" + info);
        }
        FloatComplex[] x = new FloatComplex[n];
        for (int i = 0; i < n; i++) x[i] = new FloatComplex(bRe[i], bIm[i]);
        return x;
    }
}
