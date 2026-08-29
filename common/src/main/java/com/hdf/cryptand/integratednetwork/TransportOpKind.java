package com.hdf.cryptand.integratednetwork;

/**
 * 传输网络操作类型（2026-08-26 集成网络核心）。
 * <p>
 * 优先级（高 → 低）：{@link #DESTROY} &gt; {@link #TOPOLOGY} &gt; {@link #ROUTE}
 * &gt; {@link #TICK}。传输操作类（{@link TransportOperation}）按本优先级执行
 * 整合后的操作。
 */
public enum TransportOpKind {

    /** 拓扑破坏（移除节点/边；最高优先——先拆除再变更，防残留路由） */
    DESTROY,

    /** 拓扑变更（增/改节点、边；data = TransportChange 或 List&lt;TransportChange&gt;） */
    TOPOLOGY,

    /** 重算路由表（可选显式触发；拓扑脏时 TICK 会自动重算） */
    ROUTE,

    /** 推进流动（data = Integer 帧数，可合并累加；最低优先——先定结构再跑） */
    TICK
}