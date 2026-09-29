package com.hdf.cryptand.circuitsimulation.core.node;

import com.hdf.cryptand.circuitsimulation.compute.DirectSolveNode;
import com.hdf.cryptand.circuitsimulation.compute.SolveNodeContext;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyState;
import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Set;

/**
 * 统一状态推进节点（2026-08-20 用户要求：新增模型无需新增计算节点）。
 * <p>
 * 求解后推进【所有】状态驱动模型（StateDriven）——温度（ThermalModel
 * 损耗→温度）、储能电荷（EnergyDevice.syncCharge）、机械转速/应力等
 * 任何实现 {@link StateDriven} 的复合元件。替代旧 ThermalNode + EnergyNode
 * 的按类型分工：新增模型只需实现 StateDriven，无需新增节点。
 * <p>
 * 兼容：StateDriven 是 EnergyState 的超集——复合元件若同时实现
 * EnergyDevice，则额外调 syncCharge 同步电荷（保持 EnergyNode 行为）。
 */
public final class StateNode implements DirectSolveNode {

    public static final String ID = "state";

    private final Network net;

    public StateNode(Network net) {
        this.net = net;
    }

    @Override
    public String id() {
        return ID;
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
        if (r == null || r.complex == null) return;
        double omega = 2 * Math.PI * net.dominantFrequency();
        double freqHz = net.dominantFrequency();
        int n = net.nodeCount();
        Complex[] v = r.complex;
        // 当前求解器类型（统一接口参数）：DC → REAL_DC；AC → COMPLEX_AC
        com.hdf.cryptand.circuitsimulation.solver.SolveMode mode =
                net.frequency <= 0 ? com.hdf.cryptand.circuitsimulation.solver.SolveMode.REAL_DC
                        : com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC;
        boolean anyChanging = false;
        for (CompositeElement c : net.composites()) {
            try {
                // 2026-08-20 修复"5A 电流导线 300°C"（双推进温度）：导线段
                // 温度由统一发热（computeWireHeatOne）单独推进（负责烧毁检测），
                // 状态节点跳过——否则同段导线被推进两次 → 温度翻倍累积。
                if (c instanceof com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) {
                    continue;
                }
                int a = c.nodeA(), b = c.nodeB();
                Complex va = (a >= 0 && a < n) ? v[a] : null;
                Complex vb = (b >= 0 && b < n) ? v[b] : null;
                // 统一状态推进：任何 StateDriven 复合元件（温度/机械/应力…）
                if (c instanceof StateDriven sd) {
                    sd.advanceState(va, vb, freqHz, ctx.simDt, mode);
                } else {
                    // 兼容旧路径：非 StateDriven 但有温度 → 走 update
                    // （lossPower 功耗 → ThermalModel 推进）
                    c.update(va, vb, omega, ctx.simDt); // 仿真步长（统一）
                }
                // 储能电荷同步（EnergyDevice 额外动作，保持 EnergyNode 行为）
                if (c instanceof EnergyDevice ed) {
                    ed.syncCharge(va, vb);
                }
                // 稳态跟踪（2026-08-20）：任一状态模型仍在变化 → 网络需继续求解
                if (c.stateChanging()) anyChanging = true;
            } catch (Throwable ignored) {
                // 单元件推进失败不炸整个网络（与旧路径一致）
            }
        }
        // 状态稳定标记（buildPending 据此跳过稳态网络）
        net.stateSettled = !anyChanging;
    }
}
