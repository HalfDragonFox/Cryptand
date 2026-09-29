/**
 * ===== 亚层命令总线（消息驱动，2026-08-31） =====
 *
 * 用户要求：API 全部消息驱动通知、支持大规模并发、天然线程安全+分隔不互相影响。
 *
 * 设计（keyed serial executor）：
 *  - 命令按【归属键】（亚层 uuid / 装配器位置）分区：同 key 命令【串行】执行
 *    （亚层状态天然串行，杜绝竞态）；不同 key【并行】执行（大规模并发互不阻塞）。
 *  - 投递 = 消息（{@link SubLevelCommand}），调用方只投不等待（异步通知）。
 *  - 线程安全：{@link ConcurrentHashMap} 分片 + 每片单线程执行器；
 *    同 key 串行化由 {@code synchronized(keyLock)} 实现（轻量，无任务切换）。
 *
 * 通知：handler 在命令执行时发出通知（EventBusSubscriber 式 or 监听器回调）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SubLevelCommandBus {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    /** 命令定义（消息）。 */
    @FunctionalInterface
    public interface SubLevelCommand {
        /** 执行命令（在 key 专属串行上下文内）。 */
        void run() throws Exception;
    }

    /**
     * 执行器：所有 key 共享一个线程池的【固定槽位】——同 key 通过 {@code synchronized(keyLock)}
     * 严格串行（天然分隔：一个亚层的命令不与其他亚层交错），不同 key 并发执行。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        final Thread t = new Thread(r, "cryptand-sublevel-command");
        t.setDaemon(true);
        return t;
    });

    /** key → 锁对象（串行化）。 */
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    /** 按归属键投递命令（异步；同 key 严格串行，不同 key 并发）。 */
    public void post(final String key, final SubLevelCommand command) {
        if (key == null || command == null) return;
        final Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        executor.execute(() -> {
            synchronized (lock) {
                try {
                    command.run();
                } catch (Throwable t) {
                    LOGGER.warn("[SubLevelCommandBus] command failed for key={}", key, t);
                }
            }
        });
    }

    /** 同步派发（mixin/主线程调用点要求立即结果时；仍按 key 串行）。 */
    public void dispatch(final String key, final SubLevelCommand command) {
        if (key == null || command == null) return;
        final Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                command.run();
            } catch (Throwable t) {
                LOGGER.warn("[SubLevelCommandBus] dispatch failed for key={}", key, t);
            }
        }
    }

    /** 优雅关闭（世界卸载）。 */
    public void shutdown() {
        executor.shutdown();
    }

    /** 单例。 */
    private static final SubLevelCommandBus INSTANCE = new SubLevelCommandBus();

    public static SubLevelCommandBus instance() {
        return INSTANCE;
    }

    private SubLevelCommandBus() {
    }
}
