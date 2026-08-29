package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;

/**
 * 复数（AC 相量）MNA 求解器。
 * 只用于线性稳态 AC；直流叠加需在 f=0 用 RealMnaSolver 单独解。
 */
public class ComplexMnaSolver implements Solver {

    /** 防奇异 GMin（SPICE 风格）：孤立节点/浮空回路的最小导纳到地 */
    private static final double GMIN = 1e-7;

    /**
     * DC 等效频率（Hz，2026-08-14 伪时域）。完全禁用时域后 DC(0Hz) 网络也走
     * 相量：ω 取 max(frequency, DC_EQUIV_FREQ_HZ) —— 电感 Y=1/(jωL) 接近短路、
     * 电容 Y=jωC 接近开路，数值不除零（ω=0 时电感导纳 -Infinity 会破坏 LU）。
     */
    public static final double DC_EQUIV_FREQ_HZ = 1e-4;

    @Override
    public SolveMode mode() { return SolveMode.COMPLEX_AC; }

    @Override
    public SolveResult solve(Network net) {
        // 2026-08-20 求解器原生开路支持：stamp 前标记开路电流源（两端无闭合
        // 回路 → 不注入，避免悬空端发散 100MV）
        Solvers.markOpenCurrentSources(net);
        long t0 = System.nanoTime();
        int n = net.nodeCount();
        int g = net.groundNode;
        // 2026-08-20 修复"AC 源不接任何线单独测 100MV+ 电流"：网络【无接地节点】
        // （孤立源/浮空回路，如孤立的 AC 创造源）→ 矩阵无绝对参考 → 奇异 →
        // 数值发散（实测 1e8V）。标准 MNA 必须有参考节点：无接地 → 选节点 0
        // 作参考地（等效接地，不改变相对电压）。
        if (g < 0 && n > 0) g = 0;
        // 伪时域：DC(0Hz) 用等效小频率（避免 ω=0 电感导纳除零）
        double omega = 2 * Math.PI * Math.max(net.frequency, DC_EQUIV_FREQ_HZ);

        ComplexMnaBuilder m = new ComplexMnaBuilder(n);
        for (Element e : net.elements()) e.stampComplex(m, omega);

        // 固定接地节点
        if (g >= 0 && g < n) {
            m.clearRowCol(g);
            m.addY(g, g, new Complex(1, 0));
            m.b[g] = Complex.ZERO;
        }

        // GMin 兜底：孤立节点（无元件连接）会让矩阵奇异 → 每个非参考节点
        // 对角加小导纳到地（远小于真实元件导纳，对差分电压影响可忽略）
        for (int i = 0; i < n; i++) {
            if (i == g) continue;
            m.addY(i, i, new Complex(GMIN, 0));
        }

        // 2026-08-11：超大型网络走 SuperLU 稀疏 LU（native），小网络走稠密 LU。
        // 切换阈值由配置 sparseSolverThreshold 控制（0 = 始终走 SuperLU）。
        // 两者结果应一致（稀疏是精确 LU，非迭代近似）；native 失败自动回退稠密。
        Complex[] v = solveMatrix(m);

        // 相量模式下不做时域状态更新；同时保存幅值与完整相量
        double[] vd = new double[n];
        for (int i = 0; i < n; i++) vd[i] = v[i].abs();

        // 2026-08-21 伪时域充电（用户要求"DC/AC 都走相量，节拍达成伪时域"）：
        // 相量求解后提交电容/电感状态（vPrev/iPrev）——电容在 DC/低频用
        // Backward Euler 伴随（G=C/simDt + I_hist）逐节拍充电（大电流→指数
        // 衰减→存电，现实充电曲线）。AC 网络电容用 Y=jωC（不用 vPrev），
        // commit 更新状态无副作用（下轮 AC 分支仍不用 vPrev）。
        try {
            for (Element e : net.elements()) {
                int a = e.nodeA(), b = e.nodeB();
                if (a >= 0 && a < n && b >= 0 && b < n) {
                    e.commit(v[a].re, v[b].re, Math.max(net.dt, 1e-3));
                }
            }
        } catch (Throwable ignored) {
        }

        long nanos = System.nanoTime() - t0;
        SolveResult result = new SolveResult(vd, v, true, 1, nanos, SolveMode.COMPLEX_AC);
        // 端子测试点回填（2026-08-13 用户架构：求解后自动写端子电压，天然正确）
        TerminalRecorder.record(net, result);
        return result;
    }

