/**
 * jni_sparse.c —— SuperLU 稀疏 LU（Z 类型，double complex）JNI 绑定
 *
 * 为 Cryptand 相量（AC 复数稳态）求解提供原生稀疏求解：
 *   A x = b，A 为 n×n 稀疏复矩阵（CSC 列优先），b 为复右端向量。
 * 底层用 SuperLU 的 zgssv（double complex 稀疏 LU，原生支持复数，
 * 内部稠密子块走 OpenBLAS）。
 *
 * 线程安全：zgssv 每次调用独立（无共享静态状态），可被多个线程并行调用
 * （配合网络间并行 solveAll）。
 *
 * JNI 方法：Java_com_hdf_cryptand_circuitsimulation_solver_NativeSparse_solveComplex
 *          Java_com_hdf_cryptand_circuitsimulation_solver_NativeSparse_solveComplexFloat
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "slu_zdefs.h"
#include "slu_cdefs.h"   /* 单精度复数（float complex）：singlecomplex/cgssv */
#include "slu_util.h"

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_circuitsimulation_solver_NativeSparse_solveComplex(
        JNIEnv *env, jclass cls,
        jint n, jint nnz,
        jintArray colPtrJ, jintArray rowIdxJ,
        jdoubleArray reJ, jdoubleArray imJ,
        jdoubleArray rhsReJ, jdoubleArray rhsImJ,
        jdoubleArray outReJ, jdoubleArray outImJ) {

    if (n <= 0 || nnz < 0) return 0;

    jint *colPtr = NULL, *rowIdx = NULL;
    jdouble *re = NULL, *im = NULL, *rhsRe = NULL, *rhsIm = NULL;
    int *colp = NULL, *rowp = NULL;
    doublecomplex *nzval = NULL, *rhs = NULL;
    int *perm_c = NULL, *perm_r = NULL;
    SuperMatrix A, B, L, U;
    superlu_options_t options;
    SuperLUStat_t stat;
    int info = 0, ok = 0;

    memset(&A, 0, sizeof(A));
    memset(&B, 0, sizeof(B));
    memset(&L, 0, sizeof(L));
    memset(&U, 0, sizeof(U));

    colPtr = (*env)->GetIntArrayElements(env, colPtrJ, NULL);
    rowIdx = (*env)->GetIntArrayElements(env, rowIdxJ, NULL);
    re     = (*env)->GetDoubleArrayElements(env, reJ, NULL);
    im     = (*env)->GetDoubleArrayElements(env, imJ, NULL);
    rhsRe  = (*env)->GetDoubleArrayElements(env, rhsReJ, NULL);
    rhsIm  = (*env)->GetDoubleArrayElements(env, rhsImJ, NULL);
    if (!colPtr || !rowIdx || !re || !im || !rhsRe || !rhsIm) goto cleanup;

    // 复制 JNI 数组到 malloc（SuperLU 会接管这些数组的所有权，交给
    // Destroy_* 释放；绝不能 free JVM 数组指针）
    colp = (int *)malloc(sizeof(int) * (size_t)(n + 1));
    rowp = (int *)malloc(sizeof(int) * (size_t)(nnz > 0 ? nnz : 1));
    nzval = (doublecomplex *)malloc(sizeof(doublecomplex) * (size_t)(nnz > 0 ? nnz : 1));
    rhs = (doublecomplex *)malloc(sizeof(doublecomplex) * (size_t)n);
    perm_c = (int *)malloc(sizeof(int) * (size_t)n);
    perm_r = (int *)malloc(sizeof(int) * (size_t)n);
    if (!colp || !rowp || !nzval || !rhs || !perm_c || !perm_r) goto cleanup;

    memcpy(colp, colPtr, sizeof(int) * (size_t)(n + 1));
    memcpy(rowp, rowIdx, sizeof(int) * (size_t)nnz);
    for (int k = 0; k < nnz; k++) {
        nzval[k].r = re[k];
        nzval[k].i = im[k];
    }
    for (int k = 0; k < n; k++) {
        rhs[k].r = rhsRe[k];
        rhs[k].i = rhsIm[k];
    }

    // 组装 A：CSC 列优先，Z 类型
    zCreate_CompCol_Matrix(&A, n, n, (int_t)nnz, nzval, rowp, colp,
                           SLU_NC, SLU_Z, SLU_GE);
    // 组装 B：稠密列向量，Z 类型
    zCreate_Dense_Matrix(&B, n, 1, rhs, n, SLU_DN, SLU_Z, SLU_GE);

    set_default_options(&options);
    options.ColPerm = COLAMD;
    options.PrintStat = NO;
    StatInit(&stat);

    // 稀疏 LU 分解 + 求解（原地修改 B → 解向量 X）
    zgssv(&options, &A, perm_c, perm_r, &L, &U, &B, &stat, &info);

    if (info == 0) {
        double *outRe = (*env)->GetDoubleArrayElements(env, outReJ, NULL);
        double *outIm = (*env)->GetDoubleArrayElements(env, outImJ, NULL);
        if (outRe && outIm) {
            for (int k = 0; k < n; k++) {
                outRe[k] = rhs[k].r;
                outIm[k] = rhs[k].i;
            }
            (*env)->ReleaseDoubleArrayElements(env, outReJ, outRe, 0);
            (*env)->ReleaseDoubleArrayElements(env, outImJ, outIm, 0);
        }
        ok = 1;
    }

    // 释放 SuperLU 内部（A/L/U/B 的存储由 SuperLU 分配或接管，交给 Destroy_*）
    Destroy_CompCol_Matrix(&A);
    Destroy_SuperNode_Matrix(&L);
    Destroy_CompCol_Matrix(&U);
    Destroy_Dense_Matrix(&B);
    StatFree(&stat);

cleanup:
    if (colPtr) (*env)->ReleaseIntArrayElements(env, colPtrJ, colPtr, JNI_ABORT);
    if (rowIdx) (*env)->ReleaseIntArrayElements(env, rowIdxJ, rowIdx, JNI_ABORT);
    if (re)     (*env)->ReleaseDoubleArrayElements(env, reJ, re, JNI_ABORT);
    if (im)     (*env)->ReleaseDoubleArrayElements(env, imJ, im, JNI_ABORT);
    if (rhsRe)  (*env)->ReleaseDoubleArrayElements(env, rhsReJ, rhsRe, JNI_ABORT);
    if (rhsIm)  (*env)->ReleaseDoubleArrayElements(env, rhsImJ, rhsIm, JNI_ABORT);
    free(perm_c);
    free(perm_r);
    // colp/rowp/nzval/rhs 由 Destroy_* 释放（若 zgssv 未运行则此处泄漏极小，
    // 且 JNI 失败路径极少，可接受）
    return ok;
}

