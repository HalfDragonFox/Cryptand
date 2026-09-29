package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.circuitsimulation.solver.Solver;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程处理类（本地多线程 Worker，2026-08-12 用户要求；2026-08-16 虚拟线程支持）。
 * <p>
 * 每个 Worker 是【一个常驻平台线程】+【阻塞任务队列】+【虚拟线程池】：
 *   - 空闲时在队列上休眠（{@link BlockingQueue#take()}，无线程轮询/忙等）
 *   - 收到任务 → 唤醒取出执行（solve NetworkSnapshot，纯数据不碰世界）→ 回到休眠
 *   - "线程全部处理完后休眠"：队列为空时线程持续休眠，直到 Dispatcher 投递新任务
 * <p>
 * 【虚拟线程管理】（2026-08-16 用户要求）：每个 Worker 最大管理
 * {@link #maxVirtualThreads} 个虚拟线程（默认 1000，可配置）：
 *   - 普通模式（{@link TaskMode#NORMAL}）任务：Worker 收到任务后，若虚拟线程
 *     数量未满则创建虚拟线程执行，否则在信号量上继续排队（任务留在队列等待
 *     空闲虚拟线程）——信号量许可耗尽时 Worker 阻塞取下一个任务，天然排队。
 *   - 独占模式（{@link TaskMode#EXCLUSIVE}）任务：跳过虚拟线程，直接在 Worker
 *     常驻线程上执行（一般不要使用，长任务会阻塞该 Worker 的虚拟线程调度）。
 * <p>
 * 负载统计：{@link #load()}（排队 + 执行中任务数）与 {@link #idle()}（休眠空闲），
 * 供 {@link ThreadDispatcher} 做"优先休眠线程、其次最低负载"的分配。
 * <p>
 * 结果传递：任务 id → {@link CompletableFuture}（由 Dispatcher 共享的结果表），
 * 处理完 complete 结果；调用方经 future 获取（execute 同步等待 / submit 异步）。
 */
public final class ThreadWorker implements Runnable, ThreadDispatcher.TaskSink {

    /** 当前 JVM 是否支持虚拟线程（Java 21+） */
    private static final boolean VIRTUAL_SUPPORTED = detectVirtualThreads();

    private static boolean detectVirtualThreads() {
        try {
            return Runtime.version().feature() >= 21;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 队列任务：Runnable + 执行模式（普通=虚拟线程 / 独占=常驻线程）+ 优先级 */
    private static final class Job {
        final Runnable runnable;
        final TaskMode mode;

        Job(Runnable runnable, TaskMode mode) {
            this.runnable = runnable;
            this.mode = mode;
        }

        /** 优先级（默认 0，越大越先） */
        int priority() {
            return mode != null ? mode.priority : 0;
        }
    }

    private final String name;
    private final Thread thread;
    /** 任务队列：优先级队列（2026-08-30 物理高优先；同优先级 FIFO），
     *  网络求解任务包装 + 通用分发任务（温度推进等额外计算） */
    private final BlockingQueue<Job> queue = new java.util.concurrent.PriorityBlockingQueue<>(
            64, java.util.Comparator.comparingInt(Job::priority).reversed());
    /** ⚠ 2026-08-30 用户：虚拟任务池【缓存睡眠】——虚拟消费者常驻循环，执行完
     *  任务 → 队列 take 睡眠 → 下一次任务 offer 直接分配（唤醒睡眠消费者），
     *  不再每任务 new + 销毁虚拟线程（消除创建/GC/调度开销）。 */
    private final BlockingQueue<Job> virtualQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    /** 累计处理任务数（调试统计：每次任务完成 +1） */
    private final java.util.concurrent.atomic.AtomicLong processed = new java.util.concurrent.atomic.AtomicLong();
    /** 每秒窗口（2026-08-29 HUD 用：任务速率 + 虚拟线程申请速率共用同一 1s 窗口） */
    private volatile long rateWindowNs = System.nanoTime();
    private volatile long processedAtWindowStart;
    private volatile double processedPerSecondCached;
    /** 累计创建的虚拟线程数（调试统计；2026-08-29 用户：虚拟线程也显示 1s 申请量） */
    private final java.util.concurrent.atomic.AtomicLong vtCreated = new java.util.concurrent.atomic.AtomicLong();
    private volatile long vtAtWindowStart;
    private volatile double vtPerSecondCached;
    /** 任务 id → 结果 future（与 ThreadDispatcher 共享） */
    private final Map<Long, CompletableFuture<ComputeResult>> results;
    /**
     * 本 Worker 最大管理的虚拟线程数（默认 1000，可配置；1 = 仍创建但限并发 1）。
     * <p>2026-09-14 起<b>可下调</b>：分配器把某个名额永久划给【常驻固定线程】时同步收窄
     * （用户定稿："可以在线程池永久移出一个虚拟线程放置在此线程常驻池那边，
     * 并且分配从 1000 降低到 999"）—— 总量守恒，不额外多占系统资源。
     */
    private volatile int maxVirtualThreads;
    /** 当前虚拟消费者数（缓存睡眠保留，调试统计） */
    private final AtomicInteger activeVirtual = new AtomicInteger();
    private volatile boolean closed;

    ThreadWorker(String name, Map<Long, CompletableFuture<ComputeResult>> results,
                 int maxVirtualThreads) {
        this.name = name;
        this.results = results;
        // 0 = 禁用虚拟线程（任务直接在 Worker 常驻线程执行）；正数 = 最大管理数
        this.maxVirtualThreads = Math.max(0, maxVirtualThreads);
        // 线程名完全由构造参数决定（2026-08-14 通用化：不硬编码 mod 前缀，
        // 供其他任何 mod 复用时保持中性命名，线程名 = <分配器名>-<序号>）
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** 当前负载（排队 + 执行中任务数） */
    int load() { return pending.get(); }

    /** 累计处理任务数（调试统计） */
    long processed() { return processed.get(); }

    /**
     * 每秒处理任务数（调试统计；2026-08-29 线程池 HUD 用）。
     * 惰性 1 秒窗口：距上次结算 &gt;= 1s 才重新差分，否则返回缓存值——
     * 任何线程（含渲染线程）随意调用，开销极小且结果稳定。
     */
    double processedPerSecond() {
        refreshRate();
        return processedPerSecondCached;
    }

    /**
     * 每秒【申请创建虚拟线程】数（2026-08-29 用户：虚拟线程也显示 1s 申请的量）。
     * 与 {@link #processedPerSecond()} 共用同一 1s 窗口惰性差分。
     */
    double vtCreatedPerSecond() {
        refreshRate();
        return vtPerSecondCached;
    }

    /** 累计创建的虚拟线程数（调试统计） */
    long vtCreated() {
        return vtCreated.get();
    }

    /** 1s 窗口惰性结算（任务速率 + 虚拟线程申请速率同时更新） */
    private void refreshRate() {
        long now = System.nanoTime();
        long elapsed = now - rateWindowNs;
        if (elapsed < 1_000_000_000L) return;
        double secs = elapsed / 1e9;
        processedPerSecondCached = (processed.get() - processedAtWindowStart) / secs;
        vtPerSecondCached = (vtCreated.get() - vtAtWindowStart) / secs;
        processedAtWindowStart = processed.get();
        vtAtWindowStart = vtCreated.get();
        rateWindowNs = now;
    }

    /** 是否空闲（休眠等待中，无任何任务） */
    boolean idle() { return pending.get() == 0; }

    /** 线程名 */
    String workerName() { return name; }

    /** 异步投递任务到本线程（空闲线程被唤醒处理；不阻塞调用方；默认普通模式=虚拟线程） */
    @Override
    public void submit(ComputeTask task) {
        pending.incrementAndGet();
        queue.offer(new Job(() -> {
            try {
                ComputeResult r = doSolve(task);
                CompletableFuture<ComputeResult> f = results.remove(task.id);
                if (f != null) f.complete(r);
            } catch (Throwable t) {
                CompletableFuture<ComputeResult> f = results.remove(task.id);
                if (f != null) f.completeExceptionally(t);
            } finally {
                pending.decrementAndGet();
                processed.incrementAndGet();
            }
        }, TaskMode.NORMAL));
    }

    /**
     * 异步投递【通用分发任务】（非网络求解：温度推进等额外计算，2026-08-12）。
     * 由线程分发基类/管理器把独立计算打包成 Runnable 在此线程上执行；
     * 返回 future 供调用方等待完成（不占用网络求解结果表）。
     * 默认普通模式：虚拟线程执行。
     */
    CompletableFuture<Void> submitGeneric(Runnable r) {
        return submitGeneric(r, TaskMode.NORMAL);
    }

    /** 通用分发任务 + 指定执行模式（普通=虚拟线程 / 独占=常驻线程直算） */
    @Override
    public CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        pending.incrementAndGet();
        queue.offer(new Job(() -> {
            try {
                r.run();
                f.complete(null);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                pending.decrementAndGet();
                processed.incrementAndGet();
            }
        }, mode));
        return f;
    }

    /** 独占模式通用分发任务：跳过虚拟线程，直接在本 Worker 常驻线程执行。 */
    CompletableFuture<Void> submitExclusive(Runnable r) {
        return submitGeneric(r, TaskMode.EXCLUSIVE);
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                Job job = queue.take(); // 空闲休眠（全部处理完 → 继续休眠）
                try {
                    if (job.mode == TaskMode.EXCLUSIVE || job.mode == TaskMode.PHYSICS_HIGH) {
                        runJob(job);          // 独占/物理：跳过虚拟线程，直接常驻线程执行
                    } else {
                        runJobOnVirtual(job); // 普通：走虚拟线程执行
                    }
                } catch (Throwable ignored) {
                    // 任务内部已处理异常（future completeExceptionally）；此处防 Worker 崩溃
                }
            } catch (InterruptedException e) {
                if (closed) break;
            }
        }
    }

    /**
     * 普通模式执行：虚拟任务池【缓存睡眠复用】（2026-08-30 用户方案）——
     * 虚拟消费者常驻循环（执行完 → virtualQueue.take() 睡眠 → 下一次任务
     * offer 直接分配/唤醒），不再每任务 new + 销毁虚拟线程（消除创建/GC/
     * 调度开销）。消费者数量懒增长至 maxVirtualThreads 上限（峰值并发，
     * 缓存睡眠保留不缩回）。
     */
    private void runJobOnVirtual(Job job) {
        if (!VIRTUAL_SUPPORTED || maxVirtualThreads <= 0) {
            runJob(job); // JVM 不支持虚拟线程 / 已禁用 → 直算兜底（不丢任务）
            return;
        }
        virtualQueue.offer(job);
        int active = activeVirtual.get();
        while (active < maxVirtualThreads
                && active < virtualQueue.size()
                && activeVirtual.compareAndSet(active, active + 1)) {
            vtCreated.incrementAndGet(); // 统计：启动的虚拟消费者数（缓存保留）
            try {
                Thread.ofVirtual().name(name + "-vt").start(this::virtualConsumerLoop);
            } catch (Throwable t) {
                // 虚拟线程创建失败（极端）→ 消费者数回退，直算兜底（不丢任务）
                activeVirtual.decrementAndGet();
                runJob(job);
                return;
            }
            active = activeVirtual.get();
        }
    }

    /** 虚拟消费者常驻循环：取任务执行 → 睡眠等待下一任务（缓存睡眠复用） */
    private void virtualConsumerLoop() {
        while (!closed) {
            Job job;
            try {
                job = virtualQueue.take(); // 空闲睡眠（执行完 → 等下一任务直接分配）
            } catch (InterruptedException e) {
                if (closed) break;
                continue;
            }
            try {
                runJob(job);
            } catch (Throwable ignored) {
            }
        }
    }
    // （旧信号量 + 每任务创建虚拟线程实现已移除——2026-08-30 缓存睡眠复用）

    private void runJob(Job job) {
        try {
            job.runnable.run();
        } catch (Throwable ignored) {
            // 任务内部已处理异常；此处防虚拟线程/Worker 崩溃
        }
    }

    /** 执行计算任务：NetworkSnapshot → 网络 → 求解器（Real/Complex） */
    /** 求解任务（包级：常驻线程 {@link PinnedWorker} 承接普通任务时复用同一份执行逻辑） */
    static ComputeResult doSolve(ComputeTask task) {
        Network net = task.snapshot.toNetwork();
        Solver solver = com.hdf.cryptand.circuitsimulation.solver.Solvers.create(
                task.snapshot.solveMode(), net);
        SolveResult r = solver.solve(net);
        return new ComputeResult(task.id, r.voltages, r.complex, r.converged,
                r.iterations, r.solveNanos, r.mode, "worker-" + net.frequency + "Hz");
    }

    void shutdown() {
        closed = true;
        thread.interrupt();
    }

    /** 本 Worker 最大管理的虚拟线程数（默认 1000，可配置） */
    int maxVirtualThreads() { return maxVirtualThreads; }

    /** 调整虚拟线程上限（分配器把名额划给常驻固定线程时调用；已在跑的消费者不受影响） */
    void setMaxVirtualThreads(int value) {
        this.maxVirtualThreads = Math.max(0, value);
    }

    /** 当前活动（执行中）虚拟线程数（调试统计） */
    int activeVirtualThreads() { return activeVirtual.get(); }

    @Override
    public String toString() {
        return "ThreadWorker{" + name + ", load=" + load()
                + ", vt=" + activeVirtual.get() + "/" + maxVirtualThreads + "}";
    }
}
