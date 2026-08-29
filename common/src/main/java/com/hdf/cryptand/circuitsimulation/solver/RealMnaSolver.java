package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.elements.SemiconductorElement;

/**
 * 实数（DC/时域）MNA 求解器。
 * 流程：装配 → 接地固定 → LU 求解 → 元件 commit 更新状态。
 * <p>
 * 【工作点迭代，2026-08-12 用户要求】混合电路（线性 + 非线性半导体）：
 * 非线性元件被当作线性元件（当前工作点线性化，内部模拟非线性特征），
 * 同一时间步内迭代——stamp（用当前工作点）→ LU 求解 → 更新半导体工作点
 * （vPrev/vControlPrev）→ 重新 stamp 直到收敛（阻尼，最多 MAX_ITER 次）。
 * 纯线性网络（无半导体）单次求解零开销。
 */
public class RealMnaSolver implements Solver {

    /** 防奇异 GMin（SPICE 风格）：孤立节点/浮空回路的最小导纳到地 */
    private static final double GMIN = 1e-7;

    /** 工作点迭代最大次数（含半导体网络；阻尼防振荡） */
    private static final int MAX_ITER = 5;

    /** 工作点收敛阈值（V：vPrev/vControlPrev 变化小于此值即收敛） */
    private static final double CONV_EPS = 1e-4;

    @Override
    public SolveMode mode() { return SolveMode.REAL_DC; }

    @Override
    public SolveResult solve(Network net) {
        // 2026-08-20 求解器原生开路支持：stamp 前标记开路电流源
        Solvers.markOpenCurrentSources(net);
        long t0 = System.nanoTime();
        int n = net.nodeCount();
        int g = net.groundNode;

        // 检测是否含非线性半导体元件（含 → 工作点迭代）
        boolean hasSemi = false;
        for (Element e : net.elements()) {
            if (e instanceof SemiconductorElement) { hasSemi = true; break; }
        }

        double[] v = null;
        int iter = 0;
        for (; iter < (hasSemi ? MAX_ITER : 1); iter++) {
            MnaBuilder m = new MnaBuilder(n);
            for (Element e : net.elements()) e.stampRealAt(m, net.dt, net.time);

            // 固定接地节点：行/列清零，对角线置 1，b 置 0
            if (g >= 0 && g < n) {
                for (int j = 0; j < n; j++) { m.g[g][j] = 0; m.g[j][g] = 0; }
                m.g[g][g] = 1;
                m.b[g] = 0;
            }

            // GMin 兜底：孤立节点（无元件连接）会让矩阵奇异 → 每个非参考节点
            // 对角加小导纳到地（远小于真实元件导纳，对差分电压影响可忽略）
            for (int i = 0; i < n; i++) {
                if (i == g) continue;
                m.g[i][i] += GMIN;
            }

            v = DenseRealLU.solve(m.g, m.b);

            if (hasSemi) {
                // 更新半导体工作点（本次解 → vPrev/vControlPrev），检测收敛
                double maxDelta = 0;
                for (Element e : net.elements()) {
                    if (e instanceof SemiconductorElement se) {
                        maxDelta = Math.max(maxDelta, se.updateOperatingPoint(v));
                    }
                }
                if (iter > 0 && maxDelta < CONV_EPS) {
                    iter++; // 计入本次
                    break; // 工作点收敛
                }
            }
        }

        // 更新元件历史状态
        for (Element e : net.elements()) {
            double va = v[e.nodeA()];
            double vb = v[e.nodeB()];
            e.commit(va, vb, net.dt);
        }

        long nanos = System.nanoTime() - t0;
        SolveResult result = new SolveResult(v, true, iter, nanos, SolveMode.REAL_DC);
        // 端子测试点回填（2026-08-13 用户架构：求解后自动写端子电压，天然正确）
        TerminalRecorder.record(net, result);
        return result;
    }
}
