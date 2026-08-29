package com.hdf.cryptand.circuitsimulation.netop;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 网表相关操作类（2026-08-16 用户架构，分配器分配的线程 C 上执行）。
 * <p>
 * 借用 Rust 的所有权概念：网络操作记录表（{@link AsyncInteractionManager#records}）
 * 的一个条目 = 一个本类实例，它【拥有】该网络的消息缓冲列表 {@link #buffer}——
 * 只有持有本实例的执行线程能消费缓冲，从而保证【同一网络的所有操作单独锁定
 * 直到完成】（记录表 + running 标志 + 缓冲三方协作）。
 * <p>
 * 执行流程：
 *  1. 整合缓冲消息（{@link #mergeBuffer}）：网络拆合/合并类操作整合为一条、
 *     重建整合为一条、求解整合为一条；
 *  2. 按优先级执行：网络拆合 &gt; 重建 &gt; 求解；
 *  3. 若重建过程中检测到需要网络拆合（{@link NetOpExecutor#executeRebuild} 返回
 *     true）→ 重建完成后执行拆合操作，然后继续流程直到所有任务完成；
 *  4. 执行期间新到的消息继续进入缓冲 → 循环直到缓冲清空；
 *  5. 完成 → 向异步交互管理类发送完成请求（{@link AsyncInteractionManager#onComplete}）。
 */
public final class NetlistOperation implements Runnable {

    /** 网络键（拥有权主键 = 网络操作记录表 key；同一网络同时只有一个操作类） */
    public final Object networkKey;

    /** 消息缓冲列表（本操作类拥有；异步交互管理类收到消息时放入） */
    final ConcurrentLinkedQueue<NetOpRequest> buffer = new ConcurrentLinkedQueue<>();

    /** 是否正在执行（记录表中占位：防止同一网络重复投递） */
    volatile boolean running;

    private final AsyncInteractionManager manager;
    private final NetOpExecutor executor;
    private final java.util.concurrent.atomic.AtomicLong runs =
            new java.util.concurrent.atomic.AtomicLong();

    NetlistOperation(Object networkKey, AsyncInteractionManager manager, NetOpExecutor executor) {
        this.networkKey = networkKey;
        this.manager = manager;
        this.executor = executor;
    }

    /** 已执行轮数（调试） */
    public long runs() { return runs.get(); }

    /** 当前缓冲消息数（调试） */
    public int buffered() { return buffer.size(); }

    @Override
    public void run() {
        try {
            // 整合 + 执行循环：直到缓冲清空（执行期间新消息继续进入缓冲）
            while (true) {
                NetOpMerged merged = mergeBuffer();
                if (merged.isEmpty()) break;
                runs.incrementAndGet();
                executeMerged(merged);
            }
        } finally {
            // 网络每次执行完网络操作 → 向异步交互管理类发送完成请求
            manager.onComplete(this);
        }
    }

    /**
     * 整合缓冲消息：同类型操作合并为一条（网络拆合一条 / 重建一条 / 求解一条；
     * data 取缓冲中最后一条）。只有本执行线程消费缓冲（所有权），无需加锁。
     */
    private NetOpMerged mergeBuffer() {
        NetOpMerged m = new NetOpMerged();
        NetOpRequest r;
        while ((r = buffer.poll()) != null) {
            switch (r.kind) {
                case DESTROY -> {
                    m.destroy = true;
                    m.destroyData = r.data;
                }
                case DEVICE_DELTA -> {
                    m.deviceDelta = true;
                    m.deviceDeltaData = r.data;
                }
                case SPLIT_MERGE -> {
                    m.splitMerge = true;
                    m.splitMergeData = r.data;
                }
                case REBUILD -> {
                    m.rebuild = true;
                    m.rebuildData = r.data;
                }
                case SOLVE -> {
                    m.solve = true;
                    m.solveData = r.data;
                }
            }
        }
        return m;
    }

    /** 按优先级执行整合后的操作：网络内容破坏 &gt; 网络拆合 &gt; 重建 &gt; 求解。 */
    private void executeMerged(NetOpMerged m) {
        boolean rebuildSplitAfter = false;
        // 0) 网络内容破坏（最高优先）：设备拆除/导线剪切 → 异步移除自管图端子/导线
        if (m.destroy) {
            try {
                executor.executeDestroy(networkKey, m.destroyData);
            } catch (Throwable ignored) {
            }
        }
        // 0.5) 电气设备列表包增量更新（2026-08-23 用户协议：设备增量是重建输入，
        //      必须先于重建应用）
        if (m.deviceDelta) {
            try {
                executor.executeDeviceDelta(networkKey, m.deviceDeltaData);
            } catch (Throwable ignored) {
            }
        }
        // 1) 网络拆合（次优先）
        if (m.splitMerge) {
            try {
                executor.executeSplitMerge(networkKey, m.splitMergeData);
            } catch (Throwable ignored) {
            }
        }
        // 2) 网络重建（次优先）
        if (m.rebuild) {
            try {
                rebuildSplitAfter = executor.executeRebuild(networkKey, m.rebuildData);
            } catch (Throwable ignored) {
            }
            // 重建时如果需要网络拆合 → 重建完成后执行拆合操作，然后继续流程
            if (rebuildSplitAfter) {
                try {
                    executor.executeSplitMerge(networkKey, m.splitMergeData);
                } catch (Throwable ignored) {
                }
            }
        }
        // 3) 网络求解（最低优先）
        if (m.solve) {
            try {
                executor.executeSolve(networkKey, m.solveData);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public String toString() {
        return "NetlistOperation{key=" + networkKey + ", buffered=" + buffer.size()
                + ", running=" + running + ", runs=" + runs.get() + "}";
    }
}
