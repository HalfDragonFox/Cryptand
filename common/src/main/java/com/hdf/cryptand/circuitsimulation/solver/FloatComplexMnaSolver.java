package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;

/**
 * float 版复数（AC 相量）MNA 求解器。
 * <p>
 * 求解热路径 float（内存/缓存/SIMD 收益）；魔法数字由 double 计算后转 float
 * （Element.stampComplexFloat）。用于小/中网络稠密 LU；大网络合并求解仍走
 * native SuperLU（double complex，见 MergedNetworkSolver）。
 * 结果转回 double（SolveResult 兼容：幅值 + 完整相量）。
 */
public class FloatComplexMnaSolver implements Solver {

    /** 防奇异 GMin（SPICE 风格，float） */
    private static final float GMIN = 1e-7f;

    @Override
    public SolveMode mode() {
        return SolveMode.COMPLEX_AC;
    }

    @Override
    public SolveResult solve(Network net) {
        // 2026-08-20 求解器原生开路支持：stamp 前标记开路电流源
        Solvers.markOpenCurrentSources(net);
        long t0 = System.nanoTime();
        int n = net.nodeCount();
        int g = net.groundNode;
        // 伪时域：DC(0Hz) 用等效小频率（与 ComplexMnaSolver 一致，避免电感除零）
        double omega = 2 * Math.PI * Math.max(net.frequency,
                com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver.DC_EQUIV_FREQ_HZ);

        FloatComplexMnaBuilder m = new FloatComplexMnaBuilder(n);
        for (Element e : net.elements()) {
            e.stampComplexFloat(m, omega);
        }

        // 固定接地节点
        if (g >= 0 && g < n) {
            m.clearRowCol(g);
            m.addY(g, g, new FloatComplex(1, 0));
            m.b[g] = FloatComplex.ZERO;
        }
        // GMin 兜底
        for (int i = 0; i < n; i++) {
            if (i == g) continue;
            m.addY(i, i, new FloatComplex(GMIN, 0));
        }

        FloatComplex[] v = DenseFloatComplexLU.solve(m.toDense(), m.b);

        // 相量模式：不做时域状态更新；保存幅值与完整相量
        double[] vd = new double[n];
        Complex[] vc = new Complex[n];
        for (int i = 0; i < n; i++) {
            vd[i] = v[i].abs();
            vc[i] = v[i].toDouble();
        }

        long nanos = System.nanoTime() - t0;
        SolveResult result = new SolveResult(vd, vc, true, 1, nanos, SolveMode.COMPLEX_AC);
        // 端子测试点回填（2026-08-13 用户架构：求解后自动写端子电压，天然正确）
        TerminalRecorder.record(net, result);
        return result;
    }
}
