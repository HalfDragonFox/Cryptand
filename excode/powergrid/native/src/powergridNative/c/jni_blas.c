/**
 * jni_blas.c —— OpenBLAS BLAS Level 1/3 向量·矩阵算子 JNI 绑定
 *
 * 为 Cryptand 各仿真核心（电路 / 集成网络 / 热网络等）提供统一底层数学接口
 * 的 BLAS 部分（2026-08-30 用户：OpenBLAS+SuperLU 全部做成统一底层接口，
 * 各核心可发送计算；Java 侧无 DLL 时自动转 Java 实现，本文件仅提供 native
 * 加速路径）：
 *   - gemm   (d/s/zgemm)   矩阵乘法  C = α·A·B + β·C（本层固定 α=1、β=0）
 *   - axpy   (d/s/zaxpy)   向量更新  y = α·x + y
 *   - dot    (d/z)         内积（复数取共轭第一参数，ddot/zdotc）
 *   - nrm2   (dnrm2)       2-范数
 *
 * 约定：全部矩阵按【行主序】展平（a[i*lda+j]），与 LAPACKE 一致；
 * A 为 m×k（transA=false）或 k×m（transA=true），B 为 k×n（/ n×k），
 * C 输出 m×n。
 *
 * ⚠ 复数格式（2026-08-30 实测）：OpenBLAS cblas.h 中复数全部用
 *   openblas_complex_double（GCC 下即 C99 double _Complex）。本文件直接
 *   include "cblas.h"（其内部包含 common.h → openblas_config.h，GCC 下
 *   定义 _Complex 类型）。JNI 侧按 re/im 分离数组传递，内部构造
 *   double _Complex 数组再调 cblas。
 *
 * 线程安全：CBLAS 每次调用独立（无共享静态状态），可被多个核心线程并行调用。
 *
 * JNI 方法（类 com_hdf_cryptand_math_NativeMath）：
 *   dgemm / sgemm / zgemm
 *   daxpy / saxpy / zaxpy
 *   ddot  / zdotc
 *   dnrm2
 */

#include <jni.h>
#include <stdlib.h>
#include <complex.h>
#include "cblas.h"

/* ==================== gemm（矩阵乘法） ==================== */

static int gemm_common(int m, int n, int k) {
    return (m > 0 && n > 0 && k > 0) ? 1 : 0;
}

/* ---- double (dgemm) ---- */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDgemm(
        JNIEnv *env, jclass cls,
        jint m, jint n, jint k,
        jdoubleArray aJ, jdoubleArray bJ, jdoubleArray cJ,
        jboolean transA, jboolean transB) {
    if (!gemm_common(m, n, k)) return -1;
    jdouble *a = (*env)->GetDoubleArrayElements(env, aJ, NULL);
    jdouble *b = (*env)->GetDoubleArrayElements(env, bJ, NULL);
    jdouble *c = (*env)->GetDoubleArrayElements(env, cJ, NULL);
    if (!a || !b || !c) {
        if (a) (*env)->ReleaseDoubleArrayElements(env, aJ, a, JNI_ABORT);
        if (b) (*env)->ReleaseDoubleArrayElements(env, bJ, b, JNI_ABORT);
        if (c) (*env)->ReleaseDoubleArrayElements(env, cJ, c, JNI_ABORT);
        return -1;
    }
    int lda = transA ? k : m;
    int ldb = transB ? n : k;
    cblas_dgemm(CblasRowMajor,
                transA ? CblasTrans : CblasNoTrans,
                transB ? CblasTrans : CblasNoTrans,
                m, n, k, 1.0, a, lda, b, ldb, 0.0, c, n);
    (*env)->ReleaseDoubleArrayElements(env, aJ, a, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, bJ, b, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, cJ, c, 0);
    return 0;
}

