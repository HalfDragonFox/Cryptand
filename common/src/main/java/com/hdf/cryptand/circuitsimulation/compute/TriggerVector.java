package com.hdf.cryptand.circuitsimulation.compute;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 触发向量（2026-08-22 用户架构：线程分配器嵌入式中断模式——中断源/ISR）。
 * <p>
 * 类似嵌入式中断向量：一个 handler（ISR）+ 触发标志位 + 可选消息载荷。
 * 外部（任何线程）调用 {@link TriggerDispatcher#trigger} 置位标志并唤醒
 * 调度器线程；触发【可丢失合并】（嵌入式边沿触发语义：已置位期间的重复触发
 * 合并为一次处理，处理时消费并清标志——不丢"有事件"这一事实，也不重复）。
 * <p>
 * handler 两种：{@link Runnable}（无载荷）或 {@link java.util.function.Consumer}
 * （接收触发载荷——消息到达触发处理）。{@link #priority} 用于优先处理。
 */
public final class TriggerVector implements AutoCloseable {

    /** 向量名（中断源名，诊断） */
    public final String name;
    /** 无载荷 ISR（可 null：用 payloadHandler） */
    private final Runnable handler;
    /** 载荷 ISR（可 null：用 handler） */
    private final java.util.function.Consumer<Object> payloadHandler;
    /** 触发标志位（嵌入式 ISR 置位；处理时消费） */
    final AtomicBoolean pending = new AtomicBoolean(false);
    /** 最近一次触发携带的消息载荷（handler 可读；可 null） */
    final AtomicReference<Object> lastPayload = new AtomicReference<>();
    /** 优先级（数值大 = 优先处理） */
    public final int priority;
    final TriggerDispatcher owner;

    TriggerVector(String name, Runnable handler, java.util.function.Consumer<Object> payloadHandler,
                  int priority, TriggerDispatcher owner) {
        this.name = name;
        this.handler = handler;
        this.payloadHandler = payloadHandler;
        this.priority = priority;
        this.owner = owner;
    }

    /** 向量名 */
    public String name() { return name; }

    /** 是否已触发待处理（标志位） */
    public boolean isPending() { return pending.get(); }

    /** 触发本向量（等价于经调度器 trigger；便捷） */
    public void trigger() {
        TriggerDispatcher d = owner;
        if (d != null) d.trigger(this);
    }

    /** 触发并携带消息载荷（handler 经 {@link #lastPayload} 读取） */
    public void trigger(Object payload) {
        TriggerDispatcher d = owner;
        if (d != null) d.trigger(this, payload);
    }

    /** 最近一次触发载荷（handler 处理时读取；可 null） */
    public Object lastPayload() {
        return lastPayload.get();
    }

    /** 执行 handler（调度器线程调用；失败忽略）——载荷版优先 */
    void run() {
        try {
            if (payloadHandler != null) {
                payloadHandler.accept(lastPayload.get());
            } else if (handler != null) {
                handler.run();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 注销向量（从调度器移除并清标志） */
    @Override
    public void close() {
        TriggerDispatcher d = owner;
        if (d != null) d.unregister(this);
    }

    @Override
    public String toString() {
        return "TriggerVector{" + name + ", pri=" + priority + ", pending=" + pending.get() + "}";
    }
}