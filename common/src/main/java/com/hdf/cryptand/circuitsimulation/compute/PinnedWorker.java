/**
 * ===== 常驻线程（线程固定选项的落地形态）=====
 *
 * 用户 2026-09-14 定稿：
 * 「当清理虚拟内存池缓存时不动常驻池，常驻池用于必须绑定线程的任务，然后任务还需要设置
 *   此线程是否支持其他任务占用，默认为 true，这样的话常驻池也能作为虚拟线程池一部分使用，
 *   并且分配时优先分配任务到空闲的池，只有全部分配完成后再使用常驻池。」
 *
 * <p>组成：
 * <ul>
 *   <li><b>一条平台线程</b>（OS 线程恒定）—— "必须绑定线程的任务"（SDL 等 native 调用）跑在它上面；
 *       空闲时 park、有消息才唤醒，不轮询；</li>
 *   <li><b>消息队列</b>（{@link #post}）—— 绑定线程的调用方投递，与 SDL 那种"只能在同一条
 *       OS 线程上执行"的库配套使用；</li>
 *   <li><b>普通任务槽</b>—— {@code allowOtherTasks = true}（<b>默认</b>）时，分配器把它当作
 *       虚拟线程池的<b>溢出层</b>使用：只有所有普通 Worker 都已有任务在身，才会把任务交给它
 *       （优先级规则见 {@code ThreadDispatcher#pickSink()}）。
 *       设为 {@code false} 则该线程<b>独占</b>，只服务投递给它的消息（实时性要求高的场景，
 *       例如 SDL 事件泵 —— 被普通任务占用会让设备消息延迟）。</li>
 * </ul>
 *
 * <p><b>与虚拟线程池的清理无关</b>：常驻线程登记在分配器自己的 {@code pinned} 列表里，
 * {@code ThreadWorker} 的关闭/收敛（{@link ThreadWorker#shutdown()}）不会触碰它们 ——
 * 这正是用户要求的"清理虚拟内存池缓存时不动常驻池"。
 *
 * <p><b>名额记账</b>：每条常驻线程占用的名额是从虚拟线程配额里"永久移出"的一个
 * （{@link ThreadDispatcher#acquirePinnedSlot}），总量守恒。
 */

package com.hdf.cryptand.circuitsimulation.compute;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class PinnedWorker implements ThreadDispatcher.TaskSink {

    private final String name;
    /** 是否支持其他任务占用（默认 true；false = 独占，只跑投递给它的消息） */
    private final boolean allowOtherTasks;
    private final ThreadDispatcher owner;

    /** 承载它的 ISR：一条专用【平台线程】（线程固定），空闲 park */
    private final TriggerDispatcher thread;
    private final TriggerVector wake;

    /** 必须绑定本线程的消息（FIFO） */
    private final Queue<Runnable> messages = new ConcurrentLinkedQueue<>();
    /** 承接自分配器的普通任务（FIFO） */
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger processed = new AtomicInteger();

    /** 单次唤醒最多消化的普通任务数：避免长任务把绑定线程的消息饿死 */
    private static final int MAX_TASKS_PER_WAKE = 8;

    PinnedWorker(String name, boolean allowOtherTasks, ThreadDispatcher owner) {
        this.name = name == null || name.isBlank() ? "pinned" : name;
        this.allowOtherTasks = allowOtherTasks;
        this.owner = owner;
        this.thread = TriggerDispatcher.create(this.name, true);   // ★ 线程固定 = 平台线程
        this.wake = this.thread.vector(this.name + "-pump", this::drain, 0);
    }

    // ==================== 绑定线程的消息通道 ====================

    /**
     * 投递一条"必须在本线程上执行"的消息（任意线程可调；无锁队列 + 唤醒，绝不阻塞调用方）。
     * <p>投递方与执行方之间只有这个队列与不可变数据，没有任何共享可变状态 ⇒ 无竞态。
     */
    public void post(Runnable message) {
        if (message == null) {
            return;
        }
        messages.add(message);
        thread.trigger(wake);
    }

    /** ISR 处理体（就在那条常驻平台线程上）：先跑绑定消息，再消化若干普通任务。 */
    private void drain() {
        Runnable message;
        int guard = 0;
        while ((message = messages.poll()) != null && guard++ < 256) {
            try {
                message.run();
            } catch (Throwable ignored) {
                // 单条消息出错不影响后续
            }
        }
        Runnable task;
        int done = 0;
        while (done < MAX_TASKS_PER_WAKE && (task = tasks.poll()) != null) {
            pending.decrementAndGet();
            done++;
            try {
                task.run();
            } catch (Throwable ignored) {
                // 单条任务出错不影响后续（future 由提交方自行处理）
            } finally {
                processed.incrementAndGet();
            }
        }
    }

    // ==================== 普通任务槽（分配器的溢出层）====================

    @Override
    public void submit(ComputeTask task) {
        if (task == null) {
            return;
        }
        offer(() -> {
            try {
                ComputeResult r = ThreadWorker.doSolve(task);
                CompletableFuture<ComputeResult> f = owner.takeResult(task.id);
                if (f != null) {
                    f.complete(r);
                }
            } catch (Throwable t) {
                CompletableFuture<ComputeResult> f = owner.takeResult(task.id);
                if (f != null) {
                    f.completeExceptionally(t);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        offer(() -> {
            try {
                r.run();
                f.complete(null);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private void offer(Runnable job) {
        pending.incrementAndGet();
        tasks.add(job);
        thread.trigger(wake);
    }

    // ==================== 查询 / 诊断 ====================

    public String name() {
        return name;
    }

    /** 是否支持其他任务占用（false = 独占，只跑绑定消息） */
    public boolean allowOtherTasks() {
        return allowOtherTasks;
    }

    /** 是否还有未处理的消息或任务 */
    public boolean busy() {
        return pending.get() > 0 || !messages.isEmpty() || !tasks.isEmpty();
    }

    /** 待处理普通任务数 */
    public int queuedTasks() {
        return pending.get();
    }

    /** 累计执行完成的普通任务数 */
    public int processed() {
        return processed.get();
    }

    /** 承载本常驻线程的 ISR（绑定线程的调用方可另注册自己的向量） */
    public TriggerDispatcher dispatcher() {
        return thread;
    }

    @Override
    public String toString() {
        return "PinnedWorker{" + name + (allowOtherTasks ? ", shared" : ", exclusive")
                + ", queued=" + pending.get() + ", processed=" + processed.get()
                + (busy() ? ", busy" : ", idle") + "}";
    }
}
