package com.hdf.cryptand.circuitsimulation.solver;

/**
 * OpenBLAS LAPACK 稠密 LU（dgesv/zgesv/sgesv/cgesv）JNI 入口。
 *
 * 供小网络稠密求解使用（比自研三重循环快 5~20x，SIMD/AVX2）。
 * A 为 n×n 行主序展平（a[i*n+j]），b 为 n 右端；求解后 b 被覆盖为解，
 * ipiv[n] 为主元置换。native 方法由 neoforge 侧加载 DLL 后可用：
 *   - DLL 缺失/加载失败 → {@link #isLoaded()} 返回 false，调用方回退自研
 *     Java 稠密实现。
 *   - 单精度（sgesv/cgesv）需 native 编译含单精度（isSingleLoaded）。
 */
public final class NativeDense {

    private static volatile boolean loaded;

    private NativeDense() {
    }

    /** 稠密实数求解（LAPACK dgesv，double）。返回 LAPACK info（0 成功）。 */
    public static native int denseSolveReal(int n, double[] a, double[] b, int[] ipiv);

    /** 稠密复求解（LAPACK zgesv，double complex）。返回 LAPACK info（0 成功）。 */
    public static native int denseSolveComplex(int n, double[] aRe, double[] aIm,
                                               double[] bRe, double[] bIm, int[] ipiv);

    /** 稠密实数求解（LAPACK sgesv，float）。返回 LAPACK info（0 成功）。 */
    public static native int denseSolveRealFloat(int n, float[] a, float[] b, int[] ipiv);

    /** 稠密复求解（LAPACK cgesv，float complex）。返回 LAPACK info（0 成功）。 */
    public static native int denseSolveComplexFloat(int n, float[] aRe, float[] aIm,
                                                    float[] bRe, float[] bIm, int[] ipiv);

    /** 由 neoforge 侧加载 DLL 成功后调用。 */
    public static void setLoaded(boolean v) {
        loaded = v;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** 单精度（sgesv/cgesv）native 支持标记。 */
    private static volatile boolean singleLoaded;

    public static void setSingleLoaded(boolean v) {
        singleLoaded = v;
    }

    public static boolean isSingleLoaded() {
        return singleLoaded;
    }
}

