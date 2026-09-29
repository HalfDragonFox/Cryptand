/**
 * jni_dense.c —— OpenBLAS LAPACK 稠密 LU（dgesv/zgesv/sgesv/cgesv）JNI 绑定
 *
 * 为 Cryptand 小网络稠密求解提供原生 LAPACK（SIMD 优化，比自研三重循环快数倍）：
 *   A x = b，A 为 n×n 稠密矩阵（行主序），b 为 n 右端向量。
 * LAPACKE_*gesv 原地修改 A（LU 分解）与 B（解向量）。
 *
 * 线程安全：LAPACKE 每次调用独立（无共享静态状态），可并行调用。
 *
 * JNI 方法：
 *   Java_com_hdf_cryptand_circuitsimulation_solver_NativeDense_denseSolveReal
 *   Java_com_hdf_cryptand_circuitsimulation_solver_NativeDense_denseSolveComplex
 *   Java_com_hdf_cryptand_circuitsimulation_solver_NativeDense_denseSolveRealFloat
 *   Java_com_hdf_cryptand_circuitsimulation_solver_NativeDense_denseSolveComplexFloat
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <complex.h>
#include "lapacke.h"

/* OpenBLAS libopenblas.a 未编入 lapacke_utils.c（LAPACKE 工具函数缺失，
 * 各 LAPACKE driver 引用 LAPACKE_get_nancheck 导致链接失败）。
 * 此处提供兜底实现：返回 1 = 启用 NaN 检查（与 LAPACKE 默认一致）。 */
int LAPACKE_get_nancheck(void) {
    return 1;
}

