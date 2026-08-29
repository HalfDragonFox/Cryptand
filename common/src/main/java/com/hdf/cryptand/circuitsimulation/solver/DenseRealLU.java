package com.hdf.cryptand.circuitsimulation.solver;

/** 稠密实矩阵 LU 分解（部分主元）。参考实现，后续可换稀疏/原生。 */
public final class DenseRealLU {

    public static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        // OpenBLAS LAPACK dgesv 优先（SIMD，快数倍）；失败回退自研
        if (NativeDense.isLoaded()) {
            try {
                double[] aFlat = new double[n * n];
                for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, aFlat, i * n, n);
                double[] x = new double[n];
                System.arraycopy(b, 0, x, 0, n);
                int[] ipiv = new int[n];
                if (NativeDense.denseSolveReal(n, aFlat, x, ipiv) == 0) return x;
            } catch (Throwable ignored) {
            }
        }
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, m[i], 0, n);
        double[] x = new double[n];
        System.arraycopy(b, 0, x, 0, n);

        for (int k = 0; k < n; k++) {
            // 部分主元
            int p = k;
            double max = Math.abs(m[k][k]);
            for (int i = k + 1; i < n; i++) {
                double v = Math.abs(m[i][k]);
                if (v > max) { max = v; p = i; }
            }
            if (max < 1e-15) throw new IllegalStateException("Singular matrix at row " + k);
            if (p != k) {
                double[] t = m[p]; m[p] = m[k]; m[k] = t;
                double tv = x[p]; x[p] = x[k]; x[k] = tv;
            }
            // 消元
            for (int i = k + 1; i < n; i++) {
                double f = m[i][k] / m[k][k];
                m[i][k] = 0;
                for (int j = k + 1; j < n; j++) m[i][j] -= f * m[k][j];
                x[i] -= f * x[k];
            }
        }
        // 回代
        for (int i = n - 1; i >= 0; i--) {
            double s = x[i];
            for (int j = i + 1; j < n; j++) s -= m[i][j] * x[j];
            x[i] = s / m[i][i];
        }
        return x;
    }
}
