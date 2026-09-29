package com.hdf.cryptand.math;

/**
 * 统一底层数学门面（2026-08-30 用户：OpenBLAS+SuperLU 全部提供底层统一接口，
 * 电路仿真核心、集成网络核心等【多个核心】可发送计算到此接口）。
 *
 * <p><b>设计</b>：本类是纯函数门面（无状态、线程安全），双实现：
 * <ul>
 *   <li><b>native 路径</b>：DLL 加载成功（{@link #isLoaded()} = true）→
 *       全部调用走 OpenBLAS/SuperLU（SIMD 加速）。</li>
 *   <li><b>Java 回退路径</b>：DLL 缺失/加载失败 → 同一批 public 静态方法
 *       内部自动转纯 Java 实现（结果一致，精度稍低、速度慢）。</li>
 * </ul>
 * 调用方【不需要关心 DLL 是否存在】——接口签名在两种路径下完全一致。
 *
 * <p><b>能力</b>：
 * <ul>
 *   <li>{@link #solveDenseReal} / {@link #solveDenseComplex} —— 稠密 LU
 *       （A x = b，OpenBLAS dgesv/zgesv）。</li>
 *   <li>{@link #solveSparseComplex} —— 稀疏 LU（CSC，SuperLU Z 类型）。</li>
 *   <li>{@link #dgemm}/{@link #sgemm}/{@link #zgemm} —— 矩阵乘法
 *       （C = A·B，支持转置；用于网络雅可比/热扩散等）。</li>
 *   <li>{@link #daxpy}/{@link #zaxpy} —— 向量更新 y = α·x + y。</li>
 *   <li>{@link #ddot}/{@link #zdotc} —— 内积（复共轭第一参数）。</li>
 *   <li>{@link #dnrm2} —— 2-范数。</li>
 * </ul>
 *
 * <p><b>JNI 多线程治理（2026-08-30 用户：用 ReadWriteGate 对 JNI 多线程调用）</b>：
 * 所有 native 调用经 {@link #JNI_GATE}（{@link ReadWriteGate} 线程执行表）管控：
 *   - 默认 {@link ReadWriteGate.AccessMode#ORDERED}（顺序独占）——OpenBLAS/SuperLU
 *     的 LAPACK/SuperLU 例程可能有内部全局/scratch 状态，多个求解线程同时进 native
 *     可能崩溃/结果错误；ORDERED 保证同一时刻仅一个线程进入 native。
 *   - 可选 {@link ReadWriteGate.AccessMode#UNORDERED}（乱序并发）——若确认某些操作
 *     纯函数线程安全（无共享状态），可 {@link #setJniConcurrent(boolean)} 改为乱序
 *     （多个 solve 线程并行进 native，吞吐更高）。
 *   - 容量默认 {@value ReadWriteGate#DEFAULT_MAX_THREADS}（30）。
 *
 * <p><b>线程安全</b>：除 gate 外所有方法无共享可变状态（native 每次调用独立），
 * 可被多个核心线程（如网络间并行 solveAll）并发调用（经 gate 串行化/受控并发）。
 *
 * <p><b>加载</b>：由平台侧（neoforge {@code NativeMathLoader}）加载 DLL 后
 * 调用 {@link #setLoaded}；common 模块零 MC 依赖。
 */
public final class NativeMath {

    /** JNI 线程执行表（2026-08-30 用户：native 调用经 ReadWriteGate 管控） */
    private static final com.hdf.cryptand.core.concurrent.ReadWriteGate JNI_GATE =
            new com.hdf.cryptand.core.concurrent.ReadWriteGate();

    /** native 是否并发（UNORDERED）访问；false = 顺序独占（ORDERED，默认，最安全） */
    private static volatile boolean jniConcurrent;

    /** JNI 门闸是否启用（2026-08-30 用户：每项优化独立配置；false = 直接调用不管控） */
    private static volatile boolean jniGateEnabled;