/* ---- float (sgemm) ---- */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeSgemm(
        JNIEnv *env, jclass cls,
        jint m, jint n, jint k,
        jfloatArray aJ, jfloatArray bJ, jfloatArray cJ,
        jboolean transA, jboolean transB) {
    if (!gemm_common(m, n, k)) return -1;
    jfloat *a = (*env)->GetFloatArrayElements(env, aJ, NULL);
    jfloat *b = (*env)->GetFloatArrayElements(env, bJ, NULL);
    jfloat *c = (*env)->GetFloatArrayElements(env, cJ, NULL);
    if (!a || !b || !c) {
        if (a) (*env)->ReleaseFloatArrayElements(env, aJ, a, JNI_ABORT);
        if (b) (*env)->ReleaseFloatArrayElements(env, bJ, b, JNI_ABORT);
        if (c) (*env)->ReleaseFloatArrayElements(env, cJ, c, JNI_ABORT);
        return -1;
    }
    int lda = transA ? k : m;
    int ldb = transB ? n : k;
    cblas_sgemm(CblasRowMajor,
                transA ? CblasTrans : CblasNoTrans,
                transB ? CblasTrans : CblasNoTrans,
                m, n, k, 1.0f, a, lda, b, ldb, 0.0f, c, n);
    (*env)->ReleaseFloatArrayElements(env, aJ, a, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, bJ, b, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, cJ, c, 0);
    return 0;
}

/* ---- double complex (zgemm；re/im 分离数组 → C99 _Complex) ---- */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeZgemm(
        JNIEnv *env, jclass cls,
        jint m, jint n, jint k,
        jdoubleArray aReJ, jdoubleArray aImJ,
        jdoubleArray bReJ, jdoubleArray bImJ,
        jdoubleArray cReJ, jdoubleArray cImJ,
        jboolean transA, jboolean transB) {
    if (!gemm_common(m, n, k)) return -1;
    jdouble *aRe = (*env)->GetDoubleArrayElements(env, aReJ, NULL);
    jdouble *aIm = (*env)->GetDoubleArrayElements(env, aImJ, NULL);
    jdouble *bRe = (*env)->GetDoubleArrayElements(env, bReJ, NULL);
    jdouble *bIm = (*env)->GetDoubleArrayElements(env, bImJ, NULL);
    jdouble *cRe = (*env)->GetDoubleArrayElements(env, cReJ, NULL);
    jdouble *cIm = (*env)->GetDoubleArrayElements(env, cImJ, NULL);
    if (!aRe || !aIm || !bRe || !bIm || !cRe || !cIm) {
        if (aRe) (*env)->ReleaseDoubleArrayElements(env, aReJ, aRe, JNI_ABORT);
        if (aIm) (*env)->ReleaseDoubleArrayElements(env, aImJ, aIm, JNI_ABORT);
        if (bRe) (*env)->ReleaseDoubleArrayElements(env, bReJ, bRe, JNI_ABORT);
        if (bIm) (*env)->ReleaseDoubleArrayElements(env, bImJ, bIm, JNI_ABORT);
        if (cRe) (*env)->ReleaseDoubleArrayElements(env, cReJ, cRe, JNI_ABORT);
        if (cIm) (*env)->ReleaseDoubleArrayElements(env, cImJ, cIm, JNI_ABORT);
        return -1;
    }
    int szm = m * k, szk = k * n, szc = m * n;
    double _Complex *za = malloc(sizeof(double _Complex) * (size_t) szm);
    double _Complex *zb = malloc(sizeof(double _Complex) * (size_t) szk);
    double _Complex *zc = malloc(sizeof(double _Complex) * (size_t) szc);
    if (!za || !zb || !zc) {
        free(za); free(zb); free(zc);
        (*env)->ReleaseDoubleArrayElements(env, aReJ, aRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, aImJ, aIm, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, bReJ, bRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, bImJ, bIm, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, cReJ, cRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, cImJ, cIm, JNI_ABORT);
        return -1;
    }
    for (int i = 0; i < szm; i++) za[i] = aRe[i] + I * aIm[i];
    for (int i = 0; i < szk; i++) zb[i] = bRe[i] + I * bIm[i];
    for (int i = 0; i < szc; i++) zc[i] = 0;
    int lda = transA ? k : m;
    int ldb = transB ? n : k;
    double _Complex alpha = 1.0 + 0.0 * I;
    double _Complex beta = 0.0 + 0.0 * I;
    cblas_zgemm(CblasRowMajor,
                transA ? CblasTrans : CblasNoTrans,
                transB ? CblasTrans : CblasNoTrans,
                m, n, k, &alpha, za, lda,
                zb, ldb, &beta, zc, n);
    for (int i = 0; i < szc; i++) { cRe[i] = creal(zc[i]); cIm[i] = cimag(zc[i]); }
    free(za); free(zb); free(zc);
    (*env)->ReleaseDoubleArrayElements(env, aReJ, aRe, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, aImJ, aIm, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, bReJ, bRe, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, bImJ, bIm, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, cReJ, cRe, 0);
    (*env)->ReleaseDoubleArrayElements(env, cImJ, cIm, 0);
    return 0;
}

