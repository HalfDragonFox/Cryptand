package com.hdf.cryptand.integratednetwork;

/**
 * 传输边（2026-08-26 集成网络核心）：连接两个节点的一条通道
 * （管道段 / 传送带段 / 无线链路 / 电缆段等）。
 * <p>
 * 通用边属性同时覆盖"物流管道"与"无线电"两类场景：
 * <ul>
 *   <li>{@link #throughput} 每仿真步通行量上限（管道/带宽）；</li>
 *   <li>{@link #latencyTicks} 在途耗时（管道搬运时间 / 无线传播+处理延迟）；</li>
 *   <li>{@link #loss} 丢包率 0..1（无线链路衰减；管道为 0）；</li>
 *   <li>{@link #cost} 路由代价（Dijkstra 求最短路径的权重）。</li>
 * </ul>
 * 不可变；核心只在异步线程访问本对象。
 */
public final class TransportEdge {

    /** 边 id（图内唯一，由 {@link TransportGraph#nextEdgeId()} 分配） */
    public final long id;
    /** 端点节点 id */
    public final Object from, to;
    /** 通道类型（负载通行过滤：物品管道 vs 无线链路） */
    public final TransferType type;
    /** 每仿真步通行量上限（单位；&lt;=0 = 无限制） */
    public final double throughput;
    /** 在途耗时（仿真步数；0 = 下步可达） */
    public final int latencyTicks;
    /** 丢包率（0..1；>0 模拟无线/不可靠链路） */
    public final double loss;
    /** 路由代价（最短路径权重；默认 1） */
    public final double cost;

    public TransportEdge(long id, Object from, Object to, TransferType type,
                         double throughput, int latencyTicks, double loss, double cost) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.type = type;
        this.throughput = Math.max(0.0, throughput);
        this.latencyTicks = Math.max(0, latencyTicks);
        this.loss = Math.max(0.0, Math.min(1.0, loss));
        this.cost = Math.max(0.0, cost);
    }

    /** 本边是否可运指定类型负载 */
    public boolean accepts(TransferType payloadType) {
        return TransferType.compatible(type, payloadType);
    }

    @Override
    public String toString() {
        return "TransportEdge{id=" + id + ", " + from + "->" + to + ", type=" + type
                + ", tp=" + throughput + ", lat=" + latencyTicks + ", loss=" + loss + "}";
    }
}