package com.hdf.cryptand.integratednetwork;

/**
 * 传输事件（2026-08-26 集成网络核心）：核心推进流动时产生的
 * 到达 / 送达 / 丢弃等事件，随帧结果（{@link TransportResult}）批量返回。
 * <p>
 * 主线程/平台层经监听器（{@link TransportListener}）收集这些事件后在主线程应用
 * 副作用（生成掉落物实体、写入容器、渲染、播放音效等）——核心永不碰 MC 对象。
 */
public final class TransportEvent {

    /** 事件类型 */
    public enum Kind {
        /** 负载抵达中转节点并入缓冲（继续转发） */
        ARRIVED,
        /** 负载抵达目标（或任意出口）→ 送达 */
        DELIVERED,
        /** 负载被丢弃（缓冲满 {@code "capacity"} / 无线链路丢包 {@code "loss"}） */
        DROPPED
    }

    /** 事件类型 */
    public final Kind kind;
    /** 发生位置的节点 id（到达/送达/丢弃处） */
    public final Object nodeId;
    /** 相关负载 */
    public final TransportPayload payload;
    /** 绝对步号（第几个仿真步） */
    public final long tick;
    /** 丢弃原因（DROPPED 时非 null："capacity" / "loss"） */
    public final String reason;

    public TransportEvent(Kind kind, Object nodeId, TransportPayload payload, long tick, String reason) {
        this.kind = kind;
        this.nodeId = nodeId;
        this.payload = payload;
        this.tick = tick;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "TransportEvent{" + kind + " node=" + nodeId + " payload=" + payload
                + " tick=" + tick + (reason != null ? " reason=" + reason : "") + "}";
    }
}