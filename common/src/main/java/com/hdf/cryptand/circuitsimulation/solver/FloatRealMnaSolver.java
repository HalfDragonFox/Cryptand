package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;

/**
 * float 版实数（DC/时域）MNA 求解器。
 * <p>
 * 求解热路径 float（内存/缓存/SIMD 收益）；魔法数字由 double 计算后转 float
 * （Element.stampRealFloat）。仅处理【纯线性】网络（含半导体 → 由
 * {@link Solvers} 能力检测自动回退 double RealMnaSolver 的工作点迭代）。
 */
public class FloatRealMnaSolver implements Solver {

    /** 防奇异 GMin（SPICE 风格，float） */
    private static final float GMIN = 1e-7f;

    @Override
    public SolveMode mode() {
        return SolveMode.REAL_DC;
    }

    @Override
    public SolveResult solve(Network net) {
        // 2026-08-20 求解器原生开路支持：stamp 前标记开路电流源
        Solvers.markOpenCurrentSources(net);
        long t0 = System.nanoTime();
        // ⚠ 2026-08-30 审计 U3 根因：Backward Euler 瞬态元件要求 dt>0——
        // dt=0（SimClock 首次基准/节流窗口）表示时间未流逝 → 无瞬态可解。
        // 根因契约：dt<=0 时不执行瞬态求解（状态不变），不打 dt 下限。
        if (net.dt <= 0) {
            long tz = System.nanoTime() - t0;
            return new SolveResult(new double[Math.max(net.nodeCount(), 1)],
                    true, 0, tz, SolveMode.REAL_DC);
        }
        int n = net.nodeCount();
        int g = net.groundNode;

        FloatMnaBuilder m = new FloatMnaBuilder(n);
        for (Element e : net.elements()) {
            e.stampRealFloat(m, net.dt, net.time);
        }

        // 固定接地节点：行/列清零，对角线置 1，b 置 0
        if (g >= 0 && g < n) {
            for (int j = 0; j < n; j++) {
                m.g[g][j] = 0;
                m.g[j][g] = 0;
            }
            m.g[g][g] = 1;
            m.b[g] = 0;
        }
        // GMin 兜底：孤立节点防奇异
        for (int i = 0; i < n; i++) {
            if (i == g) continue;
            m.g[i][i] += GMIN;
        }

        float[] v = DenseFloatLU.solve(m.g, m.b);

        // 更新元件历史状态（float 电压）
        double[] vd = new double[n];
        for (int i = 0; i < n; i++) vd[i] = v[i];
        for (Element e : net.elements()) {
            e.commitFloat(v[e.nodeA()], v[e.nodeB()], net.dt);
        }

        long nanos = System.nanoTime() - t0;
        SolveResult result = new SolveResult(vd, true, 1, nanos, SolveMode.REAL_DC);
        // 端子测试点回填（2026-08-13 用户架构：求解后自动写端子电压，天然正确）
        TerminalRecorder.record(net, result);
        return result;
    }
}
