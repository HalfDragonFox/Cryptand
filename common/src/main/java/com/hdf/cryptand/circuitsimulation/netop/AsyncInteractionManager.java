package com.hdf.cryptand.circuitsimulation.netop;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步交互管理类（2026-08-16 用户架构，对应架构图"异步线程 B"）。
 * <p>
 * 职责：
 *  - 维护【网络操作记录表】{@link #records}：Map&lt;网络键, 网表相关操作类&gt;——
 *    借用 Rust 所有权：每个网络键同时只有一个网表相关操作类实例（拥有该网络
 *    的消息缓冲），操作未完成前该网络一直被"占用"。
 *  - 收到网络操作消息（主线程交互管理类转发）：
 *      · 记录表已有该网络的操作类 → 把消息放入该网络消息缓冲列表
 *        （{@link NetlistOperation#buffer}），等当前操作完成后再整合执行；
 *      · 没有 → 创建记录 + 网表相关操作类，发送到分配器执行（线程 C）。
 *  - 网表相关操作类执行完网络操作后发送完成请求（{@link #onComplete}）：
 *      · 该网络消息缓冲有消息 → 继续向分配器操作（下一轮整合执行）；
 *      · 无消息 → 从记录表移除（网络操作解锁）。
 *  - 目的：让【所有对网络的操作都是单独锁定的直到完成】。
 *  - 发送网表相关操作到分配器前对缓冲消息的整合（网络拆合/重建/求解各合并为
 *    一条）由 {@link NetlistOperation#mergeBuffer} 在执行线程上完成。
 * <p>
 * 线程安全：记录表为 ConcurrentHashMap；running 标志 + 缓冲 + 二参 remove 共同
 * 保证同一网络不会有两个操作类同时执行（详见 {@link #submit}/{@link #onComplete}）。
 */
public final class AsyncInteractionManager {

    /** 网络操作记录表：网络键 → 网表相关操作类（每网络一个，拥有消息缓冲） */
    private final ConcurrentHashMap<Object, NetlistOperation> records =
            new ConcurrentHashMap<>();

    private final ThreadDispatcher dispatcher;
    private final NetOpExecutor executor;

    /** 网表相关操作类自身在分配器上的任务模式（默认普通模式=虚拟线程） */
    private final TaskMode dispatchMode;

    public AsyncInteractionManager(ThreadDispatcher dispatcher, NetOpExecutor executor) {
        this(dispatcher, executor, TaskMode.NORMAL);
    }

    public AsyncInteractionManager(ThreadDispatcher dispatcher, NetOpExecutor executor,
                                   TaskMode dispatchMode) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.dispatchMode = dispatchMode;
    }

    /** 分配器（诊断） */
    public ThreadDispatcher dispatcher() { return dispatcher; }

    /**
     * 收到网络操作消息（主线程交互管理类 / 任何来源调用）。
     * 记录表已有该网络操作类 → 消息放入该网络消息缓冲列表；否则创建记录 + 操作类，
     * 发送到分配器执行。同一网络同时只有一个操作类执行（单独锁定直到完成）。
     * <p>
     * ⚠ 循环重试：{@link #onComplete} 可能已把旧操作类从记录表移除（释放锁）——
     * 若本调用抓到的是已移除的旧实例，必须重取记录表当前条目再入缓冲，防止
     * 同一网络出现两个操作类并发执行（双投递）。
     */
    public void submit(Object networkKey, NetOpRequest request) {
        if (networkKey == null || request == null) return;
        while (true) {
            NetlistOperation op = records.computeIfAbsent(networkKey,
                    k -> new NetlistOperation(k, this, executor));
            synchronized (op) {
                if (records.get(networkKey) != op) continue; // 记录已被移除 → 重取
                op.buffer.offer(request);
                if (op.running) return;   // 已有操作类在执行 → 消息已入缓冲，等完成
                op.running = true;
            }
            dispatch(op);
            return;
        }
    }

    /**
     * 网表相关操作类完成回调（发送完成请求）：检查该网络消息缓冲。
     *   - 有消息 → 保持占用（running 不变），继续向分配器投递（下一轮整合执行）；
     *   - 无消息 → 从记录表移除（网络操作解锁）。
     * <p>
     * 与 {@link #submit} 的竞态由 op 监视器 + 二参 remove 解决：任何在 onComplete
     * 移除记录前入缓冲的消息都会被下一轮执行；移除后再来的 submit 会创建新操作类。
     */
    void onComplete(NetlistOperation op) {
        synchronized (op) {
            if (!op.buffer.isEmpty()) {
                // 仍有缓冲消息 → 保持占用，继续向分配器操作
                dispatch(op);
                return;
            }
            op.running = false;
            // 二参 remove：仅当记录表仍映射到该操作类时移除（防误删新条目）
            records.remove(op.networkKey, op);
        }
    }

    /** 把网表相关操作类发送到分配器执行（线程 C）。 */
    private void dispatch(NetlistOperation op) {
        if (dispatcher == null) {
            // 无分配器 → 当前线程直算兜底（不丢任务；op.run 内 finally 会回调完成）
            try {
                op.run();
            } catch (Throwable ignored) {
            }
            return;
        }
        if (dispatchMode == TaskMode.EXCLUSIVE) {
            // 独占模式：跳过虚拟线程，直接 Worker 常驻线程执行（一般不要使用）
            dispatcher.submitExclusive(op);
        } else {
            // 普通模式：走虚拟线程执行
            dispatcher.submitGeneric(op);
        }
    }

    /** 记录表大小（诊断：当前被占用/锁定的网络数） */
    public int size() { return records.size(); }

    /** 当前记录表的网络键（诊断） */
    public java.util.Set<Object> keys() { return records.keySet(); }

    /** 世界切换/关闭 → 清空记录表（释放所有网络锁） */
    public void clear() { records.clear(); }
}