/* ==================== axpy（y = α·x + y） ==================== */

/* ---- double (daxpy) ---- */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDaxpy(
        JNIEnv *env, jclass cls, jint n, jdouble alpha,
        jdoubleArray xJ, jdoubleArray yJ) {
    if (n <= 0) return -1;
    jdouble *x = (*env)->GetDoubleArrayElements(env, xJ, NULL);
    jdouble *y = (*env)->GetDoubleArrayElements(env, yJ, NULL);
    if (!x || !y) {
        if (x) (*env)->ReleaseDoubleArrayElements(env, xJ, x, JNI_ABORT);
        if (y) (*env)->ReleaseDoubleArrayElements(env, yJ, y, JNI_ABORT);
        return -1;
    }
    cblas_daxpy(n, alpha, x, 1, y, 1);
    (*env)->ReleaseDoubleArrayElements(env, xJ, x, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, yJ, y, 0);
    return 0;
}

/* ---- float (saxpy) ---- */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeSaxpy(
        JNIEnv *env, jclass cls, jint n, jfloat alpha,
        jfloatArray xJ, jfloatArray yJ) {
    if (n <= 0) return -1;
    jfloat *x = (*env)->GetFloatArrayElements(env, xJ, NULL);
    jfloat *y = (*env)->GetFloatArrayElements(env, yJ, NULL);
    if (!x || !y) {
        if (x) (*env)->ReleaseFloatArrayElements(env, xJ, x, JNI_ABORT);
        if (y) (*env)->ReleaseFloatArrayElements(env, yJ, y, JNI_ABORT);
        return -1;
    }
    cblas_saxpy(n, alpha, x, 1, y, 1);
    (*env)->ReleaseFloatArrayElements(env, xJ, x, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, yJ, y, 0);
    return 0;
}

/* ---- double complex (zaxpy) ---- */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeZaxpy(
        JNIEnv *env, jclass cls, jint n,
        jdouble alphaRe, jdouble alphaIm,
        jdoubleArray xReJ, jdoubleArray xImJ,
        jdoubleArray yReJ, jdoubleArray yImJ) {
    if (n <= 0) return -1;
    jdouble *xRe = (*env)->GetDoubleArrayElements(env, xReJ, NULL);
    jdouble *xIm = (*env)->GetDoubleArrayElements(env, xImJ, NULL);
    jdouble *yRe = (*env)->GetDoubleArrayElements(env, yReJ, NULL);
    jdouble *yIm = (*env)->GetDoubleArrayElements(env, yImJ, NULL);
    if (!xRe || !xIm || !yRe || !yIm) {
        if (xRe) (*env)->ReleaseDoubleArrayElements(env, xReJ, xRe, JNI_ABORT);
        if (xIm) (*env)->ReleaseDoubleArrayElements(env, xImJ, xIm, JNI_ABORT);
        if (yRe) (*env)->ReleaseDoubleArrayElements(env, yReJ, yRe, JNI_ABORT);
        if (yIm) (*env)->ReleaseDoubleArrayElements(env, yImJ, yIm, JNI_ABORT);
        return -1;
    }
    double _Complex *zx = malloc(sizeof(double _Complex) * (size_t) n);
    double _Complex *zy = malloc(sizeof(double _Complex) * (size_t) n);
    if (!zx || !zy) {
        free(zx); free(zy);
        (*env)->ReleaseDoubleArrayElements(env, xReJ, xRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, xImJ, xIm, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, yReJ, yRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, yImJ, yIm, JNI_ABORT);
        return -1;
    }
    for (int i = 0; i < n; i++) { zx[i] = xRe[i] + I * xIm[i]; zy[i] = yRe[i] + I * yIm[i]; }
    double _Complex alpha = alphaRe + alphaIm * I;
    cblas_zaxpy(n, &alpha, zx, 1, zy, 1);
    for (int i = 0; i < n; i++) { yRe[i] = creal(zy[i]); yIm[i] = cimag(zy[i]); }
    free(zx); free(zy);
    (*env)->ReleaseDoubleArrayElements(env, xReJ, xRe, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, xImJ, xIm, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, yReJ, yRe, 0);
    (*env)->ReleaseDoubleArrayElements(env, yImJ, yIm, 0);
    return 0;
}