    /** 复数矩阵一次求解：float SuperLU（SCZ）→ double SuperLU → 稠密 LU 回退。
     *  包内可见：供 {@link MultiToneSolver} 多频叠加复用（每频率一次求解）。 */
    static Complex[] solveMatrix(ComplexMnaBuilder m) {
        int n = m.size;
        int thr = NativeSparse.solverThreshold();
        boolean useSparse = NativeSparse.isLoaded() && (thr == 0 || n >= thr);
        // 配置 floatEnabled + native 含单精度 → 大网络走 float SuperLU（SCZ）
        boolean useFloatSparse = useSparse && Solvers.floatEnabled
                && NativeSparse.isSingleLoaded();
        if (useFloatSparse) {
            Complex[] v = solveSparseFloat(m);
            if (v == null) v = solveSparse(m);                       // 回退 double SuperLU
            if (v == null) v = DenseComplexLU.solve(m.toDense(), m.b); // 回退稠密
            return v;
        } else if (useSparse) {
            Complex[] v = solveSparse(m);
            if (v == null) v = DenseComplexLU.solve(m.toDense(), m.b); // 回退
            return v;
        }
        return DenseComplexLU.solve(m.toDense(), m.b);
    }

    /** SuperLU 单精度复数（SCZ，float）稀疏求解。失败返回 null（调用方回退）。 */
    static Complex[] solveSparseFloat(ComplexMnaBuilder m) {
        int n = m.size;
        ComplexMnaBuilder.Csc csc = m.toCsc();
        int nnz = csc.rowIdx.length;
        float[] re = new float[nnz], im = new float[nnz];
        for (int i = 0; i < nnz; i++) {
            re[i] = (float) csc.re[i];
            im[i] = (float) csc.im[i];
        }
        float[] rhsRe = new float[n], rhsIm = new float[n];
        float[] outRe = new float[n], outIm = new float[n];
        for (int i = 0; i < n; i++) {
            rhsRe[i] = (float) m.b[i].re;
            rhsIm[i] = (float) m.b[i].im;
        }
        try {
            int ok = NativeSparse.solveComplexFloat(n, nnz, csc.colPtr, csc.rowIdx,
                    re, im, rhsRe, rhsIm, outRe, outIm);
            if (ok != 1) return null;
            Complex[] v = new Complex[n];
            for (int i = 0; i < n; i++) v[i] = new Complex(outRe[i], outIm[i]);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** SuperLU 稀疏求解（CSC + double complex）。失败返回 null（调用方回退）。
     *  包内可见：供 {@link MergedNetworkSolver} 合并求解复用。 */
    static Complex[] solveSparse(ComplexMnaBuilder m) {
        int n = m.size;
        ComplexMnaBuilder.Csc csc = m.toCsc();
        double[] rhsRe = new double[n];
        double[] rhsIm = new double[n];
        for (int i = 0; i < n; i++) {
            rhsRe[i] = m.b[i].re;
            rhsIm[i] = m.b[i].im;
        }
        double[] outRe = new double[n];
        double[] outIm = new double[n];
        try {
            int ok = NativeSparse.solveComplex(n, csc.rowIdx.length, csc.colPtr, csc.rowIdx,
                    csc.re, csc.im, rhsRe, rhsIm, outRe, outIm);
            if (ok != 1) return null;
            Complex[] v = new Complex[n];
            for (int i = 0; i < n; i++) v[i] = new Complex(outRe[i], outIm[i]);
            return v;
        } catch (Throwable t) {
            return null; // 任何 native 异常 → 回退稠密
        }
    }
}
