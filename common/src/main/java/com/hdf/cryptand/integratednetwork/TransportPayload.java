package com.hdf.cryptand.integratednetwork;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 传输负载（2026-08-26 集成网络核心）：在传输网络中被异步移动的内容单元。
 * <p>
 * 一个负载 = 一批/一个"运单"：可以是物品堆叠、流体批次（mB）、能量包（FE）、
 * 无线电消息等。{@link #data} 为平台层不透明引用（如物品栈包装 / 消息体），
 * 核心只搬运不解析——纯虚拟，零 MC 依赖。
 * <p>
 * 不可变；核心在在途/缓冲中用同一实例推进（{@link TransportGraph.InFlight}）。
 */
public final class TransportPayload {

    /** 全局递增负载 ID（诊断/去重） */
    public final long id;
    /** 负载类型（通道兼容过滤用） */
    public final TransferType type;
    /** 数量（单位：物品个数 / mB / FE / 强度倍数等） */
    public final double amount;
    /** 目标节点 id（null = 抵达任意出口/端点即送达） */
    public final Object targetId;
    /** 优先级（越大越先移动；同优先级先入先出） */
    public final int priority;
    /** 平台层不透明数据（可 null） */
    public final Object data;
    /** 出生步号（注入时的绝对步号；调试/超时淘汰用） */
    public final long bornTick;

    private static final AtomicLong SEQ = new AtomicLong();

    public TransportPayload(TransferType type, double amount, Object targetId,
                            int priority, Object data, long bornTick) {
        this.id = SEQ.incrementAndGet();
        this.type = type == null ? TransferType.GENERIC : type;
        this.amount = Math.max(0.0, amount);
        this.targetId = targetId;
        this.priority = priority;
        this.data = data;
        this.bornTick = Math.max(0L, bornTick);
    }

    /** 便捷构造（优先级 0，无数据，出生步 0） */
    public TransportPayload(TransferType type, double amount, Object targetId, Object data) {
        this(type, amount, targetId, 0, data, 0L);
    }

    @Override
    public String toString() {
        return "TransportPayload{id=" + id + ", type=" + type + ", amount=" + amount
                + ", target=" + targetId + ", priority=" + priority + "}";
    }
}