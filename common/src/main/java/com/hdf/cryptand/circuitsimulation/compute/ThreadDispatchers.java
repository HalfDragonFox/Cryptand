package com.hdf.cryptand.circuitsimulation.compute;

import java.util.concurrent.CompletableFuture;

/**
 * 通用线程分配器全局单例（2026-08-14 用户架构：本 mod 通用分配器，可给其他任何 mod 使用）。
 * <p>
 * 独立于任何电力/游戏逻辑：任何 mod 都可调用 {@link #get()} 获取共享
 * {@link ThreadDispatcher}，用 {@link #submitGeneric}/{@link #schedule} 提交
 * 自己的计算任务/微秒级定时任务，无需经过任何电力侧类。
 * <p>
 * 特性：
 *   - 懒启动 daemon 线程（首个 {@link #get()} 触发），随 JVM 退出。
 *   - 线程数默认 = CPU 核数 − 1；平台层（neoforge 等）可在启动时用
 *     {@link #setDefaultThreads} 按配置覆盖（须在首次 get() 前调用才生效）。
 *   - 线程命名中性（默认 {@value #DEFAULT_NAME}，可经 {@link #setName} 覆盖）。
 *   - {@link #close} 幂等。
 */
public final class ThreadDispatchers {

    /** 默认分配器名（线程前缀 = &lt;name&gt;-&lt;i&gt; / &lt;name&gt;-timer） */
    public static final String DEFAULT_NAME = "cryptand-compute";

    /** 每个 Worker 默认最大管理的虚拟线程数（2026-08-16 用户要求：默认最大 1000） */
    public static final int DEFAULT_MAX_VIRTUAL_THREADS =
            ThreadDispatcher.DEFAULT_MAX_VIRTUAL_THREADS;

    private static volatile ThreadDispatcher instance;
    private static volatile int defaultThreads =
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    /** 每个 Worker 默认最大虚拟线程数（2026-08-16 用户要求；可配置覆盖） */
    private static volatile int defaultMaxVirtualThreads = DEFAULT_MAX_VIRTUAL_THREADS;
    private static volatile String name = DEFAULT_NAME;
    /** 均衡检测间隔（ms；0=实时；2026-08-29 用户可配；懒初始化时缓存，get() 时套用） */
    private static volatile int defaultBalanceIntervalMs = 0;

    /**
     * 设置全局负载均衡检测间隔（ms；2026-08-29 用户：每隔一定时间检测，分配器
     * 自查、不依赖外部更新；0 = 每次提交实时均衡）。懒初始化前后皆可调用
     * （get 时套用到实例）。
     */
    public static void setBalanceIntervalMs(int ms) {
        defaultBalanceIntervalMs = Math.max(0, ms);
        ThreadDispatcher d = instance;
        if (d != null) d.setBalanceIntervalMs(defaultBalanceIntervalMs);
    }

    /** 当前全局均衡检测间隔（ms；0=实时） */
    public static int balanceIntervalMs() {
        ThreadDispatcher d = instance;
        return (int) (d != null ? d.balanceIntervalMs() : defaultBalanceIntervalMs);
    }

    /**
     * 全部已创建的线程池注册表（2026-08-29 用户：HUD 显示每个线程池中每个线程
     * 的负载情况）。任何 {@link ThreadDispatcher} 构造时自注册（按 name+序号）；
     * {@link #pools()} 供 HUD/调试遍历所有池。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, ThreadDispatcher> POOLS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger POOL_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 供 ThreadDispatcher 构造时自注册（同包调用） */
    static void registerPool(ThreadDispatcher d) {
        try {
            POOLS.put(d.name() + "#" + POOL_SEQ.incrementAndGet(), d);
        } catch (Throwable ignored) {
        }
    }

    /** 当前全部线程池（HUD/调试遍历；懒初始化前可能为空） */
    public static java.util.Collection<ThreadDispatcher> pools() {
        return POOLS.values();
    }

