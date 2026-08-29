package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 直算节点：不参与矩阵合成，直接在 {@link SolveNodeContext} 上推进显式状态。
 *
 * <p>2026-08-19：温度解析解推进、电荷同步、结果后处理回填等
 * 无需求解器、只依赖已求得的节点电压的运算，全部归为直算节点，
 * 通过 {@link #dependsOn()} 依赖对应矩阵节点保证先解后推进。
 */
public interface DirectSolveNode extends SolveNode {

    @Override
    default SolveNodeKind kind() {
        return SolveNodeKind.DIRECT;
    }

    /** 执行显式推进 / 后处理。 */
    void execute(SolveNodeContext ctx);
}
