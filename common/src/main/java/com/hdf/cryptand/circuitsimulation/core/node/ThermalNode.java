package com.hdf.cryptand.circuitsimulation.core.node;

import com.hdf.cryptand.circuitsimulation.compute.DirectSolveNode;
import com.hdf.cryptand.circuitsimulation.compute.SolveNodeContext;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Set;

/**
 * 温度直算节点（2026-08-19 节点化求解：温度计算节点）。
 *
 * <p>依赖电路矩阵节点（{@link MnaNode#ID}），在矩阵解出端口相量电压后
 * 推进复合元件能量/温度状态（{@link CompositeElement#update}——内含
 * 单变量 ODE 解析解推进 + 功耗 EMA 平滑 + 散热）。对应旧
 * CoreNetOpExecutor.solve() 的「求解器链条后处理」段，拆为独立节点后可
 * 与其它直算节点并行。
 */
public final class ThermalNode implements DirectSolveNode {

    private final Network net;

    public ThermalNode(Network net) {
        this.net = net;
    }

    @Override
    public String id() {
        return "thermal";
    }

    @Override
    public Set<String> dependsOn() {
        return Set.of(MnaNode.ID);
    }

    @Override
    public int estimateCost() {
        return net.composites().size();
    }

    @Override
    public void execute(SolveNodeContext ctx) {
        SolveResult r = ctx.resultOf(MnaNode.ID);
        if (r == null) return;
        double omega = 2 * Math.PI * net.dominantFrequency();
        int n = net.nodeCount();
        for (CompositeElement c : net.composites()) {
            try {
                int a = c.nodeA(), b = c.nodeB();
                if (a < 0 || b < 0) {
                    c.update(null, null, omega, ctx.nowNanos);
                    continue;
                }
                Complex va = a < n ? r.voltageAtComplex(net.node(a)) : null;
                Complex vb = b < n ? r.voltageAtComplex(net.node(b)) : null;
                c.update(va, vb, omega, ctx.nowNanos);
            } catch (Throwable ignored) {
                // 单元件推进失败不炸整个网络（与旧路径一致）
            }
        }
    }
}