    /** 当前线程池数量（HUD/调试） */
    public static int poolCount() {
        return POOLS.size();
    }

    private ThreadDispatchers() {
    }

    /** 覆盖默认线程数（首次 {@link #get()} 前调用才生效） */
    public static void setDefaultThreads(int threads) {
        defaultThreads = Math.max(1, threads);
    }

    /** 当前配置的默认线程数 */
    public static int defaultThreads() {
        return defaultThreads;
    }

    /** 覆盖每个 Worker 默认最大虚拟线程数（首次 {@link #get()} 前调用才生效；
     *  0 自动按默认 {@value #DEFAULT_MAX_VIRTUAL_THREADS}） */
    public static void setDefaultMaxVirtualThreads(int maxVirtualThreads) {
        defaultMaxVirtualThreads = Math.max(0, maxVirtualThreads);
    }

    /** 当前配置的每个 Worker 默认最大虚拟线程数 */
    public static int defaultMaxVirtualThreads() {
        return defaultMaxVirtualThreads;
    }

    /** 覆盖分配器名（首次 {@link #get()} 前调用才生效；线程命名中性化） */
    public static void setName(String dispatcherName) {
        if (dispatcherName != null && !dispatcherName.isEmpty()) name = dispatcherName;
    }

    /** 获取共享分配器（懒初始化，线程安全） */
    public static ThreadDispatcher get() {
        ThreadDispatcher d = instance;
        if (d == null) {
            synchronized (ThreadDispatchers.class) {
                d = instance;
                if (d == null) {
                    d = new ThreadDispatcher(defaultThreads, name, defaultMaxVirtualThreads);
                    // 套用缓存的均衡间隔（用户配置，ms）
                    if (defaultBalanceIntervalMs > 0) d.setBalanceIntervalMs(defaultBalanceIntervalMs);
                    instance = d;
                }
            }
        }
        return d;
    }

    /** 是否已初始化（懒初始化未触发则 false） */
    public static boolean isInitialized() {
        return instance != null;
    }

    // ===== 触发/中断调度器（2026-08-22 嵌入式中断/触发模式，全局单例） =====

    /** 全局触发/中断调度器（懒创建；专用虚拟线程，空闲 park 零占用） */
    private static volatile TriggerDispatcher triggerInstance;

    /**
     * ===== 线程固定选项（2026-09-14 用户定稿）=====
     *
     * "线程分配器增加线程固定选项，开启后任务激活执行只在第一次执行的线程中进行执行。"
     *
     * <p>关闭（默认）：ISR 跑在虚拟线程上（载体线程可能在 park/unpark 之间漂移）。
     * <p>开启：ISR 跑在<b>一条专用平台线程</b>上 —— 那条线程就是"第一次执行所在的线程"，
     * 此后任务激活执行永远只由它承担，OS 线程恒定。调用本机库（SDL 等，其 TLS 与
     * main-thread 断言按 OS 线程判定）时必须开启。
     */
    private static volatile boolean pinThread;

    /** 设置线程固定选项（对之后 {@link #triggers()} 的创建生效；已创建的实例不变） */
    public static void setPinThread(boolean pinned) {
        pinThread = pinned;
    }

    /** 当前线程固定选项 */
    public static boolean pinThread() {
        return pinThread;
    }

    /**
     * 全局触发/中断调度器：注册触发向量（ISR）+ trigger（消息到达触发处理）。
     * <p>跑在哪种线程上由 {@link #pinThread()} 决定：开启 ⇒ 固定平台线程；关闭 ⇒ 虚拟线程。
     */
    public static TriggerDispatcher triggers() {
        return pinThread() ? triggersPinnedIsr() : triggersVirtual();
    }

    /** 线程固定版【纯 ISR】单例（只有消息激活 + 固定 OS 线程，不含任务槽） */
    private static volatile TriggerDispatcher pinnedIsrInstance;

