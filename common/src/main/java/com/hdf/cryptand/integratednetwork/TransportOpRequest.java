package com.hdf.cryptand.integratednetwork;

/**
 * 传输网络操作消息（2026-08-26 集成网络核心，对应 netop 的 NetOpRequest）。
 * <p>
 * 主线程/任意来源把传输事件转换为本消息，发给异步传输管理类
 * （{@link AsyncTransportManager}）；若记录表已有该传输网的操作类，消息进入该
 * 网络的缓冲列表（{@link TransportOperation#buffer}），由操作类整合后统一执行。
 * <p>
 * 携带：操作类型 {@link #kind} + 附加数据 {@link #data} + 全局序号 {@link #seq}。
 */
public final class TransportOpRequest {

    /** 操作类型 */
    public final TransportOpKind kind;
    /** 附加数据（拓扑变更 / 帧数等，可 null） */
    public final Object data;
    /** 全局递增序号（调试/排序） */
    public final long seq;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    public TransportOpRequest(TransportOpKind kind, Object data) {
        this.kind = kind;
        this.data = data;
        this.seq = SEQ.incrementAndGet();
    }

    public TransportOpRequest(TransportOpKind kind) {
        this(kind, null);
    }

    @Override
    public String toString() {
        return "TransportOpRequest{" + kind + " seq=" + seq + "}";
    }
}