    // ===== 加载状态（neoforge 侧加载 DLL 后置位） =====

    private static volatile boolean loaded;         // DLL 已加载（稠密+稀疏+BLAS 全可用）
    private static volatile boolean singleLoaded;   // 单精度（sgemm/sgesv）可用

    private NativeMath() {}

    /**
     * 由平台侧加载 DLL 成功后调用（幂等）。
     */
    public static void setLoaded(boolean v) {
        loaded = v;
    }

    /** DLL 已加载（稠密/稀疏/BLAS 全可用）。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 单精度（sgemm/sgesv）native 支持标记（DLL 编译含单精度时置 true）。 */
    public static void setSingleLoaded(boolean v) {
        singleLoaded = v;
    }

    public static boolean isSingleLoaded() {
        return singleLoaded;
    }

    // ===== JNI 门闸管控（2026-08-30 用户） =====

    /**
     * 设置 JNI 访问模式：true = 乱序并发（UNORDERED：多个 native 调用同时执行；
     * 仅当确认底层库调用纯函数线程安全时使用，否则可能崩溃/结果错误）；
     * false = 顺序独占（ORDERED：同一时刻仅一个线程进入 native，默认、最安全）。
     */
    public static void setJniConcurrent(boolean concurrent) {
        jniConcurrent = concurrent;
    }

    /** 当前 JNI 访问模式：true = 乱序并发；false = 顺序独占。 */
    public static boolean isJniConcurrent() {
        return jniConcurrent;
    }

    /**
     * 设置 JNI 门闸是否启用（2026-08-30 用户：每项优化独立配置）。
     * true = native 调用经 ReadWriteGate 管控（防并发踩踏/受控并发）；
     * false = 直接调用（OpenBLAS/SuperLU 原样并发，无管控——仅当确认安全）。
     */
    public static void setJniGateEnabled(boolean enabled) {
        jniGateEnabled = enabled;
    }

    /** 当前 JNI 门闸是否启用。 */
    public static boolean isJniGateEnabled() {
        return jniGateEnabled;
    }

    /** 当前 JNI 线程执行表容量（同时执行上限；默认 30）。 */
    public static int jniCapacity() {
        return JNI_GATE.capacity();
    }

    /**
     * 调整 JNI 线程执行表容量（可运行时更改；仅乱序并发时有意义，顺序独占恒=1）。
     */
    public static void setJniCapacity(int capacity) {
        JNI_GATE.setCapacity(capacity);
    }

    /** 当前 JNI 执行中的线程数（诊断/HUD 用）。 */
    public static int jniActive() {
        return JNI_GATE.totalCount();
    }

    /** 当前 JNI 排队线程数（诊断/HUD 用）。 */
    public static int jniQueued() {
        return JNI_GATE.queued();
    }

    /**
     * **JNI 调用内部入口**：经 ReadWriteGate 放行后执行 native 调用。
     * 顺序独占模式：同一时刻仅一个线程调用 native（严格串行）。
     * 乱序并发模式：多个线程可同时调用（容量内）。
     */
    private static void jniCall(java.util.function.IntSupplier op) {
        jniCallInternal(op);
    }

    /** 自测/门闸语义验证入口（包级，仅供 NativeMathSelfTest 使用）。 */
    static void jniCallForTest(java.util.function.IntSupplier op) {
        jniCallInternal(op);
    }

