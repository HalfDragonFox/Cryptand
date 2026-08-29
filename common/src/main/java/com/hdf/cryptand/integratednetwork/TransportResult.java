package com.hdf.cryptand.integratednetwork;

import java.util.List;

/**
 * 传输帧结果（2026-08-26 集成网络核心）：核心一次 {@code step} 的产物。
 * <p>
 * 类似电路仿真的 {@code SolveResult}：一次性携带本帧全部事件（到达/送达/丢弃）
 * 与统计量，由核心缓存于执行器（{@code result(key)} 查询）并经
 * {@link TransportListener} 回调发布。主线程只消费结果，不参与计算。
 */
public final class TransportResult {

    /** 绝对步号（第几个仿真步，1 起） */
    public final long step;
    /** 本帧事件（不可变快照） */
    public final List<TransportEvent> events;
    /** 本帧成功移入在途的负载总量（单位） */
    public final double movedUnits;
    /** 当前仍在网络中的负载数（缓冲 + 在途） */
    public final int pending;
    /** 本帧耗时（ns，诊断） */
    public final long nanoTime;

    public TransportResult(long step, List<TransportEvent> events,
                           double movedUnits, int pending, long nanoTime) {
        this.step = step;
        this.events = events == null ? List.of() : List.copyOf(events);
        this.movedUnits = movedUnits;
        this.pending = pending;
        this.nanoTime = nanoTime;
    }

    /** 本帧送达事件数 */
    public int delivered() {
        return count(TransportEvent.Kind.DELIVERED);
    }

    /** 本帧丢弃事件数 */
    public int dropped() {
        return count(TransportEvent.Kind.DROPPED);
    }

    private int count(TransportEvent.Kind kind) {
        int c = 0;
        for (TransportEvent e : events) if (e.kind == kind) c++;
        return c;
    }

    @Override
    public String toString() {
        return "TransportResult{step=" + step + ", events=" + events.size()
                + ", moved=" + movedUnits + ", pending=" + pending
                + ", ns=" + nanoTime + "}";
    }
}