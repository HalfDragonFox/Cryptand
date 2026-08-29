package com.hdf.cryptand.circuitsimulation.netop;

/**
 * 网络操作消息（2026-08-16 用户架构）。
 * <p>
 * 主线程交互管理类把网络事件转换为本消息，发给异步交互管理类；异步交互管理类
 * 若网络操作记录表已有该网络的操作类，则把消息放入该网络的消息缓冲列表
 * （{@link NetlistOperation#buffer}），由网表相关操作类整合后统一执行。
 * <p>
 * 携带：操作类型 {@link #kind} + 附加数据 {@link #data}（网络引用/位置/边列表等，
 * 可 null）+ 全局序号 {@link #seq}（调试）。
 */
public final class NetOpRequest {

    /** 操作类型 */
    public final NetOpKind kind;
    /** 附加数据（网络引用/拆合标记/求解参数等，可 null） */
    public final Object data;
    /** 全局递增序号（调试/排序） */
    public final long seq;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    public NetOpRequest(NetOpKind kind, Object data) {
        this.kind = kind;
        this.data = data;
        this.seq = SEQ.incrementAndGet();
    }

    public NetOpRequest(NetOpKind kind) {
        this(kind, null);
    }

    @Override
    public String toString() {
        return "NetOpRequest{" + kind + " seq=" + seq + "}";
    }
}
