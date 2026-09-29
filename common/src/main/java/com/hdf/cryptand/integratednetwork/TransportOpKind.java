package com.hdf.cryptand.integratednetwork;

/**
 * 传输网络操作类型（2026-08-26 集成网络核心）。
 * <p>
 * 优先级（高 → 低）：{@link #DESTROY} &gt; {@link #TOPOLOGY} &gt; {@link #ROUTE}
 * &gt; {@link #TICK}。传输操作类（{@link TransportOperation}）按本优先级执行
 * 整合后的操作。
 */
public enum TransportOpKind {

    /** 网络拆合（类仿真引擎 SPLIT_MERGE；最高优先——先定网络边界再动结构/传输） */
    SPLIT_MERGE,

    /** 拓扑破坏（移除节点/边；次高——先拆除再变更，防残留路由） */
    DESTROY,

    /** 拓扑变更（增/改节点、边；data = TransportChange 或 List&lt;TransportChange&gt;） */
    TOPOLOGY,

    /** 变化上报（接口/容器及参数变化；data = NetworkReport 或 List&lt;NetworkReport&gt;） */
    REPORT,

    /** 重算路由表（可选显式触发；拓扑脏时 TICK 会自动重算） */
    ROUTE,

    /** 传输请求（多对多分配；data = TransportTransferRequest；先出结算事件再推进） */
    TRANSFER,

    /** 推进流动（data = Integer 帧数，可合并累加；最低优先——先定结构再跑） */
    TICK
}