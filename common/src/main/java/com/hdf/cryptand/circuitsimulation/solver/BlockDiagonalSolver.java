package com.hdf.cryptand.circuitsimulation.solver;

/**
 * 块对角合成求解入口（solver 层能力，供 compute 层 SolvePipeline 调用）。
 *
 * <p>2026-08-19：把多个同频 AC 子网的 {@link ComplexMnaBuilder} 子矩阵
 * （通过 offset 已装配进同一全局构建器）一次性求解，返回全局相量数组。
 * 内部走 {@link ComplexMnaSolver#solveMatrix}（稀疏 SuperLU → 稠密回退），
 * 与 {@code MergedNetworkSolver} 复用同一求解路径。
 */
public final class BlockDiagonalSolver {

    private BlockDiagonalSolver() {
    }

    /** 求解全局块对角矩阵，返回按全局坐标索引的相量数组。 */
    public static Complex[] solve(ComplexMnaBuilder global) {
        return ComplexMnaSolver.solveMatrix(global);
    }
}
