package com.hdf.cryptand.integratednetwork;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 传输操作类（2026-08-26 集成网络核心，对应 netop.NetlistOperation；线程 C 执行）。
 * <p>
 * 借用 Rust 的所有权概念：异步传输管理类（{@link AsyncTransportManager#records}）
 * 的一个条目 = 一个本类实例，它【拥有】该传输网的消息缓冲 {@link #buffer}——只有
 * 持有本实例的执行线程能消费缓冲，从而保证【同一传输网的所有操作单独锁定直到
 * 完成】（记录表 + running 标志 + 缓冲三方协作）。
 * <p>
 * 执行流程：
 *  1. 整合缓冲消息（{@link #mergeBuffer}）：破坏/拓扑变更/路由各整合为一条；
 *     推进流动整合为一条并把【帧数累加】（批量 TICK 一次执行多帧）；
 *  2. 按优先级执行：拓扑破坏 &gt; 拓扑变更 &gt; 路由 &gt; 推进流动；
 *  3. 执行期间新到的消息继续进入缓冲 → 循环直到缓冲清空；
 *  4. 完成 → 向异步传输管理类发送完成请求（{@link AsyncTransportManager#onComplete}）。
 */
public final class TransportOperation implements Runnable {

    /** 传输网键（拥有权主键 = 记录表 key；同一网同时只有一个操作类） */
    public final Object key;

    /** 消息缓冲（本操作类拥有；异步传输管理类收到消息时放入） */
    final ConcurrentLinkedQueue<TransportOpRequest> buffer = new ConcurrentLinkedQueue<>();

    /** 是否正在执行（记录表中占位：防止同一传输网重复投递） */
    volatile boolean running;

    private final AsyncTransportManager manager;
    private final TransportExecutor executor;
    private final java.util.concurrent.atomic.AtomicLong runs =
            new java.util.concurrent.atomic.AtomicLong();

    TransportOperation(Object key, AsyncTransportManager manager, TransportExecutor executor) {
        this.key = key;
        this.manager = manager;
        this.executor = executor;
    }

    /** 已执行轮数（调试） */
    public long runs() {
        return runs.get();
    }

    /** 当前缓冲消息数（调试） */
    public int buffered() {
        return buffer.size();
    }

    @Override
    public void run() {
        try {
            // 整合 + 执行循环：直到缓冲清空（执行期间新消息继续进入缓冲）。
            // ★ 2026-09 P2-7 根因修复：管理类关闭（世界卸载）→ 停止消费剩余
            //   缓冲，立即归还所有权（onComplete 因 closing 直接移除记录）。
            while (!manager.isClosing()) {
                TransportOpMerged merged = mergeBuffer();
                if (merged.isEmpty()) break;
                runs.incrementAndGet();
                executeMerged(merged);
            }
        } finally {
            // 每次执行完 → 向异步传输管理类发送完成请求
            manager.onComplete(this);
        }
    }

    /**
     * 整合缓冲消息：破坏/拓扑变更/路由各一条；推进流动一条且【帧数累加】。
     * 只有本执行线程消费缓冲（所有权），无需加锁。
     */
    private TransportOpMerged mergeBuffer() {
        TransportOpMerged m = new TransportOpMerged();
        TransportOpRequest r;
        while ((r = buffer.poll()) != null) {
            switch (r.kind) {
                case SPLIT_MERGE -> {
                    m.splitMerge = true;
                    m.splitMergeData = r.data;
                }
                case DESTROY -> {
                    m.destroy = true;
                    m.destroyData = r.data;
                }
                case TOPOLOGY -> {
                    m.topology = true;
                    m.topologyData = r.data;
                }
                case REPORT -> {
                    m.report = true;
                    m.reportData = r.data;
                }
                case ROUTE -> m.route = true;
                case TRANSFER -> {
                    m.transfer = true;
                    m.transferData = r.data;
                }
                case TICK -> {
                    m.tick = true;
                    m.tickFrames += (r.data instanceof Integer i) ? Math.max(0, i) : 1;
                }
            }
        }
        return m;
    }

    /** 按优先级执行整合后的操作：拓扑破坏 &gt; 拓扑变更 &gt; 路由 &gt; 推进流动。 */
    private void executeMerged(TransportOpMerged m) {
        if (m.splitMerge) {
            try {
                executor.executeSplitMerge(key, m.splitMergeData);
            } catch (Throwable ignored) {
            }
        }
        if (m.destroy) {
            try {
                executor.executeDestroy(key, m.destroyData);
            } catch (Throwable ignored) {
            }
        }
        if (m.topology) {
            try {
                executor.executeTopology(key, m.topologyData);
            } catch (Throwable ignored) {
            }
        }
        if (m.report) {
            try {
                executor.executeReport(key, m.reportData);
            } catch (Throwable ignored) {
            }
        }
        if (m.route) {
            try {
                executor.executeRoute(key, null);
            } catch (Throwable ignored) {
            }
        }
        if (m.transfer) {
            try {
                executor.executeTransfer(key, m.transferData);
            } catch (Throwable ignored) {
            }
        }
        if (m.tick) {
            try {
                executor.executeTick(key, m.tickFrames);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public String toString() {
        return "TransportOperation{key=" + key + ", buffered=" + buffer.size()
                + ", running=" + running + ", runs=" + runs.get() + "}";
    }
}