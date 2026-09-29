package com.hdf.cryptand.circuitsimulation.compute;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 触发/中断调度器（2026-08-22 用户架构：线程分配器支持嵌入式中断模式 + 触发任务）。
 * <p>
 * 【事件驱动 + 空闲零占用】：专用调度线程用 {@link LockSupport#park()} 挂起
 * （不轮询、不占 CPU），只有外部 {@link #trigger} 才 {@code unpark} 唤醒处理，
 * 处理完自动回挂起——符合"只有消息来时进行触发处理，提高线程使用效率"。
 * <p>
 * 【嵌入式中断语义】：
 * <ul>
 *   <li><b>中断向量</b>：注册多个 {@link TriggerVector}（ISR/handler，可带消息载荷）；</li>
 *   <li><b>触发可丢失合并</b>：已置位的向量重复触发合并为一次处理（边沿语义），
 *       处理时消费标志——不重复也不丢"有事件"事实；</li>
 *   <li><b>优先级</b>：高优先级向量先处理（类似嵌套式中断排队）；</li>
 *   <li><b>触发任务</b>：有一个 {@link #when(List, Runnable)} 便捷——一次等
 *       多个向量触发后执行一次任务（等信号量/消息聚合）。</li>
 * </ul>
 * <p>
 * 与 {@link ThreadDispatcher} 互补：Dispatcher 管"任务队列"（很粒度、含排队/虚拟线程），
 * 本类管"消息/中断触发"（轻量、合并、空闲零占用）——可单独使用（如通信/对话消息
 * 到达触发、外部事件唤醒）或配合。线程安全：CopyOnWriteArrayList + 标志位 CAS。
 */
public final class TriggerDispatcher implements AutoCloseable {

    /** 全部触发向量（ISR 表） */
    private final List<TriggerVector> vectors = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String name;
    private final Thread thread;

    /** 线程固定选项：true = 任务激活执行只在第一次执行的那条平台线程上进行 */
    private final boolean pinThread;

    private TriggerDispatcher(String name, boolean pinThread) {
        this.name = name == null || name.isBlank() ? "triggers" : name;
        this.pinThread = pinThread;
        this.thread = pinThread
                ? Thread.ofPlatform().daemon(true).name(this.name).start(this::loop)
                : Thread.ofVirtual().name(this.name).start(this::loop);
    }

    /** 创建触发/中断调度器（默认：专属虚拟线程，空闲 park 零占用） */
    public static TriggerDispatcher create(String name) {
        return new TriggerDispatcher(name, false);
    }

    /**
     * 创建触发/中断调度器（带<b>线程固定选项</b>）。
     *
     * @param pinThread 用户 2026-09-14 定稿："开启后任务激活执行<b>只在第一次执行的线程中</b>
     *                  进行执行"。true = 用一条专用平台线程承载（那条线程就是第一次执行所在的
     *                  线程，此后永远只由它执行，OS 线程恒定）；false = 虚拟线程（默认，
     *                  park/unpark 之间载体线程可能漂移）。
     *
     * <p><b>什么时候必须开启</b>：{@code handler} 里要调用<b>对本机线程有亲和性要求的库</b>
     * （SDL / 其它 native 库）。虚拟线程对 JVM 是"同一条线程"，对本机库却是换了 OS 线程，
     * 它的 TLS（如 {@code SDL_GetError}）与 main-thread 断言会全部错乱。
     * 互斥锁救不了这一类问题 —— 它保证的是"串行"，不是"同一条 OS 线程"。
     */
    public static TriggerDispatcher create(String name, boolean pinThread) {
        return new TriggerDispatcher(name, pinThread);
    }

    /** 线程固定版（= {@code create(name, true)}），语义见上。 */
    public static TriggerDispatcher createPlatform(String name) {
        return new TriggerDispatcher(name, true);
    }

    /** 是否开启了线程固定（诊断） */
    public boolean isPinned() {
        return pinThread;
    }

    /** 是否支持线程固定（当前实现：开启后即用专用平台线程，恒定 OS 线程） */
    public static boolean supportsPinning() {
        return true;
    }

    /** 调度器名 */
    public String name() { return name; }

    /** 注册触发向量（ISR）：返回向量句柄（可 trigger/携带载荷/注销） */
    public TriggerVector vector(String vectorName, Runnable handler) {
        return vector(vectorName, handler, null, 0);
    }

    /** 注册触发向量（载荷版：handler 接收触发消息） */
    public TriggerVector vector(String vectorName,
                                java.util.function.Consumer<Object> payloadHandler, int priority) {
        return vector(vectorName, null, payloadHandler, priority);
    }

    /** 注册触发向量（指定优先级，大 = 优先处理） */
    public TriggerVector vector(String vectorName, Runnable handler, int priority) {
        return vector(vectorName, handler, null, priority);
    }

    private TriggerVector vector(String vectorName, Runnable handler,
                                 java.util.function.Consumer<Object> payloadHandler, int priority) {
        TriggerVector v = new TriggerVector(vectorName, handler, payloadHandler, priority, this);
        if (running.get()) vectors.add(v);
        return v;
    }

    /** 注销向量 */
    public void unregister(TriggerVector v) {
        if (v != null) {
            v.pending.set(false);
            vectors.remove(v);
        }
    }

    /** 触发向量（任何线程）：置标志 + 唤醒调度线程（可丢失合并） */
    public void trigger(TriggerVector v) {
        if (v == null || !running.get()) return;
        v.pending.set(true);
        LockSupport.unpark(thread);
    }

    /** 触发向量 + 携带消息载荷（handler 经 {@link TriggerVector#lastPayload()} 读） */
    public void trigger(TriggerVector v, Object payload) {
        if (v == null || !running.get()) return;
        v.lastPayload.set(payload);
        v.pending.set(true);
        LockSupport.unpark(thread);
    }

    /** 调度线程主循环：挂起（park）→ 被触发唤醒 → 按优先级处理已触发向量 → 回挂起 */
    private void loop() {
        while (running.get()) {
            LockSupport.park(); // 空闲挂起，零占用（unpark 唤醒）
            // 处理全部已触发向量：优先级大先处理（稳定序）
            vectors.stream()
                    .filter(TriggerVector::isPending)
                    .sorted((a, b) -> Integer.compare(b.priority, a.priority))
                    .forEach(v -> {
                        if (v.pending.getAndSet(false)) v.run();
                    });
        }
    }

    /** 已注册向量数（诊断） */
    public int size() { return vectors.size(); }

    /** 关闭调度器：清空向量 + 唤醒线程退出 */
    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            vectors.clear();
            LockSupport.unpark(thread);
        }
    }
}