package com.hdf.cryptand.integratednetwork;

/**
 * 拓扑变更指令（2026-08-26 集成网络核心）：主线程 → 核心的建网/拆网最小指令。
 * <p>
 * 不可变；作为 {@link IntegratedNetworkCore#submitTopology} /
 * {@link IntegratedNetworkCore#submitDestroy} 的 data 载荷（可传单条或
 * {@code List<TransportChange>}）。核心线程按指令在纯虚拟图上执行，
 * 主线程零计算。
 */
public final class TransportChange {

    /** 变更类型 */
    public enum Kind {
        /** 新增节点 */
        ADD_NODE,
        /** 移除节点（连带其边与缓冲） */
        REMOVE_NODE,
        /** 新增边（id 由核心自动分配） */
        ADD_EDGE,
        /** 移除边 */
        REMOVE_EDGE
    }

    /** 变更类型 */
    public final Kind kind;
    /** 节点 id / 边 id */
    public final Object id;
    /** 边的两个端点节点 id（ADD_EDGE） */
    public final Object a, b;
    /** 节点/边类型（通道过滤） */
    public final TransferType type;
    /** 节点坐标（ADD_NODE：{x, y, z}） */
    public final double[] pos;
    /** 节点缓冲容量 */
    public final double capacity;
    /** 边通行量（/步） */
    public final double throughput;
    /** 边在途耗时（步） */
    public final int latencyTicks;
    /** 边丢包率 */
    public final double loss;
    /** 边路由代价 */
    public final double cost;

    private TransportChange(Kind kind, Object id, Object a, Object b, TransferType type,
                            double[] pos, double capacity, double throughput,
                            int latencyTicks, double loss, double cost) {
        this.kind = kind;
        this.id = id;
        this.a = a;
        this.b = b;
        this.type = type;
        this.pos = pos;
        this.capacity = capacity;
        this.throughput = throughput;
        this.latencyTicks = latencyTicks;
        this.loss = loss;
        this.cost = cost;
    }

    /** 新增节点（capacity &lt;= 0 = 无限制） */
    public static TransportChange addNode(Object id, TransferType type,
                                          double x, double y, double z, double capacity) {
        return new TransportChange(Kind.ADD_NODE, id, null, null, type,
                new double[]{x, y, z}, capacity, 0, 0, 0, 0);
    }

    /** 移除节点 */
    public static TransportChange removeNode(Object id) {
        return new TransportChange(Kind.REMOVE_NODE, id, null, null, null,
                null, 0, 0, 0, 0, 0);
    }

    /** 新增边（id 核心分配；loss 丢包率、cost 路由代价） */
    public static TransportChange addEdge(Object a, Object b, TransferType type,
                                          double throughput, int latencyTicks,
                                          double loss, double cost) {
        return new TransportChange(Kind.ADD_EDGE, null, a, b, type,
                null, 0, throughput, latencyTicks, loss, cost);
    }

    /** 移除边 */
    public static TransportChange removeEdge(long edgeId) {
        return new TransportChange(Kind.REMOVE_EDGE, edgeId, null, null, null,
                null, 0, 0, 0, 0, 0);
    }

    @Override
    public String toString() {
        return "TransportChange{" + kind + " id=" + id + (kind == Kind.ADD_EDGE
                ? " " + a + "->" + b + " tp=" + throughput + " lat=" + latencyTicks
                    + " loss=" + loss + " cost=" + cost
                : "") + "}";
    }
}