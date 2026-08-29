package com.hdf.cryptand.circuitsimulation.solver;

/** 稠密复矩阵 LU 分解（部分主元，按模长）。参考实现。 */
public final class DenseComplexLU {

    public static Complex[] solve(Complex[][] a, Complex[] b) {
        int n = b.length;
        // OpenBLAS LAPACK zgesv 优先（SIMD）；失败回退自研
        if (NativeDense.isLoaded()) {
            try {
                double[] aRe = new double[n * n], aIm = new double[n * n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        aRe[i * n + j] = a[i][j].re;
                        aIm[i * n + j] = a[i][j].im;
                    }
                }
                double[] bRe = new double[n], bIm = new double[n];
                for (int i = 0; i < n; i++) {
                    bRe[i] = b[i].re;
                    bIm[i] = b[i].im;
                }
                int[] ipiv = new int[n];
                if (NativeDense.denseSolveComplex(n, aRe, aIm, bRe, bIm, ipiv) == 0) {
                    Complex[] x = new Complex[n];
                    for (int i = 0; i < n; i++) x[i] = new Complex(bRe[i], bIm[i]);
                    return x;
                }
            } catch (Throwable ignored) {
            }
        }
        Complex[][] m = new Complex[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, m[i], 0, n);
        Complex[] x = new Complex[n];
        System.arraycopy(b, 0, x, 0, n);

        for (int k = 0; k < n; k++) {
            int p = k;
            double max = m[k][k].abs();
            for (int i = k + 1; i < n; i++) {
                double v = m[i][k].abs();
                if (v > max) { max = v; p = i; }
            }
            if (max < 1e-15) throw new IllegalStateException("Singular matrix at row " + k);
            if (p != k) {
                Complex[] t = m[p]; m[p] = m[k]; m[k] = t;
                Complex tv = x[p]; x[p] = x[k]; x[k] = tv;
            }
            for (int i = k + 1; i < n; i++) {
                Complex f = div(m[i][k], m[k][k]);
                m[i][k] = Complex.ZERO;
                for (int j = k + 1; j < n; j++) m[i][j] = m[i][j].sub(f.mul(m[k][j]));
                x[i] = x[i].sub(f.mul(x[k]));
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            Complex s = x[i];
            for (int j = i + 1; j < n; j++) s = s.sub(m[i][j].mul(x[j]));
            x[i] = div(s, m[i][i]);
        }
        return x;
    }

    private static Complex div(Complex a, Complex b) {
        double d = b.re * b.re + b.im * b.im;
        return new Complex((a.re * b.re + a.im * b.im) / d,
                (a.im * b.re - a.re * b.im) / d);
    }
}