/* ==================== dot（内积；复数取共轭第一参数 zdotc） ==================== */

/* ---- double (ddot) ---- */
JNIEXPORT jdouble JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDdot(
        JNIEnv *env, jclass cls, jint n,
        jdoubleArray xJ, jdoubleArray yJ) {
    if (n <= 0) return 0.0;
    jdouble *x = (*env)->GetDoubleArrayElements(env, xJ, NULL);
    jdouble *y = (*env)->GetDoubleArrayElements(env, yJ, NULL);
    if (!x || !y) {
        if (x) (*env)->ReleaseDoubleArrayElements(env, xJ, x, JNI_ABORT);
        if (y) (*env)->ReleaseDoubleArrayElements(env, yJ, y, JNI_ABORT);
        return 0.0;
    }
    double r = cblas_ddot(n, x, 1, y, 1);
    (*env)->ReleaseDoubleArrayElements(env, xJ, x, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, yJ, y, JNI_ABORT);
    return (jdouble) r;
}

/* ---- double complex (zdotc) ---- */
JNIEXPORT void JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeZdotc(
        JNIEnv *env, jclass cls, jint n,
        jdoubleArray xReJ, jdoubleArray xImJ,
        jdoubleArray yReJ, jdoubleArray yImJ,
        jdoubleArray outReJ, jdoubleArray outImJ) {
    if (n <= 0) return;
    jdouble *xRe = (*env)->GetDoubleArrayElements(env, xReJ, NULL);
    jdouble *xIm = (*env)->GetDoubleArrayElements(env, xImJ, NULL);
    jdouble *yRe = (*env)->GetDoubleArrayElements(env, yReJ, NULL);
    jdouble *yIm = (*env)->GetDoubleArrayElements(env, yImJ, NULL);
    jdouble *outRe = (*env)->GetDoubleArrayElements(env, outReJ, NULL);
    jdouble *outIm = (*env)->GetDoubleArrayElements(env, outImJ, NULL);
    if (!xRe || !xIm || !yRe || !yIm || !outRe || !outIm) goto cleanup;
    {
        double _Complex *zx = malloc(sizeof(double _Complex) * (size_t) n);
        double _Complex *zy = malloc(sizeof(double _Complex) * (size_t) n);
        if (!zx || !zy) { free(zx); free(zy); goto cleanup; }
        for (int i = 0; i < n; i++) { zx[i] = xRe[i] + I * xIm[i]; zy[i] = yRe[i] + I * yIm[i]; }
        double _Complex r = cblas_zdotc(n, zx, 1, zy, 1);
        outRe[0] = creal(r);
        outIm[0] = cimag(r);
        free(zx); free(zy);
    }
cleanup:
    if (xRe) (*env)->ReleaseDoubleArrayElements(env, xReJ, xRe, JNI_ABORT);
    if (xIm) (*env)->ReleaseDoubleArrayElements(env, xImJ, xIm, JNI_ABORT);
    if (yRe) (*env)->ReleaseDoubleArrayElements(env, yReJ, yRe, JNI_ABORT);
    if (yIm) (*env)->ReleaseDoubleArrayElements(env, yImJ, yIm, JNI_ABORT);
    if (outRe) (*env)->ReleaseDoubleArrayElements(env, outReJ, outRe, 0);
    if (outIm) (*env)->ReleaseDoubleArrayElements(env, outImJ, outIm, 0);
}

/* ==================== nrm2（2-范数） ==================== */

/* ---- double (dnrm2) ---- */
JNIEXPORT jdouble JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDnrm2(
        JNIEnv *env, jclass cls, jint n, jdoubleArray xJ) {
    if (n <= 0) return 0.0;
    jdouble *x = (*env)->GetDoubleArrayElements(env, xJ, NULL);
    if (!x) return 0.0;
    double r = cblas_dnrm2(n, x, 1);
    (*env)->ReleaseDoubleArrayElements(env, xJ, x, JNI_ABORT);
    return (jdouble) r;
}
