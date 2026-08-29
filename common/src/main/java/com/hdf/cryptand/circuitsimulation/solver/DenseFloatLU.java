package com.hdf.cryptand.circuitsimulation.solver;

/**
 * float 稠密求解——native-only（OpenBLAS LAPACK sgesv）。
 *
 * 2026-08-12 用户要求删除 float 自研：float 是可选精度，native float 不可用时
 * 由 {@link Solvers} 工厂自动回退 double 求解器（不调用本类）。本类被调用时
 * native float 必须可用，否则抛异常（明确失败而非静默错解）。
 * double 稠密自研（DenseRealLU/DenseComplexLU）保留作 native 失败最终兜底。
 */
public final class DenseFloatLU {

    private DenseFloatLU() {
    }

    public static float[] solve(float[][] a, float[] b) {
        int n = b.length;
        if (!NativeDense.isLoaded() || !NativeDense.isSingleLoaded()) {
            throw new IllegalStateException("Native LAPACK float unavailable");
        }
        float[] aFlat = new float[n * n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, aFlat, i * n, n);
        float[] x = new float[n];
        System.arraycopy(b, 0, x, 0, n);
        int[] ipiv = new int[n];
        int info = NativeDense.denseSolveRealFloat(n, aFlat, x, ipiv);
        if (info != 0) {
            throw new IllegalStateException("Singular matrix, LAPACK info=" + info);
        }
        return x;
    }
}