    private static TriggerDispatcher triggersPinnedIsr() {
        TriggerDispatcher t = pinnedIsrInstance;
        if (t == null) {
            synchronized (ThreadDispatchers.class) {
                t = pinnedIsrInstance;
                if (t == null) {
                    t = TriggerDispatcher.create("cryptand-triggers-pinned", true);
                    pinnedIsrInstance = t;
                }
            }
        }
        return t;
    }

    /** 虚拟线程版 ISR（不固定 OS 线程；纯 Java 逻辑用它即可） */
    private static TriggerDispatcher triggersVirtual() {
        TriggerDispatcher t = triggerInstance;
        if (t == null) {
            synchronized (ThreadDispatchers.class) {
                t = triggerInstance;
                if (t == null) {
                    t = TriggerDispatcher.create("cryptand-triggers");
                    triggerInstance = t;
                }
            }
        }
        return t;
    }

    /** 全局触发调度器是否已创建（任一版本） */
    public static boolean triggersInitialized() {
        return triggerInstance != null || pinnedIsrInstance != null
                || platformTriggerInstance != null;
    }

    /** 线程固定版常驻线程（懒创建；给"必须绑定线程"的调用方，如 SDL） */
    private static volatile PinnedWorker platformTriggerInstance;

    /**
     * 全局触发/中断调度器（<b>线程固定</b>版）：消息到达才唤醒处理、空闲 park 零占用，
     * 但任务激活执行永远只在<b>同一条平台线程</b>上 —— 也就是把
     * {@link #setPinThread(boolean)} 打开时 {@link #triggers()} 返回的那个实例。
     *
     * <p>给 native 调用方用（SDL 的 joystick/haptic/事件泵）：那些库的 TLS 与
     * main-thread 断言看的是 OS 线程，虚拟线程的载体漂移会让它们错乱。
     * 本调度器独立于 {@link #get()} 的 Worker 池，也不受 {@link #close()} 影响。
     */
    public static PinnedWorker triggersPlatform() {
        PinnedWorker t = platformTriggerInstance;
        if (t == null) {
            synchronized (ThreadDispatchers.class) {
                t = platformTriggerInstance;
                if (t == null) {
                    // 走【记账】路径（名额从虚拟线程配额里永久移出），并要求【独占】：
                    // 这类线程是设备/实时用途，被普通任务占用会让设备消息延迟。
                    t = pinPermanent("cryptand-triggers-native", false);
                    platformTriggerInstance = t;
                }
            }
        }
        return t;
    }

    /** 平台线程版触发调度器是否已创建 */
    public static boolean platformTriggersInitialized() {
        return platformTriggerInstance != null;
    }

    /**
     * 申请一条【常驻固定线程】（线程固定选项的落地形态）。
     *
     * <p>用户 2026-09-14 定稿："如果虚拟线程+固定的话可以在线程池永久移出一个虚拟线程放置在
     * 此线程常驻池那边，并且分配从 1000 降低到 999（比如）。"
     * ⇒ 每申请一条，分配器的虚拟线程配额<b>永久减 1</b>，那个名额转交给这条常驻平台线程；
     * 总量守恒，仍由同一个分配器统一记账（{@link #pinnedSlots()} / {@link #effectiveMaxVirtualThreads()} 可查）。
     *
     * @return 跑在这条常驻平台线程上的 ISR 调度器（空闲 park、消息到达才唤醒）
     */
    public static PinnedWorker pinPermanent(String name) {
        return pinPermanent(name, true);
    }

    /**
     * 申请一条【常驻固定线程】，并指定它<b>是否支持其他任务占用</b>。
     *
     * @param allowOtherTasks 用户定稿："任务还需要设置此线程是否支持其他任务占用，默认为 true，
     *                        这样的话常驻池也能作为虚拟线程池一部分使用。"
     *                        true ⇒ 分配器在普通 Worker 全忙时会把任务交给它（溢出层）；
     *                        false ⇒ 独占，只服务投递给它的消息（实时设备线程）。
     */
    public static PinnedWorker pinPermanent(String name, boolean allowOtherTasks) {
        return get().acquirePinnedSlot(name, allowOtherTasks);
    }

