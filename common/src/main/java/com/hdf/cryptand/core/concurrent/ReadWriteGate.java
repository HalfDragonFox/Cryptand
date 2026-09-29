package com.hdf.cryptand.core.concurrent;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * 线程执行表门闸（ExecutionGate）—— 2026-08-30 用户：对同一资源的访问建立
 * "线程执行表"；放入 common 作为通用库（不依赖任何游戏/电力逻辑，任何 mod 可用）。
 *
 * <p>2026-08-30 用户最终语义：**不区分读写线程**，排入的线程直接指定：
 * <ul>
 *   <li>{@link AccessMode#UNORDERED} 乱序执行：多个乱序线程可【同时】执行
 *       （并行、不保证顺序）。</li>
 *   <li>{@link AccessMode#ORDERED} 顺序执行：独占——同一时刻仅【一个】顺序线程执行，
 *       其余严格按排队顺序等候（保证顺序）。</li>
 * </ul>
 *
 * <p>并存规则（与"队列放行"结合，源自用户早期描述，保留为乱序批量语义）：
 *   - 队列队首为 UNORDERED 且已有乱序执行中（计数≥1）→ 一次性放行队列中全部
 *     乱序线程（容量内），实现"一起释放、批量并发"。
 *   - 队列队首为 ORDERED → 等待当前所有执行者清场后仅放行该线程（独占）。
 *   - ORDERED 执行中 → 任何新线程（无论乱序/顺序）都等待其退出。
 *
 * <p>计数（2016-08-30 用户：有读/写计数→现为乱序/顺序计数，退出时相应递减）：
 *   {@link #unorderedCount()} / {@link #orderedCount()} / {@link #totalCount()}；
 *   {@link #exit(AccessMode)} 退出时递减对应计数并重新触发放行。
 *
 * <p>上限：{@link #setCapacity(int)} 可运行时更改（默认 {@value #DEFAULT_MAX_THREADS}=30）；
 *   ≤ 当前执行数时不强制驱逐，仅影响后续放行。
 *
 * <p>线程安全：内部单一 monitor（synchronized + wait/notifyAll），队列 FIFO。
 */
public final class ReadWriteGate {

    /** 默认最大线程数（同时执行上限，用户要求 30） */
    public static final int DEFAULT_MAX_THREADS = 30;

    /** 执行模式：乱序 / 顺序（2026-08-30 用户：方便分辨，不再区分读写） */
    public enum AccessMode {
        /** 乱序执行：多个线程可同时执行（并行，不保证顺序） */
        UNORDERED,
        /** 顺序执行：独占，同一时刻仅一个线程执行（严格顺序） */
        ORDERED
    }

    /** 排队/执行等待者 */
    private static final class Waiter {
        final AccessMode mode;
        final long deadlineNanos;   // 0 = 无限等待
        volatile boolean granted;   // 已获放行许可

        Waiter(AccessMode mode, long deadlineNanos) {
            this.mode = mode;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object monitor = new Object();
    /** 同时执行上限（2026-08-30 用户：可运行时更改，默认 30） */
    private volatile int capacity;
    private final ArrayDeque<Waiter> queue = new ArrayDeque<>();

    // 执行中计数（synchronized(monitor) 保护）
    private int unorderedCount;
    private int orderedCount;

    /** 默认容量 {@value #DEFAULT_MAX_THREADS} */
    public ReadWriteGate() {
        this(DEFAULT_MAX_THREADS);
    }

    /**
     * @param maxThreads 同时执行线程总数上限（乱序+顺序；&lt;1 取 1）
     */
    public ReadWriteGate(int maxThreads) {
        this.capacity = Math.max(1, maxThreads);
    }

    /**
     * 运行时调整同时执行上限（2026-08-30 用户：上限可以更改，默认为30）。
     * <p>线程安全；若设为 &lt; 当前已执行数，不强制驱逐——已排入执行的线程继续运行，
     * 仅后续放行按新上限执行。
     */
    public void setCapacity(int maxThreads) {
        synchronized (monitor) {
            capacity = Math.max(1, maxThreads);
            triggerLocked();    // 上限调大时立即尝试放行排队者
        }
    }

    /** 容量（同时执行上限） */
    public int capacity() {
        return capacity;
    }

    /** 执行中的乱序线程数 */
    public int unorderedCount() {
        synchronized (monitor) {
            return unorderedCount;
        }
    }

    /** 执行中的顺序线程数（0 或 1——顺序独占） */
    public int orderedCount() {
        synchronized (monitor) {
            return orderedCount;
        }
    }

    /** 总计数（执行中 乱序+顺序） */
    public int totalCount() {
        synchronized (monitor) {
            return unorderedCount + orderedCount;
        }
    }

    /** 当前排队（等待许可）的线程数 */
    public int queued() {
        synchronized (monitor) {
            return queue.size();
        }
    }

    /**
     * 阻塞进入（直到获得许可），可被中断。
     *
     * @throws InterruptedException 等待中被打断
     */
    public void enter(AccessMode mode) throws InterruptedException {
        enter(mode, 0L, TimeUnit.MILLISECONDS);
    }

    /**
     * 带超时进入。
     *
     * @param timeout 超时值；≤0 = 无限等待
     * @return true 已获得许可；false 超时未获许可
     * @throws InterruptedException 等待中被打断
     */
    public boolean enter(AccessMode mode, long timeout, TimeUnit unit) throws InterruptedException {
        if (mode == null) throw new NullPointerException("mode");
        long deadline = 0L;
        if (timeout > 0) deadline = System.nanoTime() + Math.max(unit.toNanos(timeout), 0L);
        final Waiter w = new Waiter(mode, deadline);
        synchronized (monitor) {
            queue.addLast(w);
            triggerLocked();
            while (!w.granted) {
                if (w.deadlineNanos != 0L) {
                    long left = w.deadlineNanos - System.nanoTime();
                    if (left <= 0L) {
                        // 超时取消（未获许可才可移除；已获许可者不可回退）
                        if (!w.granted) queue.remove(w);
                        return w.granted;
                    }
                    monitor.wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } else {
                    monitor.wait();
                }
            }
            return true;
        }
    }

    /**
     * 完成并退出表（释放名额，递减对应计数，并触发下一次放行）。
     * 必须与 {@link #enter} 配对（同模式）。
     */
    public void exit(AccessMode mode) {
        synchronized (monitor) {
            if (mode == AccessMode.UNORDERED) {
                if (unorderedCount <= 0) throw new IllegalStateException("no unordered lease to exit");
                unorderedCount--;
            } else {
                if (orderedCount <= 0) throw new IllegalStateException("no ordered lease to exit");
                orderedCount--;
            }
            triggerLocked();
        }
    }

    /** 便捷：持许可执行（自动 enter/exit），异常时保证 exit。 */
    public void execute(AccessMode mode, Runnable action) throws InterruptedException {
        enter(mode);
        try {
            action.run();
        } finally {
            exit(mode);
        }
    }

    /** 便捷：持许可执行并返回结果（自动 enter/exit）。 */
    public <T> T execute(AccessMode mode, java.util.function.Supplier<T> action)
            throws InterruptedException {
        enter(mode);
        try {
            return action.get();
        } finally {
            exit(mode);
        }
    }

    // ===== 放行调度（必须 synchronized(monitor) 内调用） =====

    /**
     * 核心放行规则（每次 enter/exit/setCapacity 后触发）：
     * 1) 顺序执行中（orderedCount&gt;0）→ 不放行任何（等顺序线程清场）。
     * 2) 队首为顺序（ORDERED）→ 如果无乱序执行者（unorderedCount==0）→
     *    仅放行该顺序线程（独占）；有乱序执行中则等全部退出后再放行。
     * 3) 队首为乱序（UNORDERED）→
     *    a) 无乱序执行（unorderedCount==0）→ 放行队首（开启并发会话）；
     *    b) 已有乱序执行（unorderedCount≥1）→ 一次性放行队列中全部乱序线程
     *       （受容量上限约束），实现"一起释放、批量并发"。
     * 所有放行均在容量内（unorderedCount+orderedCount ≤ capacity）。
     */
    private void triggerLocked() {
        boolean changed = false;
        boolean capacityFull = false;
        while (!queue.isEmpty() && !capacityFull) {
            if (orderedCount > 0) break;                         // 顺序独占中
            Waiter head = queue.peekFirst();
            if (head.mode == AccessMode.ORDERED) {
                if (unorderedCount > 0) break;                   // 等乱序清场 → 顺序独占
                if (unorderedCount + orderedCount >= capacity) break;
                head.granted = true;
                queue.pollFirst();
                orderedCount = 1;                                // 顺序独占（仅此一个）
                changed = true;
                break;
            }
            // 队首为乱序
            if (unorderedCount == 0) {
                if (unorderedCount + orderedCount >= capacity) break;
                head.granted = true;
                queue.pollFirst();
                unorderedCount++;
                changed = true;
                // 继续循环：unorderedCount 现 ≥1 → 下一轮走批量分支（若还有排队乱序）
            } else {
                // 已有乱序执行 → 放行队列中所有乱序（容量内；顺序线程跳过）
                Iterator<Waiter> it = queue.iterator();
                while (it.hasNext()) {
                    Waiter w = it.next();
                    if (w.mode == AccessMode.UNORDERED) {
                        if (unorderedCount + orderedCount >= capacity) {
                            capacityFull = true;                 // 容量满：剩余乱序继续等
                            break;
                        }
                        w.granted = true;
                        it.remove();
                        unorderedCount++;
                        changed = true;
                    }
                }
                break;                                           // 本批放行完毕（等待者已被唤醒）
            }
            if (unorderedCount + orderedCount >= capacity) capacityFull = true;
        }
        if (changed) monitor.notifyAll();
    }
}
