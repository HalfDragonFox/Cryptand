package com.hdf.cryptand.circuitsimulation.compute;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 多线程分发类（本地统一线程管理，2026-08-12 用户要求）。
 * <p>
 * 所有多线程计算请求【统一走本类】：{@link #execute}/{@link #submit} 接收
 * {@link ComputeTask} → 从 Worker 池（{@link ThreadWorker}）中挑选一个线程 →
 * 把计算任务发送给它。特性：
 *   - 【线程数受限】：Worker 数量 = 构造时 maxThreads（不超过最大配置设定值），
 *     整个游戏的运算线程被限制住、统一管理（不随任务数无限增长）。
 *   - 【优先休眠线程】：有空闲（休眠中）Worker → 直接用（不唤醒不必要的）。
 *   - 【其次最低负载】：全忙时选当前负载最低的 Worker（任务均衡）。
 *   - 【Worker 空闲休眠】：每个 Worker 队列空时在阻塞队列上休眠（无线程忙等）。
 * <p>
 * 实现 {@link ComputeEngine} → 可注册进 {@link ComputeScheduler}，对接现有预留
 * 多线程接口（ComputeTask/ComputeResult/NetworkSnapshot）。所有计算走此统一入口；
 * 未来 C++/GPU/集群（分布式）另实现 ComputeEngine，调度层不变。
 */
public final class ThreadDispatcher implements ComputeEngine {

    /** 每个 Worker 默认最大管理的虚拟线程数（2026-08-16 用户要求：默认最大 1000） */
    public static final int DEFAULT_MAX_VIRTUAL_THREADS = 1000;

    private final List<ThreadWorker> workers;
    private final int maxThreads;
    /** 每个 Worker 最大管理的虚拟线程数（2026-08-16 用户要求） */
    private final int maxVirtualThreads;
    /** 分配器名（线程命名前缀，2026-08-14 通用化：由构造传入，不硬编码） */
    private final String name;
    /** 任务 id → 结果 future（Worker 处理完 complete） */
    private final Map<Long, CompletableFuture<ComputeResult>> results =
            new ConcurrentHashMap<>();

    // ===== 定时任务调度（2026-08-13 用户要求：微秒级定时任务） =====
    // 供需要定时触发计算的 mod 挂载：schedule(action, periodUs) 注册周期任务，
    // 由一个【定时线程】按到期时间（System.nanoTime，纳秒基准）唤醒，到点后
    // 提交到统一 Worker 池执行（submitGeneric —— 线程数受限、优先空闲）。
    // 周期用绝对时间累加（不漂移）；等待用 LockSupport.parkNanos（纳秒精度）。
    private final Object timerLock = new Object();
    private final java.util.PriorityQueue<ScheduledTask> timerQueue =
            new java.util.PriorityQueue<>();
    private volatile Thread timerThread;
    private volatile boolean timerRunning;

    /** 定时任务（可比：按下次触发时间排序） */
    private static final class ScheduledTask implements ScheduledHandle, Comparable<ScheduledTask> {
        final Runnable action;
        final long periodNanos;   // 0 = 一次性
        long nextFireNanos;
        volatile boolean cancelled;

        ScheduledTask(Runnable action, long initialDelayNanos, long periodNanos) {
            this.action = action;
            this.periodNanos = periodNanos;
            this.nextFireNanos = System.nanoTime() + Math.max(initialDelayNanos, 0);
        }

        @Override public void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public int compareTo(ScheduledTask o) {
            return Long.compare(nextFireNanos, o.nextFireNanos);
        }
    }

    public ThreadDispatcher(int maxThreads, String name) {
        this(maxThreads, name, DEFAULT_MAX_VIRTUAL_THREADS);
    }

    /**
     * @param maxVirtualThreads 每个 Worker 最大管理的虚拟线程数（2026-08-16 用户
     *                          要求；默认 {@value #DEFAULT_MAX_VIRTUAL_THREADS}；
     *                          线程收到任务后若虚拟线程数量未满则创建虚拟线程执行，
     *                          否则继续排队；0 = 禁用虚拟线程，任务直接在 Worker
     *                          常驻线程执行）
     */
    public ThreadDispatcher(int maxThreads, String name, int maxVirtualThreads) {
        this.maxThreads = Math.max(1, maxThreads);
        // 0 = 禁用虚拟线程（任务直接在 Worker 常驻线程执行）；否则按配置值
        this.maxVirtualThreads = maxVirtualThreads;
        this.name = (name == null || name.isEmpty()) ? "compute" : name;
        this.workers = new ArrayList<>(this.maxThreads);
        for (int i = 0; i < this.maxThreads; i++) {
            this.workers.add(new ThreadWorker(this.name + "-" + i, results, this.maxVirtualThreads));
        }
        startTimer();
    }

    /** 启动定时调度线程（daemon，线程名 = <分配器名>-timer） */
    private void startTimer() {
        synchronized (timerLock) {
            if (timerRunning) return;
            timerRunning = true;
        }
        Thread t = new Thread(this::timerLoop, name + "-timer");
        t.setDaemon(true);
        timerThread = t;
        t.start();
    }

    /**
     * 注册周期定时任务（微秒级，2026-08-13 用户要求）。
     * 供需要定时触发计算的 mod 挂载（PLC 载波刷新/示波器高频采样/外部计算）。
     *
     * @param action   到期执行的回调（提交到统一 Worker 池，线程数受限）
     * @param periodUs 周期（微秒）
     * @return 句柄（可取消）
     */
    public ScheduledHandle schedule(Runnable action, long periodUs) {
        return schedule(action, periodUs, periodUs);
    }

    /** 注册周期定时任务（带初始延迟，微秒级） */
    public ScheduledHandle schedule(Runnable action, long initialDelayUs, long periodUs) {
        if (action == null) return null;
        if (periodUs <= 0) periodUs = 0;
        ScheduledTask t = new ScheduledTask(action, initialDelayUs * 1000L, periodUs * 1000L);
        synchronized (timerLock) {
            timerQueue.add(t);
            timerLock.notifyAll();
        }
        java.util.concurrent.locks.LockSupport.unpark(timerThread);
        return t;
    }

    /** 注册一次性定时任务（延迟后执行一次，微秒级） */
    public ScheduledHandle scheduleOnce(Runnable action, long delayUs) {
        return schedule(action, delayUs, 0);
    }

    /** 当前排队的定时任务数（调试） */
    public int scheduledCount() {
        synchronized (timerLock) {
            return timerQueue.size();
        }
    }

    /**
     * 定时调度线程：按 nextFireNanos（纳秒基准，微秒级精度）到点唤醒，
     * 把到点任务提交到统一 Worker 池执行；周期任务按绝对时间重新入队（不漂移）。
     */
    private void timerLoop() {
        while (timerRunning && !Thread.currentThread().isInterrupted()) {
            ScheduledTask toRun = null;
            long sleepNanos = 0;
            synchronized (timerLock) {
                // 清理所有已取消任务（cancel 后立即从队列移除，不再触发）
                timerQueue.removeIf(t -> t.cancelled);
                ScheduledTask head = timerQueue.peek();
                if (head != null && !head.cancelled) {
                    long now = System.nanoTime();
                    long rem = head.nextFireNanos - now;
                    if (rem <= 0) {
                        toRun = timerQueue.poll();
                        // 周期任务：绝对时间累加（执行慢/阻塞不漂移）
                        if (toRun.periodNanos > 0) {
                            long nf = toRun.nextFireNanos + toRun.periodNanos;
                            if (nf <= now) nf = now + toRun.periodNanos; // 落后太多 → 重对齐
                            toRun.nextFireNanos = nf;
                            timerQueue.add(toRun);
                        }
                    } else {
                        sleepNanos = rem;
                    }
                }
            }
            if (toRun != null && !toRun.cancelled) {
                try {
                    // 提交到统一 Worker 池执行（线程数受限、优先空闲）
                    submitGeneric(toRun.action);
                } catch (Throwable ignored) {
                }
            } else if (sleepNanos > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(sleepNanos);
            } else {
                // 空队列：等 schedule 唤醒
                try {
                    synchronized (timerLock) {
                        if (timerQueue.isEmpty()) timerLock.wait(1000);
                    }
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** 最大线程数（构造时固定，不超过配置设定值） */
    public int maxThreads() { return maxThreads; }

    /** 每个 Worker 最大管理的虚拟线程数（2026-08-16 用户要求） */
    public int maxVirtualThreads() { return maxVirtualThreads; }

    /** 当前所有 Worker 活动（执行中）虚拟线程总数（调试） */
    public int activeVirtualThreads() {
        int n = 0;
        for (ThreadWorker w : workers) n += w.activeVirtualThreads();
        return n;
    }

    /** 当前总负载（所有 Worker 排队 + 执行中任务数） */
    public int totalLoad() {
        int n = 0;
        for (ThreadWorker w : workers) n += w.load();
        return n;
    }

    /**
     * 线程分发统计（调试，2026-08-12 用户要求）：每个 Worker 累计处理任务数
     * 与当前负载（空闲/忙）。调用方按需每秒打印（如 PhasorEngine 配置开关）。
     */
    public String stats() {
        StringBuilder sb = new StringBuilder("ThreadDispatcher{threads=")
                .append(maxThreads).append(", maxVt=").append(maxVirtualThreads)
                .append(", totalLoad=").append(totalLoad()).append('}');
        for (ThreadWorker w : workers) {
            sb.append('\n').append("  ").append(w.workerName())
                    .append(" processed=").append(w.processed())
                    .append(" load=").append(w.load())
                    .append(" vt=").append(w.activeVirtualThreads())
                    .append('/').append(w.maxVirtualThreads())
                    .append(w.idle() ? " (idle)" : " (busy)");
        }
        return sb.toString();
    }

    /**
     * 选择线程：优先【空闲（休眠）】Worker → 其次【最低负载】Worker。
     * 统一管理下线程数受限，绝不创建新线程。
     */
    private ThreadWorker pickWorker() {
        ThreadWorker best = workers.get(0);
        for (ThreadWorker w : workers) {
            if (w.idle()) return w;              // 优先休眠线程
            if (w.load() < best.load()) best = w; // 其次最低负载
        }
        return best;
    }

    /** 异步提交任务（不阻塞调用方）：选 Worker 投递，结果经 CompletableFuture 获取 */
    public CompletableFuture<ComputeResult> submit(ComputeTask task) {
        CompletableFuture<ComputeResult> f = new CompletableFuture<>();
        results.put(task.id, f);
        pickWorker().submit(task);
        return f;
    }

    /**
     * 异步提交【通用分发任务】（2026-08-12 用户要求）：线程分发基类/管理器把
     * 独立计算（温度推进等额外计算）打包成 Runnable → 选 Worker（优先空闲 →
     * 最低负载）在此线程执行。不走网络求解结果表，返回 future 等待完成。
     * 默认普通模式：虚拟线程执行（2026-08-16 用户要求）。
     */
    public CompletableFuture<Void> submitGeneric(Runnable r) {
        return pickWorker().submitGeneric(r);
    }

    /**
     * 异步提交通用分发任务 + 指定执行模式（2026-08-16 用户要求）。
     * {@link TaskMode#NORMAL} 走虚拟线程；{@link TaskMode#EXCLUSIVE} 独占直算。
     */
    public CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode) {
        return pickWorker().submitGeneric(r, mode);
    }

    /**
     * 独占模式提交通用分发任务：跳过虚拟线程，直接 Worker 常驻线程执行。
     * ⚠ 一般不要使用（长任务阻塞该 Worker 的虚拟线程调度，容易卡死）。
     */
    public CompletableFuture<Void> submitExclusive(Runnable r) {
        return pickWorker().submitExclusive(r);
    }

    @Override
    public ComputeResult execute(ComputeTask task) {
        if (task.expired()) {
            throw new IllegalStateException("task " + task.id + " expired before start");
        }
        CompletableFuture<ComputeResult> f = submit(task);
        try {
            return f.get(Math.max(1, task.budgetNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            results.remove(task.id);
            throw new RuntimeException("task " + task.id + " timed out (budget="
                    + task.budgetNanos + "ns)", e);
        } catch (Exception e) {
            results.remove(task.id);
            throw new RuntimeException("task " + task.id + " failed", e);
        }
    }

    // ===== ComputeEngine（对接预留多线程接口） =====
    @Override public ComputeEngineType type() { return ComputeEngineType.CPU; }

    @Override
    public ThreadQuality quality() {
        return maxThreads > 1 ? ThreadQuality.MULTI_CORE : ThreadQuality.SINGLE_CORE;
    }

    @Override
    public boolean canExecute(ComputeTask task) {
        if (task.minQuality.level > quality().level) return false;
        return task.engineMask == 0 || ComputeEngineType.CPU.matches(task.engineMask);
    }

    @Override
    public void close() {
        for (ThreadWorker w : workers) w.shutdown();
        workers.clear();
        results.clear();
        // 关闭定时调度线程
        timerRunning = false;
        Thread tt = timerThread;
        if (tt != null) {
            java.util.concurrent.locks.LockSupport.unpark(tt);
            tt.interrupt();
            timerThread = null;
        }
        synchronized (timerLock) {
            timerQueue.clear();
        }
    }

    @Override
    public String toString() {
        return "ThreadDispatcher{threads=" + maxThreads + ", maxVt=" + maxVirtualThreads
                + ", totalLoad=" + totalLoad() + "}";
    }
}
