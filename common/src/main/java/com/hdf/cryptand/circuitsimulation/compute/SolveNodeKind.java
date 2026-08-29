package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 求解节点形态（ECS 风格节点分类）。
 *
 * 2026-08-19 节点化求解架构：
 *   - {@link #MATRIX}  矩阵装配节点：把自己的子矩阵 stamp 进块对角构建器，
 *                      最终由全局求解器一次性求解（电路 MNA、热网络隐式矩阵）
 *   - {@link #DIRECT}  直算节点：不参与矩阵合成，直接推进显式状态
 *                      （温度解析解 / 电荷同步 / 后处理回填）
 *
 * 同一网络内节点按 {@code dependsOn()} 构成 DAG；节点数低于阈值时
 * 同线程串行（零开销），超过阈值才交给 {@code ThreadDispatchers} 异步并行。
 */
public enum SolveNodeKind {
    /** 矩阵装配节点（stampBlock → 全局块对角求解 → applyBlock 取回） */
    MATRIX,
    /** 直算节点（execute 直接推进状态） */
    DIRECT
}