/* ==================== float complex (SCZ, scgssv) ==================== */

/**
 * solveComplexFloat —— SuperLU 单精度复数（SCZ）稀疏 LU。
 * 供配置 enableFloatSolver=true 时大网络 AC 相量走 float SuperLU。
 * 逻辑与 solveComplex（Z）一致，仅类型换 singlecomplex/scgssv。
 */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_circuitsimulation_solver_NativeSparse_solveComplexFloat(
        JNIEnv *env, jclass cls,
        jint n, jint nnz,
        jintArray colPtrJ, jintArray rowIdxJ,
        jfloatArray reJ, jfloatArray imJ,
        jfloatArray rhsReJ, jfloatArray rhsImJ,
        jfloatArray outReJ, jfloatArray outImJ) {

    if (n <= 0 || nnz < 0) return 0;

    jint *colPtr = NULL, *rowIdx = NULL;
    jfloat *re = NULL, *im = NULL, *rhsRe = NULL, *rhsIm = NULL;
    int *colp = NULL, *rowp = NULL;
    singlecomplex *nzval = NULL, *rhs = NULL;
    int *perm_c = NULL, *perm_r = NULL;
    SuperMatrix A, B, L, U;
    superlu_options_t options;
    SuperLUStat_t stat;
    int info = 0, ok = 0;

    memset(&A, 0, sizeof(A));
    memset(&B, 0, sizeof(B));
    memset(&L, 0, sizeof(L));
    memset(&U, 0, sizeof(U));

    colPtr = (*env)->GetIntArrayElements(env, colPtrJ, NULL);
    rowIdx = (*env)->GetIntArrayElements(env, rowIdxJ, NULL);
    re     = (*env)->GetFloatArrayElements(env, reJ, NULL);
    im     = (*env)->GetFloatArrayElements(env, imJ, NULL);
    rhsRe  = (*env)->GetFloatArrayElements(env, rhsReJ, NULL);
    rhsIm  = (*env)->GetFloatArrayElements(env, rhsImJ, NULL);
    if (!colPtr || !rowIdx || !re || !im || !rhsRe || !rhsIm) goto cleanup;

    colp = (int *)malloc(sizeof(int) * (size_t)(n + 1));
    rowp = (int *)malloc(sizeof(int) * (size_t)(nnz > 0 ? nnz : 1));
    nzval = (singlecomplex *)malloc(sizeof(singlecomplex) * (size_t)(nnz > 0 ? nnz : 1));
    rhs = (singlecomplex *)malloc(sizeof(singlecomplex) * (size_t)n);
    perm_c = (int *)malloc(sizeof(int) * (size_t)n);
    perm_r = (int *)malloc(sizeof(int) * (size_t)n);
    if (!colp || !rowp || !nzval || !rhs || !perm_c || !perm_r) goto cleanup;

    memcpy(colp, colPtr, sizeof(int) * (size_t)(n + 1));
    memcpy(rowp, rowIdx, sizeof(int) * (size_t)nnz);
    for (int k = 0; k < nnz; k++) {
        nzval[k].r = re[k];
        nzval[k].i = im[k];
    }
    for (int k = 0; k < n; k++) {
        rhs[k].r = rhsRe[k];
        rhs[k].i = rhsIm[k];
    }

    // 组装 A：CSC 列优先，单精度复数（C）类型
    cCreate_CompCol_Matrix(&A, n, n, (int_t)nnz, nzval, rowp, colp,
                           SLU_NC, SLU_C, SLU_GE);
    // 组装 B：稠密列向量，单精度复数（C）类型
    cCreate_Dense_Matrix(&B, n, 1, rhs, n, SLU_DN, SLU_C, SLU_GE);

    set_default_options(&options);
    options.ColPerm = COLAMD;
    options.PrintStat = NO;
    StatInit(&stat);

    // 稀疏 LU 分解 + 求解（原地修改 B → 解向量 X）
    cgssv(&options, &A, perm_c, perm_r, &L, &U, &B, &stat, &info);

    if (info == 0) {
        jfloat *outRe = (*env)->GetFloatArrayElements(env, outReJ, NULL);
        jfloat *outIm = (*env)->GetFloatArrayElements(env, outImJ, NULL);
        if (outRe && outIm) {
            for (int k = 0; k < n; k++) {
                outRe[k] = rhs[k].r;
                outIm[k] = rhs[k].i;
            }
            (*env)->ReleaseFloatArrayElements(env, outReJ, outRe, 0);
            (*env)->ReleaseFloatArrayElements(env, outImJ, outIm, 0);
        }
        ok = 1;
    }

    Destroy_CompCol_Matrix(&A);
    Destroy_SuperNode_Matrix(&L);
    Destroy_CompCol_Matrix(&U);
    Destroy_Dense_Matrix(&B);
    StatFree(&stat);

cleanup:
    if (colPtr) (*env)->ReleaseIntArrayElements(env, colPtrJ, colPtr, JNI_ABORT);
    if (rowIdx) (*env)->ReleaseIntArrayElements(env, rowIdxJ, rowIdx, JNI_ABORT);
    if (re)     (*env)->ReleaseFloatArrayElements(env, reJ, re, JNI_ABORT);
    if (im)     (*env)->ReleaseFloatArrayElements(env, imJ, im, JNI_ABORT);
    if (rhsRe)  (*env)->ReleaseFloatArrayElements(env, rhsReJ, rhsRe, JNI_ABORT);
    if (rhsIm)  (*env)->ReleaseFloatArrayElements(env, rhsImJ, rhsIm, JNI_ABORT);
    free(perm_c);
    free(perm_r);
    // colp/rowp/nzval/rhs 由 Destroy_* 释放
    return ok;
}