    /** 当前常驻线程清单（诊断） */
    public static java.util.List<PinnedWorker> pinnedWorkers() {
        return get().pinnedWorkers();
    }

    /** 已被常驻固定线程占用的虚拟线程名额（诊断） */
    public static int pinnedSlots() {
        return get().pinnedSlots();
    }

    /** 当前生效的每 Worker 虚拟线程上限（= 配置值 − 常驻固定线程占用） */
    public static int effectiveMaxVirtualThreads() {
        return get().effectiveMaxVirtualThreads();
    }

    // ===== 通用 API（转发到共享分配器，任何 mod 可直接调用） =====

    /** 注册周期定时任务（微秒级；到期提交到受限 Worker 池执行，线程数受限） */
    public static ScheduledHandle schedule(Runnable action, long periodUs) {
        return get().schedule(action, periodUs);
    }

    /** 注册周期定时任务（带初始延迟，微秒级） */
    public static ScheduledHandle schedule(Runnable action, long initialDelayUs, long periodUs) {
        return get().schedule(action, initialDelayUs, periodUs);
    }

    /** 注册一次性定时任务（延迟后执行一次，微秒级） */
    public static ScheduledHandle scheduleOnce(Runnable action, long delayUs) {
        return get().scheduleOnce(action, delayUs);
    }

    /** 固定周期 tick +【执行模式】（NORMAL=虚拟线程 / EXCLUSIVE / PHYSICS_HIGH=独占直算；
     *  2026-08-30 线程统一：物理循环/后台 round 用此入口注册分配核心周期 tick） */
    public static ScheduledHandle schedule(Runnable action, TaskMode mode,
                                           long initialDelayUs, long periodUs) {
        return get().schedule(action, mode, initialDelayUs, periodUs);
    }

    /** 异步提交通用计算任务（Runnable 在受限 Worker 池执行；不绑定任何载荷类型） */
    public static CompletableFuture<Void> submitGeneric(Runnable r) {
        return get().submitGeneric(r);
    }

    /** 提交任务并【阻塞等待完成，附带超时】（timeoutNanos<=0 = 无限）。
     *  2026-08-30 用户需求：线程分配核心发送任务时可附带超时时间。 */
    public static CompletableFuture<Void> submitGenericTimed(Runnable r, TaskMode mode,
                                                             long timeoutNanos) {
        return get().submitGenericTimed(r, mode, timeoutNanos);
    }

    /** 异步提交通用计算任务 + 指定执行模式（普通=虚拟线程 / 独占=常驻线程直算） */
    public static CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode) {
        return get().submitGeneric(r, mode);
    }

    /** 独占模式提交通用计算任务（跳过虚拟线程；一般不要使用，易卡死） */
    public static CompletableFuture<Void> submitExclusive(Runnable r) {
        return get().submitExclusive(r);
    }

    /** 物理专属提交（2026-08-30：高优先级 EXCLUSIVE，走常驻平台线程直算——JNI 需要） */
    public static CompletableFuture<Void> submitPhysics(Runnable r) {
        return get().submitGeneric(r, TaskMode.PHYSICS_HIGH);
    }

    /** 物理专属提交 + 指定模式（供需要虚拟线程的物理子任务使用） */
    public static CompletableFuture<Void> submitPhysics(Runnable r, TaskMode mode) {
        return get().submitGeneric(r, mode);
    }

    /** 线程分发统计（调试） */
    public static String stats() {
        return get().stats();
    }

    /** 关闭共享分配器（幂等；关闭后再次 get() 会重建） */
    public static void close() {
        ThreadDispatcher d = instance;
        if (d != null) {
            synchronized (ThreadDispatchers.class) {
                d = instance;
                if (d != null) {
                    d.close();
                    instance = null;
                }
            }
        }
    }
}
