package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Map;

/**
 * 节点执行上下文：pipeline 在运行期注入，供直算节点读取
 * 网络、仿真时钟与矩阵阶段结果。
 *
 * <p>2026-08-19：同一 SolveGraph 内矩阵节点全部完成后才进入直算阶段，
 * 因此 {@link #result} 与 {@link #nodeResults} 必定可用
 * （依赖未满足的节点会被 DAG 层排序拦下）。
 */
public final class SolveNodeContext {

    /** 被求解的网络（只读）。 */
    public final Network network;

    /** 本次推进步长（秒）。 */
    public final double simDt;

    /** 矩阵阶段产出的本网络主结果（相量 / 幅值数组，按节点索引）。 */
    public final SolveResult result;

    /** 仿真时钟（纳秒，来自 SimClock）。 */
    public final long nowNanos;

    /** 全部节点结果（矩阵节点必填；直算节点可读依赖节点的结果）。 */
    public final Map<String, SolveResult> nodeResults;

    public SolveNodeContext(Network network, double simDt, SolveResult result,
                            long nowNanos, Map<String, SolveResult> nodeResults) {
        this.network = network;
        this.simDt = simDt;
        this.result = result;
        this.nowNanos = nowNanos;
        this.nodeResults = nodeResults;
    }

    /** 便捷访问：节点 i 的完整相量（AC 模式），DC 模式返回 null。 */
    public Complex complexAt(int nodeId) {
        return result == null || result.complex == null ? null : result.complex[nodeId];
    }

    /** 便捷访问：节点 i 的幅值 / 瞬时电压。 */
    public double voltageAt(int nodeId) {
        return result == null ? 0.0 : result.voltages[nodeId];
    }

    /** 按节点 id 取结果（依赖节点专用）。 */
    public SolveResult resultOf(String nodeId) {
        return nodeResults.get(nodeId);
    }
}
