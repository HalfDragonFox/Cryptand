package com.hdf.cryptand.circuitsimulation.core.node;

import com.hdf.cryptand.circuitsimulation.compute.DirectSolveNode;
import com.hdf.cryptand.circuitsimulation.compute.SolveNodeContext;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Set;

/**
 * 储能电荷同步节点（2026-08-19 节点化求解：能量计算节点）。
 *
 * <p>依赖电路矩阵节点，在矩阵解出端口相量电压后对储能复合元件
 * （电容/电池等 {@link EnergyDevice}）执行 {@link EnergyDevice#syncCharge}
 * ——把时间相关变量（电荷）绑定到端口电压，相量求不出时由电荷状态驱动电压。
 */
public final class EnergyNode implements DirectSolveNode {

    private final Network net;

    public EnergyNode(Network net) {
        this.net = net;
    }

    @Override
    public String id() {
        return "energy";
    }

    @Override
    public Set<String> dependsOn() {
        return Set.of(MnaNode.ID);
    }

    @Override
    public int estimateCost() {
        int c = 0;
        for (CompositeElement ce : net.composites()) if (ce instanceof EnergyDevice) c++;
        return c;
    }

    @Override
    public void execute(SolveNodeContext ctx) {
        SolveResult r = ctx.resultOf(MnaNode.ID);
        if (r == null || r.complex == null) return;
        Complex[] v = r.complex;
        int n = net.nodeCount();
        for (CompositeElement ce : net.composites()) {
            if (!(ce instanceof EnergyDevice ed)) continue;
            try {
                int a = ed.nodeA(), b = ed.nodeB();
                Complex va = (a >= 0 && a < n) ? v[a] : null;
                Complex vb = (b >= 0 && b < n) ? v[b] : null;
                ed.syncCharge(va, vb);
            } catch (Throwable ignored) {
            }
        }
    }
}
