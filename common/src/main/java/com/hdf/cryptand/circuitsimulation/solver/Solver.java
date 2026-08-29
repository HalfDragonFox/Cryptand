package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;

/**
 * 求解器接口 —— 分布式调度的核心抽象。
 * 未来 C++/GPU/集群后端实现此接口，Java 侧只需选择实现即可无缝切换。
 */
public interface Solver {
    SolveMode mode();

    /** 求解一个网络，返回节点电压。不会修改网络拓扑，但会更新元件内部状态（伴随模型）。 */
    SolveResult solve(Network net);
}