    private static void jniCallInternal(java.util.function.IntSupplier op) {
        // 2026-08-30 用户：每项优化独立配置 → 未启用门闸时直接调用（无管控）
        if (!jniGateEnabled) {
            op.getAsInt();
            return;
        }
        try {
            JNI_GATE.enter(jniConcurrent
                    ? com.hdf.cryptand.core.concurrent.ReadWriteGate.AccessMode.UNORDERED
                    : com.hdf.cryptand.core.concurrent.ReadWriteGate.AccessMode.ORDERED);
            try {
                op.getAsInt();
            } finally {
                JNI_GATE.exit(jniConcurrent
                        ? com.hdf.cryptand.core.concurrent.ReadWriteGate.AccessMode.UNORDERED
                        : com.hdf.cryptand.core.concurrent.ReadWriteGate.AccessMode.ORDERED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * **JNI 调用——线程分配器入口**（2026-08-30 用户：并行采用线程分配器进行任务分配）。
     * 把 native 调用交给 {@link com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers}
     * （线程分配器：优先空闲 Worker → 最低负载；Worker 虚拟线程执行），
     * 门闸（ORDERED 串行 / UNORDERED 并发）管控 native 进入。
     * 供"多网络并行求解"使用：每个网络的 native 调用都被分配器均衡到不同 Worker，
     * 不再挤在同一线程上忙等门闸；同时单次 native 仍受门闸保护（防库竞争）。
     *
     * @param op native 调用体（IntSupplier：int 返回值）
     * @param wait 是否等待完成（true = 阻塞到 native 返回；false = 异步提交，结果经 op 内部捕获）
     */
    static void jniCallAsync(java.util.function.IntSupplier op, boolean wait) {
        var f = com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                .submitGeneric(() -> jniCallInternal(op));
        if (wait) {
            try {
                f.join();
            } catch (Throwable ignored) {
            }
        }
    }

    // ===== 稠密 LU 求解（A x = b；行主序展平 a[i*n+j]） =====

    /**
     * 稠密实数求解（LAPACK dgesv，double）。成功后 x 载入解，ipiv 载入主元置换。
     *
     * @return 0 成功；&gt;0 LAPACK 奇异矩阵标号（主元行为零时）；&lt;0 参数错/失败
     */
    public static native int nativeDenseSolveReal(int n, double[] a, double[] b, int[] ipiv);

    /**
     * 稠密复求解（LAPACK zgesv，double complex；re/im 分离数组）。
     * @return 0 成功；&gt;0 奇异；&lt;0 参数错/失败
     */
    public static native int nativeDenseSolveComplex(int n, double[] aRe, double[] aIm,
                                                     double[] bRe, double[] bIm, int[] ipiv);

    /** 稠密实数求解（LAPACK sgesv，float）。@return 0 成功；&gt;0 奇异；&lt;0 失败 */
    public static native int nativeDenseSolveRealFloat(int n, float[] a, float[] b, int[] ipiv);

    /** 稠密复求解（LAPACK cgesv，float complex）。@return 0 成功；&gt;0 奇异；&lt;0 失败 */
    public static native int nativeDenseSolveComplexFloat(int n, float[] aRe, float[] aIm,
                                                          float[] bRe, float[] bIm, int[] ipiv);

    // ===== 稀疏 LU 求解（CSC 列优先；SuperLU Z 类型 double complex） =====

    /**
     * 稀疏复求解（CSC，double complex Z）。colPtr[n+1]、rowIdx[nnz]（每列内升序）、
     * re/im[nnz]、rhsRe/rhsIm[n]、outRe/outIm[n]。
     * @return 1 成功；0 失败（调用方应回退稠密）
     */
    public static native int nativeSolveSparseComplex(int n, int nnz, int[] colPtr, int[] rowIdx,
                                                      double[] re, double[] im,
                                                      double[] rhsRe, double[] rhsIm,
                                                      double[] outRe, double[] outIm);

    /**
     * 稀疏复求解（CSC，单精度复数 SCZ，float）。@return 1 成功；0 失败
     */
    public static native int nativeSolveSparseComplexFloat(int n, int nnz, int[] colPtr, int[] rowIdx,
                                                           float[] re, float[] im,
                                                           float[] rhsRe, float[] rhsIm,
                                                           float[] outRe, float[] outIm);

    // ===== BLAS Level 1/3（矩阵：行主序展平） =====

    /** 矩阵乘法 C = A·B（m×k × k×n）；行主序；transA/transB 转置。@return 0 成功；-1 参数错 */
    public static native int nativeDgemm(int m, int n, int k, double[] a, double[] b, double[] c,
                                         boolean transA, boolean transB);

    /** 矩阵乘法 C = A·B（float）。@return 0 成功；-1 参数错 */
    public static native int nativeSgemm(int m, int n, int k, float[] a, float[] b, float[] c,
                                         boolean transA, boolean transB);

    /** 矩阵乘法 C = A·B（double complex；re/im 分离）。@return 0 成功；-1 参数错 */
    public static native int nativeZgemm(int m, int n, int k,
                                         double[] aRe, double[] aIm,
                                         double[] bRe, double[] bIm,
                                         double[] cRe, double[] cIm,
                                         boolean transA, boolean transB);

    /** 向量更新 y = α·x + y。@return 0 成功；-1 参数错 */
    public static native int nativeDaxpy(int n, double alpha, double[] x, double[] y);

    /** 向量更新 y = α·x + y（float）。@return 0 成功；-1 参数错 */
    public static native int nativeSaxpy(int n, float alpha, float[] x, float[] y);

    /** 向量更新 y = α·x + y（double complex）。@return 0 成功；-1 参数错 */
    public static native int nativeZaxpy(int n, double alphaRe, double alphaIm,
                                         double[] xRe, double[] xIm,
                                         double[] yRe, double[] yIm);

    /** 内积（实数）。@return dot(x,y) */
    public static native double nativeDdot(int n, double[] x, double[] y);

    /** 复内积（共轭第一参数 zdotc）。写入 outRe[0]/outIm[0]。 */
    public static native void nativeZdotc(int n, double[] xRe, double[] xIm,
                                          double[] yRe, double[] yIm,
                                          double[] outRe, double[] outIm);

    /** 2-范数（实数）。 */
    public static native double nativeDnrm2(int n, double[] x);

    // =====================================================================
    // Java 回退实现（无 DLL 时自动使用；与 native 结果一致）
    // =====================================================================

    /** DLL 已加载 → native；否则 Java LU 分解回退。n≤0 返回 null；奇异返回 null。 */
    public static double[] solveDenseReal(int n, double[] a, double[] b) {
        if (loaded) {
            double[] aa = a.clone();
            double[] x = b.clone();
            int[] ipiv = new int[n];
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeDenseSolveReal(n, aa, x, ipiv);
                return 0;
            });
            if (rc[0] == 0) return x;
            return null;
        }
        return luSolveReal(n, a, b);
    }

    /** DLL 已加载 → native zgesv；否则 Java 稠密 LU。n≤0 → null；奇异 → null。 */
    public static ComplexD[] solveDenseComplex(int n, double[] aRe, double[] aIm,
                                               double[] bRe, double[] bIm) {
        if (loaded) {
            double[] a1 = aRe.clone(), a2 = aIm.clone();
            double[] b1 = bRe.clone(), b2 = bIm.clone();
            int[] ipiv = new int[n];
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeDenseSolveComplex(n, a1, a2, b1, b2, ipiv);
                return 0;
            });
            if (rc[0] == 0) {
                ComplexD[] x = new ComplexD[n];
                for (int i = 0; i < n; i++) x[i] = new ComplexD(b1[i], b2[i]);
                return x;
            }
            return null;
        }
        return luSolveComplex(n, aRe, aIm, bRe, bIm);
    }

