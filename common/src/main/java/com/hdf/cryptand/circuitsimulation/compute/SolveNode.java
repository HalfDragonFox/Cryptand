package com.hdf.cryptand.circuitsimulation.compute;

import java.util.Set;

/**
 * 求解节点——ECS 风格「最基础运算求解节点」的统一抽象。
 *
 * <p>2026-08-19 节点化求解架构核心接口。设计目标：
 * <pre>
 *   对外只传 Network 对象（SimulationCore 现有 API 不变）
 *        │
 *        ▼
 *   NetworkDecomposer 拆分为 SolveNode 图（电路 / 温度 / 能量 / 后处理）
 *        │
 *        ▼
 *   SolvePipeline：依赖分析(DAG) → 阈值判断（串行 / 异步并行）
 *        │
 *        ▼
 *   矩阵类节点子矩阵块对角合成 → 全局求解器一次求解
 *   直算类节点按依赖层推进显式状态
 * </pre>
 *
 * <p>三种形态见 {@link SolveNodeKind}；节点间通过 {@link #dependsOn()}
 * 声明依赖（结果依赖），由 {@link SolveGraph} 校验无环并给出拓扑执行层。
 */
public interface SolveNode {

    /** 节点唯一 id（同一 SolveGraph 内不重复）。 */
    String id();

    /** 依赖的其它节点 id（结果依赖，构成 DAG；无依赖返回空集合）。 */
    Set<String> dependsOn();

    /** 估算代价（节点数 / 元件数量级），用于调度与阈值统计。 */
    int estimateCost();

    /** 节点形态：矩阵装配 or 直算。 */
    SolveNodeKind kind();
}
