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
    /**
     * 已被【常驻固定线程】占用的虚拟线程名额数。
     * <p>用户 2026-09-14："如果虚拟线程+固定的话可以在线程池永久移出一个虚拟线程放置在此线程
     * 常驻池那边，并且分配从 1000 降低到 999（比如）" —— 线程固定不是白拿一条常驻线程，
     * 而是把虚拟线程配额里的名额<b>永久移出</b>转交给它：总量守恒、同一个分配器记账。
     */
    private final java.util.concurrent.atomic.AtomicInteger pinnedSlots =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 常驻线程池（"必须绑定线程的任务"跑在它们上面）。
     * <p>用户 2026-09-14："当清理虚拟内存池缓存时不动常驻池。" —— 它们是独立登记表，
     * {@link ThreadWorker} 的关闭/收敛（{@link #shutdown()}）不触碰。
     */
    private final java.util.List<PinnedWorker> pinned = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 任务落点：{@link ThreadWorker}（虚拟线程池）与 {@link PinnedWorker}（常驻池）都能承接任务，
     * 由 {@link #pickSink()} 按"优先空闲池、全部分配完才用常驻池"的规则挑选。
     */
    public interface TaskSink {
        /** 投递求解任务（结果经分配器的结果表回填） */
        void submit(ComputeTask task);

        /** 投递通用任务（返回 future 供调用方等待） */
        java.util.concurrent.CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode);
    }
    /** 分配器名（线程命名前缀，2026-08-14 通用化：由构造传入，不硬编码） */
    private final String name;
    /** 均衡检测间隔（ns；0=每次提交实时均衡；2026-08-29 用户：可配 ms，分配器自查） */
    private volatile long balanceIntervalNs;
    /** 上次均衡时间戳（ns；自查：不依赖外部更新） */
    private volatile long lastBalanceNs;
    /** 最近一次均衡选中的 Worker（间隔内复用） */
    private volatile ThreadWorker cachedBest;
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
        final TaskMode mode;              // ★ 2026-09-05 执行模式（NORMAL=虚拟线程 / EXCLUSIVE / PHYSICS_HIGH=独占直算）
        final long periodNanos;   // 0 = 一次性
        long nextFireNanos;
        volatile boolean cancelled;

        ScheduledTask(Runnable action, long initialDelayNanos, long periodNanos) {
            this(action, TaskMode.NORMAL, initialDelayNanos, periodNanos);
        }

        ScheduledTask(Runnable action, TaskMode mode, long initialDelayNanos, long periodNanos) {
            this.action = action;
            this.mode = mode;
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
        // 2026-08-29 线程池自注册：HUD/调试可枚举所有线程池
        ThreadDispatchers.registerPool(this);
    }

    /** 分配器名（2026-08-29 HUD/调试用） */
    public String name() {
        return name;
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
        return schedule(action, TaskMode.NORMAL, initialDelayUs, periodUs);
    }

    /** ★ 2026-09-05 注册周期定时任务 +【执行模式】（NORMAL=虚拟线程 / EXCLUSIVE /
     *  PHYSICS_HIGH=独占直算；物理循环应传 PHYSICS_HIGH——常驻 Worker 直算、最高优先级）。 */
    public ScheduledHandle schedule(Runnable action, TaskMode mode,
                                    long initialDelayUs, long periodUs) {
        if (action == null) return null;
        if (periodUs <= 0) periodUs = 0;
        ScheduledTask t = new ScheduledTask(action, mode, initialDelayUs * 1000L, periodUs * 1000L);
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

    /** ★ 2026-09-05 一次性定时任务 + 执行模式。 */
    public ScheduledHandle scheduleOnce(Runnable action, TaskMode mode, long delayUs) {
        return schedule(action, mode, delayUs, 0);
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
                    // 提交到统一 Worker 池执行（线程数受限、优先空闲）；
                    // ★ 2026-09-05 按任务 mode 提交（NORMAL=虚拟线程 / EXCLUSIVE / PHYSICS_HIGH=独占直算）
                    submitGeneric(toRun.action, toRun.mode);
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

    /**
     * 设置负载均衡检测间隔（ms；2026-08-29 用户：每隔一定时间检测，分配器自查、
     * 不依赖外部更新）。0 = 每次提交实时均衡。
     */
    public void setBalanceIntervalMs(long ms) {
        this.balanceIntervalNs = Math.max(0, ms) * 1_000_000L;
        this.lastBalanceNs = System.nanoTime();
        this.cachedBest = null; // 间隔变化 → 下一提交立即重新均衡
    }

    /** 均衡检测间隔（ms；0=实时） */
    public long balanceIntervalMs() {
        return balanceIntervalNs / 1_000_000L;
    }
    /** 每个 Worker 最大管理的虚拟线程数（配置值；2026-08-16 用户要求） */
    public int maxVirtualThreads() { return maxVirtualThreads; }

    /**
     * 申请一条【常驻固定线程】并登记进常驻池：把虚拟线程配额里的 1 个名额永久移出，转交给它。
     *
     * <p>用户 2026-09-14 定稿："如果虚拟线程+固定的话可以在线程池永久移出一个虚拟线程放置在
     * 此线程常驻池那边，并且分配从 1000 降低到 999（比如）。"
     *
     * @param allowOtherTasks 该线程<b>是否支持其他任务占用</b>（用户："默认为 true，这样的话
     *                        常驻池也能作为虚拟线程池一部分使用"；false = 独占，只服务投递给
     *                        它的消息 —— 实时性要求高的设备线程用这个）
     * @return 新建的常驻线程（可 {@code post} 绑定线程的消息）
     */
    public PinnedWorker acquirePinnedSlot(String name, boolean allowOtherTasks) {
        pinnedSlots.incrementAndGet();
        int effective = effectiveMaxVirtualThreads();
        for (ThreadWorker w : workers) {
            w.setMaxVirtualThreads(effective);   // 已在跑的消费者不动，只是不再增长到旧上限
        }
        PinnedWorker p = new PinnedWorker(name, allowOtherTasks, this);
        pinned.add(p);
        return p;
    }

    /** 当前常驻线程清单（诊断；虚拟线程池的清理不会动它们） */
    public java.util.List<PinnedWorker> pinnedWorkers() {
        return new ArrayList<>(pinned);
    }

    /**
     * 释放一条【常驻固定线程】（2026-09-15，与 {@link #acquirePinnedSlot} 对称）。
     *
     * <p>用途：负载自适应调度（如游戏内 SoC 芯片——轻负载走共享池、重负载升为独占，
     * 负载回落后需降级并<b>归还名额</b>，否则反复升降级会持续泄漏线程）。</p>
     *
     * <p>三步：从常驻池移除 → 归还虚拟线程名额（总量守恒）→ 关闭其专属平台线程。</p>
     *
     * @return true = 确实由本分配器释放；false = 不属于本分配器（无操作）
     */
    public boolean releasePinnedSlot(PinnedWorker worker) {
        if (worker == null || !pinned.remove(worker)) {
            return false;
        }
        pinnedSlots.updateAndGet(v -> Math.max(0, v - 1));
        final int effective = effectiveMaxVirtualThreads();
        for (ThreadWorker w : workers) {
            w.setMaxVirtualThreads(effective);   // 名额归还 → 虚拟线程上限恢复
        }
        try {
            worker.dispatcher().close();          // 关闭承载它的专属线程（空闲 park 中）
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 取走某任务的等待 future（常驻线程执行完承接的任务时回填结果用） */
    java.util.concurrent.CompletableFuture<ComputeResult> takeResult(long taskId) {
        return results.remove(taskId);
    }

    /** 已被常驻固定线程占用的虚拟线程名额数（诊断） */
    public int pinnedSlots() { return pinnedSlots.get(); }

    /**
     * 当前<b>生效</b>的"每 Worker 虚拟线程上限" = 配置值 − 常驻固定线程占用的名额。
     * <p>配置值 0 = 明确禁用虚拟线程，语义不变；否则至少保留 1，不把虚拟线程池饿死。
     */
    public int effectiveMaxVirtualThreads() {
        if (maxVirtualThreads <= 0) {
            return maxVirtualThreads;
        }
        return Math.max(1, maxVirtualThreads - pinnedSlots.get());
    }

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
     * 实际使用的线程数（2026-08-29 用户：线程数应显示实际使用量，不是全部核心）。
     * 当前有负载（排队/执行中）的 Worker 数——已创建但空闲的线程不算“使用”。
     */
    public int usedThreadCount() {
        int n = 0;
        for (ThreadWorker w : workers) if (w.load() > 0) n++;
        return n;
    }

    /**
     * 线程分发统计（调试，2026-08-12 用户要求）：每个 Worker 累计处理任务数
     * 与当前负载（空闲/忙）。调用方按需每秒打印（如 PhasorEngine 配置开关）。
     * 2026-08-29 增加【每秒处理任务数】与虚拟线程使用量/最大值（HUD 用）。
     */
    public String stats() {
        StringBuilder sb = new StringBuilder("ThreadDispatcher{").append(name)
                .append(" usedThreads=").append(usedThreadCount()).append('/')
                .append(maxThreads)
                .append(", maxVt/worker=").append(effectiveMaxVirtualThreads())
                .append(pinnedSlots.get() > 0 ? "(pinned-" + pinnedSlots.get() + ")" : "")
                .append(", totalLoad=").append(totalLoad()).append('}');
        for (ThreadWorker w : workers) {
            sb.append('\n').append("  ").append(w.workerName());
            if (w.maxVirtualThreads() > 0) {
                sb.append(" vt=").append(w.activeVirtualThreads())
                        .append('/').append(w.maxVirtualThreads());
            }
            sb.append(" load=").append(w.load())
                    .append(String.format(" %.0f/s", w.processedPerSecond()))
                    .append(String.format(" vt%.0f/s", w.vtCreatedPerSecond()))
                    .append(" processed=").append(w.processed())
                    .append(w.idle() ? " (idle)" : " (busy)");
        }
        return sb.toString();
    }

    /**
     * 每 Worker 一行的负载摘要（HUD 用，2026-08-29）。
     * 行格式：&lt;worker&gt; | load=&lt;排队+执行中&gt; | vt=&lt;使用/最大&gt; |
     * &lt;xx.x&gt;/s=每秒处理 | processed=&lt;累计&gt; | idle/busy
     */
    public java.util.List<String> workerSummaryLines() {
        java.util.List<String> out = new ArrayList<>(maxThreads);
        for (ThreadWorker w : workers) {
            StringBuilder sb = new StringBuilder().append(w.workerName());
            if (w.maxVirtualThreads() > 0) {
                sb.append("  vt ").append(w.activeVirtualThreads())
                        .append('/').append(w.maxVirtualThreads());
            }
            sb.append("  load ").append(w.load())
                    .append(String.format("  %.1f/s", w.processedPerSecond()))
                    .append(String.format("  vt%.1f/s", w.vtCreatedPerSecond()))
                    .append("  proc ").append(w.processed())
                    .append(w.idle() ? "  idle" : "  busy");
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * 选择线程（2026-08-29 用户：均衡每隔一定时间检测，单位 ms，分配器自查）。
     * <p>
     * 自适应：
     * <ul>
     *   <li>均衡间隔开启（&gt;0）：间隔内复用最近一次均衡选中的空闲 Worker
     *       （{@link #cachedBest}），无需每次提交扫描——负载检测与单位时间统计
     *       同源（懒时间戳，不依赖外部 ServerTick 更新）；若缓存忙则临时
     *       回退到空闲中最闲者，避免间隔内压单线程；</li>
     *   <li>间隔 0 = 每次提交实时均衡：空闲中选累计处理最少者，全忙选最低负载。</li>
     * </ul>
     * 统一管理下线程数受限，绝不创建新线程。
     */
    private ThreadWorker pickWorker() {
        long interval = balanceIntervalNs;
        if (interval > 0) {
            long now = System.nanoTime();
            if (now - lastBalanceNs < interval) {
                // 间隔内：缓存空闲 → 直接复用（自查低频，无外部驱动）
                ThreadWorker c = cachedBest;
                if (c != null && c.idle()) return c;
                // 缓存忙 → 找空闲中最闲的；仍无则用缓存（下一间隔再均衡）
                ThreadWorker idle = pickIdleLeastProcessed();
                if (idle != null) return idle;
                if (c != null) return c;
                return pickMinLoad();
            }
            // 间隔到 → 重新均衡并缓存（时间戳自查）
            ThreadWorker best = pickBalanced();
            lastBalanceNs = now;
            cachedBest = best;
            return best;
        }
        // 实时模式：每次提交均衡
        ThreadWorker idle = pickIdleLeastProcessed();
        if (idle != null) return idle;
        return pickMinLoad();
    }

    /** 空闲 Worker 中【累计处理最少】（长期均衡） */
    private ThreadWorker pickIdleLeastProcessed() {
        ThreadWorker idleBest = null;
        for (ThreadWorker w : workers) {
            if (!w.idle()) continue;
            if (idleBest == null || w.processed() < idleBest.processed()) idleBest = w;
        }
        return idleBest;
    }

    /** 全忙：当前负载最低 */
    private ThreadWorker pickMinLoad() {
        ThreadWorker best = workers.get(0);
        for (ThreadWorker w : workers) {
            if (w.load() < best.load()) best = w;
        }
        return best;
    }

    /** 全面均衡（空闲中最闲；无空闲则最低负载）并作缓存候选 */
    private ThreadWorker pickBalanced() {
        ThreadWorker idle = pickIdleLeastProcessed();
        return idle != null ? idle : pickMinLoad();
    }

    /**
     * 选择任务落点（用户 2026-09-14 定稿："分配时优先分配任务到空闲的池，只有全部分配完成后
     * 再使用常驻池"）：
     * <ol>
     *   <li>有空闲的普通 Worker ⇒ 用它（虚拟线程池优先）；</li>
     *   <li>普通 Worker 全都有任务在身 ⇒ 交给常驻池里"允许被占用且当前空闲"的那条
     *       （常驻池因此也能作为虚拟线程池的一部分使用）；</li>
     *   <li>都没有 ⇒ 老实排队，按均衡规则落到负载最低的 Worker。</li>
     * </ol>
     */
    private TaskSink pickSink() {
        ThreadWorker idle = pickIdleLeastProcessed();
        if (idle != null) {
            return idle;
        }
        PinnedWorker free = pickFreePinned();
        if (free != null) {
            return free;
        }
        return pickWorker();
    }

    /** 常驻池里可被普通任务占用、且当前空闲的一条 */
    private PinnedWorker pickFreePinned() {
        for (PinnedWorker p : pinned) {
            if (p.allowOtherTasks() && !p.busy()) {
                return p;
            }
        }
        return null;
    }

    /** 异步提交任务（不阻塞调用方）：选落点投递，结果经 CompletableFuture 获取 */
    public CompletableFuture<ComputeResult> submit(ComputeTask task) {
        CompletableFuture<ComputeResult> f = new CompletableFuture<>();
        results.put(task.id, f);
        pickSink().submit(task);
        return f;
    }

    /**
     * 异步提交【通用分发任务】（2026-08-12 用户要求）：线程分发基类/管理器把
     * 独立计算（温度推进等额外计算）打包成 Runnable → 选 Worker（优先空闲 →
     * 最低负载）在此线程执行。不走网络求解结果表，返回 future 等待完成。
     * 默认普通模式：虚拟线程执行（2026-08-16 用户要求）。
     */
    public CompletableFuture<Void> submitGeneric(Runnable r) {
        return pickSink().submitGeneric(r, TaskMode.NORMAL);
    }

    /**
     * 异步提交通用分发任务 + 指定执行模式（2026-08-16 用户要求）。
     * {@link TaskMode#NORMAL} 走虚拟线程；{@link TaskMode#EXCLUSIVE} 独占直算。
     */
    public CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode) {
        return pickSink().submitGeneric(r, mode);
    }

    /**
     * 提交任务并【阻塞等待完成，附带超时】（2026-08-30 用户需求：
     * 发送任务时可附带超时时间，timeoutNanos <= 0 = 无限等待）。
     * <p>超时返回后任务【仍在执行】（虚拟线程/JNI 挂起不可取消）——调用方
     * 通过返回 future 的 {@code isDone()} 判断是否超时并决定是否放弃结果。
     */
    public CompletableFuture<Void> submitGenericTimed(Runnable r, TaskMode mode,
                                                      long timeoutNanos) {
        CompletableFuture<Void> f = pickSink().submitGeneric(r, mode);
        try {
            if (timeoutNanos > 0) {
                f.get(timeoutNanos, TimeUnit.NANOSECONDS);
            } else {
                f.get(); // 无限等待
            }
        } catch (TimeoutException te) {
            return f; // 超时：future 未完成（任务仍在执行）
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ee) {
            // 任务内部异常 → future 已完成（isDone()=true）
        }
        return f;
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
        // ⚠ 只收敛【虚拟线程池】的 Worker：常驻池（pinned）是"必须绑定线程的任务"的载体，
        //   用户定稿"清理虚拟内存池缓存时不动常驻池" —— 它们由各自的持有方决定生命周期。
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
