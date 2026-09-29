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

    /** 关闭标志（2026-09 P2-7 根因修复：优雅关闭——置位后拒绝新消息、
     *  不再继续投递缓冲，在途操作自然完成后清空） */
    private volatile boolean closing = false;
    /** 在途传输操作计数（dispatch 前 +1；最终从记录表移除时 -1）。
     *  供 {@link #awaitIdle} 等待全部在途操作结束（世界卸载不再有孤儿任务）。 */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

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
        if (closing) return; // 关闭中：拒绝新消息（世界已卸载）
        while (true) {
            TransportOperation op = records.computeIfAbsent(key,
                    k -> new TransportOperation(k, this, executor));
            synchronized (op) {
                if (records.get(key) != op) continue; // 记录已被移除 → 重取
                op.buffer.offer(request);
                if (op.running) return;   // 已有操作类在执行 → 消息已入缓冲，等完成
                op.running = true;
            }
            inFlight.incrementAndGet();   // 本次投递为一个在途单元
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
            // 关闭中：不再继续投递缓冲（剩余消息随世界作废），直接结束该在途单元。
            if (!closing && !op.buffer.isEmpty()) {
                dispatch(op);
                return;
            }
            op.running = false;
            if (records.remove(op.key, op)) {
                inFlight.decrementAndGet();
            }
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

    /**
     * 优雅关闭（2026-09 P2-7 根因修复）：置关闭标志——拒绝新消息、不再继续
     * 投递缓冲；在途操作自然完成后由 {@link #awaitIdle} 感知。
     */
    public void shutdown() {
        closing = true;
    }

    /** 是否已关闭（在途操作据此停止消费缓冲） */
    public boolean isClosing() {
        return closing;
    }

    /** 等待全部在途传输操作结束（带超时）。返回是否在超时内结束。 */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (inFlight.get() > 0) {
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return inFlight.get() == 0;
            }
        }
        return true;
    }

    /** 当前在途传输操作数（诊断） */
    public int inFlightCount() {
        return inFlight.get();
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