    /** 稀疏复求解：DLL 加载 &amp; 阈值满足 → native SuperLU；否则 Java 稠密回退。
     *  返回 null = 失败（调用方回退）。 */
    public static ComplexD[] solveSparseComplex(int n, int nnz, int[] colPtr, int[] rowIdx,
                                                double[] re, double[] im,
                                                double[] rhsRe, double[] rhsIm) {
        if (loaded) {
            double[] outRe = new double[n], outIm = new double[n];
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeSolveSparseComplex(n, nnz, colPtr, rowIdx, re, im,
                        rhsRe, rhsIm, outRe, outIm);
                return 0;
            });
            if (rc[0] == 1) {
                ComplexD[] x = new ComplexD[n];
                for (int i = 0; i < n; i++) x[i] = new ComplexD(outRe[i], outIm[i]);
                return x;
            }
            return null;
        }
        // Java 回退：把 CSC → 稠密再 LU
        double[] aRe = new double[n * n], aIm = new double[n * n];
        for (int c = 0; c < n; c++) {
            for (int p = colPtr[c]; p < colPtr[c + 1]; p++) {
                int r = rowIdx[p];
                aRe[r * n + c] += re[p];
                aIm[r * n + c] += im[p];
            }
        }
        return luSolveComplex(n, aRe, aIm, rhsRe, rhsIm);
    }

    // ===== BLAS：native 或 Java 回退 =====

    /** C = A·B（dgemm）。n≤0 或维度不符返回 false。 */
    public static boolean dgemm(int m, int n, int k, double[] a, double[] b, double[] c,
                                boolean transA, boolean transB) {
        if (transA ? a.length < k * m : a.length < m * k) return false;
        if (loaded) {
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeDgemm(m, n, k, a, b, c, transA, transB);
                return 0;
            });
            return rc[0] == 0;
        }
        gemmReal(m, n, k, a, b, c, transA, transB);
        return true;
    }

    /** C = A·B（sgemm）。 */
    public static boolean sgemm(int m, int n, int k, float[] a, float[] b, float[] c,
                                boolean transA, boolean transB) {
        if (loaded) {
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeSgemm(m, n, k, a, b, c, transA, transB);
                return 0;
            });
            return rc[0] == 0;
        }
        gemmFloat(m, n, k, a, b, c, transA, transB);
        return true;
    }

    /** C = A·B（zgemm 复数）。 */
    public static boolean zgemm(int m, int n, int k,
                                double[] aRe, double[] aIm,
                                double[] bRe, double[] bIm,
                                double[] cRe, double[] cIm,
                                boolean transA, boolean transB) {
        if (loaded) {
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeZgemm(m, n, k, aRe, aIm, bRe, bIm, cRe, cIm, transA, transB);
                return 0;
            });
            return rc[0] == 0;
        }
        gemmComplex(m, n, k, aRe, aIm, bRe, bIm, cRe, cIm, transA, transB);
        return true;
    }

    /** y = α·x + y（daxpy）。 */
    public static boolean daxpy(int n, double alpha, double[] x, double[] y) {
        if (loaded) {
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeDaxpy(n, alpha, x, y);
                return 0;
            });
            return rc[0] == 0;
        }
        for (int i = 0; i < n; i++) y[i] += alpha * x[i];
        return true;
    }

    /** y = α·x + y（saxpy）。 */
    public static boolean saxpy(int n, float alpha, float[] x, float[] y) {
        if (loaded) {
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeSaxpy(n, alpha, x, y);
                return 0;
            });
            return rc[0] == 0;
        }
        for (int i = 0; i < n; i++) y[i] += alpha * x[i];
        return true;
    }

    /** y = α·x + y（zaxpy 复数）。 */
    public static boolean zaxpy(int n, double alphaRe, double alphaIm,
                                double[] xRe, double[] xIm, double[] yRe, double[] yIm) {
        if (loaded) {
            int[] rc = new int[1];
            jniCall(() -> {
                rc[0] = nativeZaxpy(n, alphaRe, alphaIm, xRe, xIm, yRe, yIm);
                return 0;
            });
            return rc[0] == 0;
        }
        for (int i = 0; i < n; i++) {
            double nr = alphaRe * xRe[i] - alphaIm * xIm[i];
            double ni = alphaRe * xIm[i] + alphaIm * xRe[i];
            yRe[i] += nr;
            yIm[i] += ni;
        }
        return true;
    }

    /** 实数内积 ddot。n≤0 → 0。 */
    public static double ddot(int n, double[] x, double[] y) {
        if (loaded) {
            double[] rc = new double[1];
            jniCall(() -> {
                rc[0] = nativeDdot(n, x, y);
                return 0;
            });
            return rc[0];
        }
        double s = 0;
        for (int i = 0; i < n; i++) s += x[i] * y[i];
        return s;
    }

    /** 复内积 zdotc（共轭第一参数）。返回 {re, im}。 */
    public static double[] zdotc(int n, double[] xRe, double[] xIm,
                                 double[] yRe, double[] yIm) {
        if (loaded) {
            double[] outRe = new double[2], outIm = new double[2];
            jniCall(() -> {
                nativeZdotc(n, xRe, xIm, yRe, yIm, outRe, outIm);
                return 0;
            });
            return new double[]{outRe[0], outIm[0]};
        }
        double re = 0, im = 0;
        for (int i = 0; i < n; i++) {
            re += xRe[i] * yRe[i] + xIm[i] * yIm[i];
            im += xRe[i] * yIm[i] - xIm[i] * yRe[i];
        }
        return new double[]{re, im};
    }

    /** 2-范数 dnrm2。n≤0 → 0。 */
    public static double dnrm2(int n, double[] x) {
        if (loaded) {
            double[] rc = new double[1];
            jniCall(() -> {
                rc[0] = nativeDnrm2(n, x);
                return 0;
            });
            return rc[0];
        }
        double s = 0;
        for (int i = 0; i < n; i++) s += x[i] * x[i];
        return Math.sqrt(s);
    }

    // ===== Java 回退内部实现（纯 Java，无 native 依赖） =====

    /** 双精度复数容器（解向量）。 */
    public static final class ComplexD {
        public final double re, im;
        public ComplexD(double re, double im) {
            this.re = re;
            this.im = im;
        }
    }

    /** Java 稠密 LU（double real）——高斯消元带部分主元。成功返回解；奇异返回 null。 */
    private static double[] luSolveReal(int n, double[] a, double[] b) {
        if (n <= 0) return null;
        double[] m = a.clone();
        double[] x = b.clone();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        for (int p = 0; p < n; p++) {
            int max = p;
            for (int i = p + 1; i < n; i++) {
                if (Math.abs(m[idx[i] * n + p]) > Math.abs(m[idx[max] * n + p])) max = i;
            }
            if (Math.abs(m[idx[max] * n + p]) < 1e-300) return null; // 奇异
            int t = idx[p]; idx[p] = idx[max]; idx[max] = t;
            int piv = idx[p];
            for (int i = p + 1; i < n; i++) {
                int row = idx[i];
                double f = m[row * n + p] / m[piv * n + p];
                for (int j = p; j < n; j++) m[row * n + j] -= f * m[piv * n + j];
                x[row] -= f * x[piv];
            }
        }
        double[] out = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            int row = idx[i];
            double s = x[row];
            for (int j = i + 1; j < n; j++) s -= m[row * n + j] * out[j];
            out[i] = s / m[row * n + i];
        }
        return out;
    }

    /** Java 稠密 LU（double complex）——结构与 luSolveReal 同。 */
    private static ComplexD[] luSolveComplex(int n, double[] aRe, double[] aIm,
                                             double[] bRe, double[] bIm) {
        if (n <= 0) return null;
        double[] mr = aRe.clone(), mi = aIm.clone();
        double[] xr = bRe.clone(), xi = bIm.clone();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        for (int p = 0; p < n; p++) {
            int max = p;
            for (int i = p + 1; i < n; i++) {
                double c1 = mag(mr[idx[i] * n + p], mi[idx[i] * n + p]);
                double c2 = mag(mr[idx[max] * n + p], mi[idx[max] * n + p]);
                if (c1 > c2) max = i;
            }
            if (mag(mr[idx[max] * n + p], mi[idx[max] * n + p]) < 1e-300) return null;
            int t = idx[p]; idx[p] = idx[max]; idx[max] = t;
            int piv = idx[p];
            double prr = mr[piv * n + p], pri = mi[piv * n + p];
            for (int i = p + 1; i < n; i++) {
                int row = idx[i];
                double arr = mr[row * n + p], ari = mi[row * n + p];
                double den = prr * prr + pri * pri;
                double fr = (arr * prr + ari * pri) / den;
                double fi = (ari * prr - arr * pri) / den;
                for (int j = p; j < n; j++) {
                    mr[row * n + j] -= fr * mr[piv * n + j] - fi * mi[piv * n + j];
                    mi[row * n + j] -= fr * mi[piv * n + j] + fi * mr[piv * n + j];
                }
                xr[row] -= fr * xr[piv] - fi * xi[piv];
                xi[row] -= fr * xi[piv] + fi * xr[piv];
            }
        }
        ComplexD[] out = new ComplexD[n];
        for (int i = n - 1; i >= 0; i--) {
            int row = idx[i];
            double s1 = xr[row], s2 = xi[row];
            for (int j = i + 1; j < n; j++) {
                double a1 = mr[row * n + j], a2 = mi[row * n + j];
                s1 -= a1 * out[j].re - a2 * out[j].im;
                s2 -= a1 * out[j].im + a2 * out[j].re;
            }
            double den = mr[row * n + i] * mr[row * n + i] + mi[row * n + i] * mi[row * n + i];
            out[i] = new ComplexD(
                    (s1 * mr[row * n + i] + s2 * mi[row * n + i]) / den,
                    (s2 * mr[row * n + i] - s1 * mi[row * n + i]) / den);
        }
        return out;
    }

    private static double mag(double re, double im) {
        return Math.sqrt(re * re + im * im);
    }

    /** Java dgemm 回退（C = A·B，行主序，transA/transB 转置）。 */
    private static void gemmReal(int m, int n, int k, double[] a, double[] b, double[] c,
                                 boolean transA, boolean transB) {
        java.util.Arrays.fill(c, 0, m * n, 0);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int p = 0; p < k; p++) {
                    double av = transA ? a[p * m + i] : a[i * k + p];
                    double bv = transB ? b[j * k + p] : b[p * n + j];
                    s += av * bv;
                }
                c[i * n + j] = s;
            }
        }
    }

    /** Java sgemm 回退。 */
    private static void gemmFloat(int m, int n, int k, float[] a, float[] b, float[] c,
                                  boolean transA, boolean transB) {
        java.util.Arrays.fill(c, 0, m * n, 0f);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                float s = 0;
                for (int p = 0; p < k; p++) {
                    float av = transA ? a[p * m + i] : a[i * k + p];
                    float bv = transB ? b[j * k + p] : b[p * n + j];
                    s += av * bv;
                }
                c[i * n + j] = s;
            }
        }
    }

    /** Java zgemm 回退（复数）。 */
    private static void gemmComplex(int m, int n, int k,
                                    double[] aRe, double[] aIm,
                                    double[] bRe, double[] bIm,
                                    double[] cRe, double[] cIm,
                                    boolean transA, boolean transB) {
        java.util.Arrays.fill(cRe, 0, m * n, 0);
        java.util.Arrays.fill(cIm, 0, m * n, 0);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double s1 = 0, s2 = 0;
                for (int p = 0; p < k; p++) {
                    double ar = transA ? aRe[p * m + i] : aRe[i * k + p];
                    double ai = transA ? aIm[p * m + i] : aIm[i * k + p];
                    double br = transB ? bRe[j * k + p] : bRe[p * n + j];
                    double bi = transB ? bIm[j * k + p] : bIm[p * n + j];
                    s1 += ar * br - ai * bi;
                    s2 += ar * bi + ai * br;
                }
                cRe[i * n + j] = s1;
                cIm[i * n + j] = s2;
            }
        }
    }
}
