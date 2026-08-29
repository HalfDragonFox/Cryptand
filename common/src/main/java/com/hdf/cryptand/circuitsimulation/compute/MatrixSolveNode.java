package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

/**
 * 矩阵装配节点：把自己的子矩阵 stamp 进块对角构建器，由全局求解器
 * 一次性求解，再从全局结果中取回本块（offset 切分）。
 *
 * <p>2026-08-19：这是「矩阵化后送给最终求解器」的核心形态——
 * 电路 MNA、热网络隐式矩阵均可实现本接口；块对角合成复用
 * {@code MergedNetworkSolver} 已验证的 {@link ComplexMnaBuilder#offset} 机制。
 */
public interface MatrixSolveNode extends SolveNode {

    @Override
    default SolveNodeKind kind() {
        return SolveNodeKind.MATRIX;
    }

    /**
     * 是否可参与块对角合成。true = 单频 AC 相量（同频子网可并入同一矩阵）；
     * false = DC 实数 / 非线性迭代 / 多频，必须独立求解。
     */
    boolean blockEligible();

    /** 子矩阵大小（本块节点数，不含外部）。 */
    int blockSize();

    /**
     * 装配本块子矩阵。pipeline 已设置 {@code m.offset} 为本块全局偏移，
     * 节点内直接用自身局部节点号 stamp（addY/addB/clearRowCol 自动加偏移）。
     *
     * @param m     全局块对角构建器（offset 已指向本块）
     * @param omega 求解角频率
     */
    void stampBlock(ComplexMnaBuilder m, double omega);

    /**
     * 全局求解完成后取回本块结果。
     *
     * @param global 全局相量数组（总大小）
     * @param offset 本块全局偏移
     */
    void applyBlock(Complex[] global, int offset);

    /**
     * 不参与块对角合成时的独立求解路径（DC / 非线性 / 多频回退）。
     * 内部负责选择求解器并返回完整结果。
     */
    SolveResult directSolve();
}
