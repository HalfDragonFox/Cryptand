package com.hdf.cryptand.circuitsimulation.core;

import com.hdf.cryptand.circuitsimulation.compute.SolveGraph;
import com.hdf.cryptand.circuitsimulation.compute.SolvePipeline;
import com.hdf.cryptand.circuitsimulation.compute.SolvePipeline.SolvePipelineResult;
import com.hdf.cryptand.circuitsimulation.core.node.MnaNode;
import com.hdf.cryptand.circuitsimulation.core.node.PostNode;
import com.hdf.cryptand.circuitsimulation.core.node.StateNode;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod;

/**
 * 网络分解器（2026-08-19 节点化求解）：把单个 {@link Network} 拆分为
 * {@link SolveNode} 图。
 *
 * <p>对外接口仍然只传网络对象（SimulationCore API 不变），分解在内部完成：
 * <pre>
 *   Network
 *     ├─ MnaNode   电路 MNA 矩阵（blockEligible → 块对角合成）
 *     ├─ StateNode 统一状态推进（依赖 mna）：温度/电荷/机械/应力等
 *     │            ——所有实现 StateDriven 的复合元件，无需新增节点
 *     └─ PostNode  端子回填（依赖 mna，仅当存在端子）
 * </pre>
 * 2026-08-20 统一：ThermalNode + EnergyNode 合并为 StateNode（StateDriven
 * 是 EnergyState 超集，任何状态模型都走同一节点）。是否挂载 post 节点按
 * 网络内容自动判定，避免空节点浪费。
 */
public final class NetworkDecomposer {

    private final SolvePipeline pipeline;

    /** 非线性相量求解方法（透传给 MnaNode；由上层配置写入） */
    public volatile PhasorNonlinearMethod nonlinearMethod = PhasorNonlinearMethod.NONE;

    public NetworkDecomposer() {
        this(new SolvePipeline());
    }

    public NetworkDecomposer(SolvePipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** 访问内部流水线（可调阈值）。 */
    public SolvePipeline pipeline() {
        return pipeline;
    }

    /** 分解网络 → 节点图（含 DAG 校验）。 */
    public SolveGraph decompose(Network net) {
        SolveGraph graph = new SolveGraph();
        MnaNode mna = new MnaNode(net);
        mna.nonlinearMethod = nonlinearMethod;
        graph.addNode(mna);
        graph.addNode(new StateNode(net));
        if (!net.terminals().isEmpty()) graph.addNode(new PostNode(net));
        return graph;
    }

    /** 分解并执行（同步 API）：网络 → 节点图 → 流水线 → 主结果。 */
    public SolvePipelineResult run(Network net) {
        SolveGraph graph = decompose(net);
        double omega = 2 * Math.PI * net.dominantFrequency();
        return pipeline.execute(graph, net, omega, net.dt, System.nanoTime());
    }
}
