package com.hdf.cryptand.integratednetwork;

/**
 * 传输节点（2026-08-26 集成网络核心）：输入口 / 输出口 / 中转站点的纯虚拟表示。
 * <p>
 * 只含元数据（id / 类型 / 位置 / 缓冲容量）；真实缓冲状态由
 * {@link TransportGraph} 管理。核心在异步线程操作本对象，不碰任何 MC 对象。
 */
public final class TransportNode {

    /** 节点 id（平台层键：区块坐标 / 逻辑端点 id 等） */
    public final Object id;
    /** 节点类型（通道过滤用；Tor 端点可用 GENERIC） */
    public final TransferType type;
    /** 位置（世界坐标；核心内仅作路由/可视化元数据，不参与运算） */
    public final double x, y, z;
    /** 缓冲存量上限（单位；≤0 = 无上限） */
    public final double capacity;

    public TransportNode(Object id, TransferType type, double x, double y, double z, double capacity) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.capacity = capacity;
    }

    @Override
    public String toString() {
        return "TransportNode{id=" + id + ", type=" + type + ", pos=(" + x + "," + y + "," + z
                + "), cap=" + capacity + "}";
    }
}