package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步传输管理类（2026-08-26 集成网络核心，对应架构"异步线程 B"；与
 * {@link com.hdf.cryptand.circuitsimulation.netop.AsyncInteractionManager} 同构）。
 * <p>
 * 职责：维护【传输操作记录表】{@link #records}：Map&lt;传输网键, 传输操作类&gt;——
 * 借用 Rust 所有权：每个传输网键同时只有一个 {@link TransportOperation} 实例
 * （拥有该网的消息缓冲），操作未完成前该网一直被"占用"。
 * <p>
 * 收到传输网络操作消息：
 *   - 记录表已有该网的操作类 → 消息放入该网缓冲列表，等当前操作完成后再整合执行；
 *   - 没有 → 创建记录 + 操作类，发送到分配器执行（线程 C）。
 * 操作类执行完发送完成请求（{@link #onComplete}）：缓冲有消息 → 继续投递下一轮
 * 整合执行；无消息 → 从记录表移除（解锁）。
 * <p>
 * 线程安全：记录表为 ConcurrentHashMap；running 标志 + 缓冲 + 二参 remove 共同
 * 保证同一传输网不会有两个操作类同时执行（详见 {@link #submit}/{@link #onComplete}）。
 */
public final class AsyncTransportManager {

    /** 传输操作记录表：传输网键 → 传输操作类（每网一个，拥有消息缓冲） */
    private final ConcurrentHashMap<Object, TransportOperation> records =
            new ConcurrentHashMap<>();

    private final ThreadDispatcher dispatcher;
    private final TransportExecutor executor;

    /** 传输操作类自身在分配器上的任务模式（默认普通=虚拟线程） */
    private final TaskMode dispatchMode;

    public AsyncTransportManager(ThreadDispatcher dispatcher, TransportExecutor executor) {
        this(dispatcher, executor, TaskMode.NORMAL);
    }

    public AsyncTransportManager(ThreadDispatcher dispatcher, TransportExecutor executor,
                                 TaskMode dispatchMode) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.dispatchMode = dispatchMode;
    }

    /** 分配器（诊断） */
    public ThreadDispatcher dispatcher() {
        return dispatcher;
    }

    /**
     * 收到传输网络操作消息（主线程/任何来源调用）。
     * 记录表已有该网操作类 → 消息入缓冲；否则创建记录 + 操作类发送到分配器执行。
     * 同一传输网同时只有一个操作类执行（单独锁定直到完成）。
     * <p>
     * ⚠ 循环重试：{@link #onComplete} 可能已把旧操作类从记录表移除——若本调用抓到
     * 的是已移除的旧实例，必须重取记录表当前条目再入缓冲，防止双投递。
     */
    public void submit(Object key, TransportOpRequest request) {
        if (key == null || request == null) return;
        while (true) {
            TransportOperation op = records.computeIfAbsent(key,
                    k -> new TransportOperation(k, this, executor));
            synchronized (op) {
                if (records.get(key) != op) continue; // 记录已被移除 → 重取
                op.buffer.offer(request);
                if (op.running) return;   // 已有操作类在执行 → 消息已入缓冲，等完成
                op.running = true;
            }
            dispatch(op);
            return;
        }
    }

    /**
     * 传输操作类完成回调：检查该网缓冲。
     *   - 有消息 → 保持占用（running 不变），继续投递（下一轮整合执行）；
     *   - 无消息 → 从记录表移除（解锁）。
     * 与 {@link #submit} 的竞态由 op 监视器 + 二参 remove 解决。
     */
    void onComplete(TransportOperation op) {
        synchronized (op) {
            if (!op.buffer.isEmpty()) {
                dispatch(op);
                return;
            }
            op.running = false;
            records.remove(op.key, op);
        }
    }

    /** 把传输操作类发送到分配器执行（线程 C）。 */
    private void dispatch(TransportOperation op) {
        if (dispatcher == null) {
            // 无分配器 → 当前线程直算兜底（不丢任务；op.run finally 会回调完成）
            try {
                op.run();
            } catch (Throwable ignored) {
            }
            return;
        }
        if (dispatchMode == TaskMode.EXCLUSIVE) {
            dispatcher.submitExclusive(op);
        } else {
            dispatcher.submitGeneric(op);
        }
    }

    /** 记录表大小（诊断：当前被占用/锁定的传输网数） */
    public int size() {
        return records.size();
    }

    /** 当前记录表的传输网键（诊断） */
    public java.util.Set<Object> keys() {
        return records.keySet();
    }

    /** 世界切换/关闭 → 清空记录表（释放所有传输网锁） */
    public void clear() {
        records.clear();
    }
}