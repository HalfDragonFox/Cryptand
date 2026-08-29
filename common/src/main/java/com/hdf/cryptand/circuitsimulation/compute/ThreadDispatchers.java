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

    /** 全局触发/中断调度器：注册触发向量（ISR）+ trigger（消息到达触发处理） */
    public static TriggerDispatcher triggers() {
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

    /** 全局触发调度器是否已创建 */
    public static boolean triggersInitialized() {
        return triggerInstance != null;
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

    /** 异步提交通用计算任务（Runnable 在受限 Worker 池执行；不绑定任何载荷类型） */
    public static CompletableFuture<Void> submitGeneric(Runnable r) {
        return get().submitGeneric(r);
    }

    /** 异步提交通用计算任务 + 指定执行模式（普通=虚拟线程 / 独占=常驻线程直算） */
    public static CompletableFuture<Void> submitGeneric(Runnable r, TaskMode mode) {
        return get().submitGeneric(r, mode);
    }

    /** 独占模式提交通用计算任务（跳过虚拟线程；一般不要使用，易卡死） */
    public static CompletableFuture<Void> submitExclusive(Runnable r) {
        return get().submitExclusive(r);
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
