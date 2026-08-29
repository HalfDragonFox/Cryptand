package com.hdf.cryptand.circuitsimulation.comm;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 对话执行器（2026-08-22 用户架构：创建对话时分配【单独线程】）。
 * <p>
 * 每个对话拥有一个【专属虚拟线程】+ 任务队列——该对话的操作只在其专属线程上
 * 顺序执行，与其他对话完全隔离（一个对话卡死不影响其他对话，符合"单独对接"）。
 * <p>
 * 用 {@link Thread#ofVirtual()} 创建（轻量虚拟线程；如需受控线程数可改由
 * ThreadDispatcher 分配）。{@link #submit} 投递任意回调（如缓存变化通知/回调/
 * 提交到核心），{@link #close} 停止线程（销毁对话时调用）。
 */
public final class DialogueExecutor implements AutoCloseable {

    private final String name;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    private DialogueExecutor(String name) {
        this.name = name;
        this.thread = Thread.ofVirtual()
                .name("dialogue-" + name)
                .start(this::loop);
    }

    /** 创建对话专属执行线程（专属虚拟线程） */
    public static DialogueExecutor create(String name) {
        return new DialogueExecutor(name == null || name.isBlank() ? "conversation" : name);
    }

    /** 专属线程事件循环：顺序执行投递的任务，直到关闭 */
    private void loop() {
        while (running) {
            try {
                Runnable r = queue.take(); // 阻塞等待（空闲不占 CPU）
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            } catch (InterruptedException e) {
                if (!running) break;
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 投递任务到对话专属线程（异步；关闭后忽略） */
    public void submit(Runnable r) {
        if (r != null && running) {
            queue.offer(r);
        }
    }

    /** 对话名（诊断） */
    public String name() { return name; }

    /** 是否运行中 */
    public boolean running() { return running; }

    /** 关闭对话：停止专属线程、清空队列 */
    @Override
    public void close() {
        running = false;
        queue.clear();
        thread.interrupt();
    }
}