/* ==================== double real (dgesv) ==================== */

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDenseSolveReal(
        JNIEnv *env, jclass cls, jint n,
        jdoubleArray aJ, jdoubleArray bJ, jintArray ipivJ) {
    if (n <= 0) return -1;
    jdouble *a = (*env)->GetDoubleArrayElements(env, aJ, NULL);
    jdouble *b = (*env)->GetDoubleArrayElements(env, bJ, NULL);
    jint *ipiv = (*env)->GetIntArrayElements(env, ipivJ, NULL);
    if (!a || !b || !ipiv) {
        if (a) (*env)->ReleaseDoubleArrayElements(env, aJ, a, JNI_ABORT);
        if (b) (*env)->ReleaseDoubleArrayElements(env, bJ, b, JNI_ABORT);
        if (ipiv) (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
        return -1;
    }
    lapack_int info = LAPACKE_dgesv(LAPACK_ROW_MAJOR, n, 1, a, n, (lapack_int *) ipiv, b, 1);
    (*env)->ReleaseDoubleArrayElements(env, aJ, a, JNI_ABORT);   // A 不需写回
    (*env)->ReleaseDoubleArrayElements(env, bJ, b, 0);           // B = 解，写回
    (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
    return (jint) info;
}

/* ==================== double complex (zgesv) ==================== */

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDenseSolveComplex(
        JNIEnv *env, jclass cls, jint n,
        jdoubleArray aReJ, jdoubleArray aImJ, jdoubleArray bReJ, jdoubleArray bImJ,
        jintArray ipivJ) {
    if (n <= 0) return -1;
    jdouble *aRe = (*env)->GetDoubleArrayElements(env, aReJ, NULL);
    jdouble *aIm = (*env)->GetDoubleArrayElements(env, aImJ, NULL);
    jdouble *bRe = (*env)->GetDoubleArrayElements(env, bReJ, NULL);
    jdouble *bIm = (*env)->GetDoubleArrayElements(env, bImJ, NULL);
    jint *ipiv = (*env)->GetIntArrayElements(env, ipivJ, NULL);
    if (!aRe || !aIm || !bRe || !bIm || !ipiv) {
        if (aRe) (*env)->ReleaseDoubleArrayElements(env, aReJ, aRe, JNI_ABORT);
        if (aIm) (*env)->ReleaseDoubleArrayElements(env, aImJ, aIm, JNI_ABORT);
        if (bRe) (*env)->ReleaseDoubleArrayElements(env, bReJ, bRe, JNI_ABORT);
        if (bIm) (*env)->ReleaseDoubleArrayElements(env, bImJ, bIm, JNI_ABORT);
        if (ipiv) (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
        return -1;
    }
    lapack_complex_double *a = (lapack_complex_double *) malloc(sizeof(lapack_complex_double) * (size_t) n * (size_t) n);
    lapack_complex_double *b = (lapack_complex_double *) malloc(sizeof(lapack_complex_double) * (size_t) n);
    if (!a || !b) {
        free(a); free(b);
        (*env)->ReleaseDoubleArrayElements(env, aReJ, aRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, aImJ, aIm, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, bReJ, bRe, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, bImJ, bIm, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
        return -1;
    }
    for (int k = 0; k < n * n; k++) a[k] = aRe[k] + I * aIm[k];
    for (int k = 0; k < n; k++) b[k] = bRe[k] + I * bIm[k];
    lapack_int info = LAPACKE_zgesv(LAPACK_ROW_MAJOR, n, 1, a, n, (lapack_int *) ipiv, b, 1);
    for (int k = 0; k < n; k++) {
        bRe[k] = creal(b[k]);
        bIm[k] = cimag(b[k]);
    }
    free(a);
    free(b);
    (*env)->ReleaseDoubleArrayElements(env, aReJ, aRe, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, aImJ, aIm, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, bReJ, bRe, 0);
    (*env)->ReleaseDoubleArrayElements(env, bImJ, bIm, 0);
    (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
    return (jint) info;
}

/* ==================== float real (sgesv) ==================== */

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDenseSolveRealFloat(
        JNIEnv *env, jclass cls, jint n,
        jfloatArray aJ, jfloatArray bJ, jintArray ipivJ) {
    if (n <= 0) return -1;
    jfloat *a = (*env)->GetFloatArrayElements(env, aJ, NULL);
    jfloat *b = (*env)->GetFloatArrayElements(env, bJ, NULL);
    jint *ipiv = (*env)->GetIntArrayElements(env, ipivJ, NULL);
    if (!a || !b || !ipiv) {
        if (a) (*env)->ReleaseFloatArrayElements(env, aJ, a, JNI_ABORT);
        if (b) (*env)->ReleaseFloatArrayElements(env, bJ, b, JNI_ABORT);
        if (ipiv) (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
        return -1;
    }
    lapack_int info = LAPACKE_sgesv(LAPACK_ROW_MAJOR, n, 1, a, n, (lapack_int *) ipiv, b, 1);
    (*env)->ReleaseFloatArrayElements(env, aJ, a, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, bJ, b, 0);
    (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
    return (jint) info;
}

/* ==================== float complex (cgesv) ==================== */

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_math_NativeMath_nativeDenseSolveComplexFloat(
        JNIEnv *env, jclass cls, jint n,
        jfloatArray aReJ, jfloatArray aImJ, jfloatArray bReJ, jfloatArray bImJ,
        jintArray ipivJ) {
    if (n <= 0) return -1;
    jfloat *aRe = (*env)->GetFloatArrayElements(env, aReJ, NULL);
    jfloat *aIm = (*env)->GetFloatArrayElements(env, aImJ, NULL);
    jfloat *bRe = (*env)->GetFloatArrayElements(env, bReJ, NULL);
    jfloat *bIm = (*env)->GetFloatArrayElements(env, bImJ, NULL);
    jint *ipiv = (*env)->GetIntArrayElements(env, ipivJ, NULL);
    if (!aRe || !aIm || !bRe || !bIm || !ipiv) {
        if (aRe) (*env)->ReleaseFloatArrayElements(env, aReJ, aRe, JNI_ABORT);
        if (aIm) (*env)->ReleaseFloatArrayElements(env, aImJ, aIm, JNI_ABORT);
        if (bRe) (*env)->ReleaseFloatArrayElements(env, bReJ, bRe, JNI_ABORT);
        if (bIm) (*env)->ReleaseFloatArrayElements(env, bImJ, bIm, JNI_ABORT);
        if (ipiv) (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
        return -1;
    }
    lapack_complex_float *a = (lapack_complex_float *) malloc(sizeof(lapack_complex_float) * (size_t) n * (size_t) n);
    lapack_complex_float *b = (lapack_complex_float *) malloc(sizeof(lapack_complex_float) * (size_t) n);
    if (!a || !b) {
        free(a); free(b);
        (*env)->ReleaseFloatArrayElements(env, aReJ, aRe, JNI_ABORT);
        (*env)->ReleaseFloatArrayElements(env, aImJ, aIm, JNI_ABORT);
        (*env)->ReleaseFloatArrayElements(env, bReJ, bRe, JNI_ABORT);
        (*env)->ReleaseFloatArrayElements(env, bImJ, bIm, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
        return -1;
    }
    for (int k = 0; k < n * n; k++) a[k] = aRe[k] + I * aIm[k];
    for (int k = 0; k < n; k++) b[k] = bRe[k] + I * bIm[k];
    lapack_int info = LAPACKE_cgesv(LAPACK_ROW_MAJOR, n, 1, a, n, (lapack_int *) ipiv, b, 1);
    for (int k = 0; k < n; k++) {
        bRe[k] = crealf(b[k]);
        bIm[k] = cimagf(b[k]);
    }
    free(a);
    free(b);
    (*env)->ReleaseFloatArrayElements(env, aReJ, aRe, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, aImJ, aIm, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, bReJ, bRe, 0);
    (*env)->ReleaseFloatArrayElements(env, bImJ, bIm, 0);
    (*env)->ReleaseIntArrayElements(env, ipivJ, ipiv, JNI_ABORT);
    return (jint) info;
}
