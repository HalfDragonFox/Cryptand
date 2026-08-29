package com.hdf.cryptand.circuitsimulation.solver;

/**
 * SuperLU 稀疏 LU（double complex）JNI 入口。
 *
 * 求解 A x = b（A 为 n×n 稀疏复矩阵，CSC 列优先），供相量（AC 复数稳态）
 * 超大型网络使用——稠密 LU 是 O(n³)，数万节点不可行；SuperLU 稀疏 LU 是
 * O(n·nnz) 级别，且原生支持 double complex（Z 类型）。
 *
 * native 方法由 neoforge 侧加载 DLL 后可用（NativeSparseLoader）：
 *   - DLL 缺失/加载失败 → {@link #isLoaded()} 返回 false，调用方回退稠密求解。
 *   - 线程安全：zgssv 每次调用独立，可被网络间并行（solveAll）并发调用。
 */
public final class NativeSparse {

    private static volatile boolean loaded;

    /** SuperLU 切换节点阈值：n ≥ 该值走稀疏；0 = 始终走 SuperLU（只要 native 已加载）。
     *  由 neoforge 侧配置 sparseSolverThreshold 同步（默认 256）。 */
    private static volatile int solverThreshold = 256;

    private NativeSparse() {}

    /**
     * 稀疏复求解（CSC，double complex Z）。输入/输出数组长度：
     *   colPtr[n+1] 列指针（每列起始，升序）；rowIdx[nnz] 行索引（每列内升序）；
     *   re/im[nnz] 非零元实/虚部；rhsRe/rhsIm[n] 右端；outRe/outIm[n] 解。
     * @return 1 成功；0 失败（调用方应回退稠密求解）
     */
    public static native int solveComplex(int n, int nnz, int[] colPtr, int[] rowIdx,
                                          double[] re, double[] im,
                                          double[] rhsRe, double[] rhsIm,
                                          double[] outRe, double[] outIm);

    /**
     * 稀疏复求解（CSC，单精度复数 SCZ，float）。
     * 配置 enableFloatSolver=true 且 native 编译含单精度时使用。
     * @return 1 成功；0 失败（调用方回退 double SuperLU / 稠密）
     */
    public static native int solveComplexFloat(int n, int nnz, int[] colPtr, int[] rowIdx,
                                               float[] re, float[] im,
                                               float[] rhsRe, float[] rhsIm,
                                               float[] outRe, float[] outIm);

    /** 由 neoforge 侧加载 DLL 成功后调用。 */
    public static void setLoaded(boolean loaded) {
        NativeSparse.loaded = loaded;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** 单精度（SCZ/S）native 支持标记（DLL 编译含单精度时由加载器置 true）。 */
    private static volatile boolean singleLoaded;

    public static void setSingleLoaded(boolean v) {
        singleLoaded = v;
    }

    public static boolean isSingleLoaded() {
        return singleLoaded;
    }

    /** 由 neoforge 侧配置同步。t=0 表示始终走 SuperLU。 */
    public static void setSolverThreshold(int t) {
        solverThreshold = Math.max(0, t);
    }

    public static int solverThreshold() {
        return solverThreshold;
    }
}
