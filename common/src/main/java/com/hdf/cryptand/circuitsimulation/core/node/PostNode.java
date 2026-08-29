package com.hdf.cryptand.circuitsimulation.core.node;

import com.hdf.cryptand.circuitsimulation.compute.DirectSolveNode;
import com.hdf.cryptand.circuitsimulation.compute.SolveNodeContext;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Set;

/**
 * 端子回填后处理节点（2026-08-19 节点化求解：结果后处理节点）。
 *
 * <p>依赖电路矩阵节点，求解完成后把端子节点电压回填到
 * {@link TerminalElement}（对应 neoforge TerminalRecorder 的 common 版）：
 * 悬空端因内部元件 MNA 等电位 → 回填即正确值，不做任何强制 hack。
 *
 * <p>DC 模式回填瞬时值（re=voltage, im=0, frequency=0）；
 * AC 模式回填幅值 + 完整相量 + 主导频率。
 */
public final class PostNode implements DirectSolveNode {

    private final Network net;

    public PostNode(Network net) {
        this.net = net;
    }

    @Override
    public String id() {
        return "post";
    }

    @Override
    public Set<String> dependsOn() {
        return Set.of(MnaNode.ID);
    }

    @Override
    public int estimateCost() {
        return net.terminals().size();
    }

    @Override
    public void execute(SolveNodeContext ctx) {
        SolveResult r = ctx.resultOf(MnaNode.ID);
        if (r == null) return;
        double freq = net.dominantFrequency();
        int n = net.nodeCount();
        for (TerminalElement t : net.terminals()) {
            try {
                int idx = t.engineNode;
                if (idx < 0 || idx >= n) continue;
                double v = r.voltages[idx];
                if (r.complex != null && idx < r.complex.length) {
                    Complex c = r.complex[idx];
                    t.record(v, c.re, c.im, freq);
                } else {
                    t.record(v, v, 0.0, 0.0);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
