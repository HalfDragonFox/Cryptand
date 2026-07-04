/**
 * ===== PowerGrid 多线程化 - 线程池 =====
 *
 * 工作偷取（Work-Stealing）线程池，用于并行执行 ElectricalNetwork.singleTick()。
 *
 * 设计决策：
 *   - 使用 FixedThreadPool 而非 ForkJoinPool（work-stealing）。
 *     PowerGrid 0.5.5.1 使用 NativeMNA（JNI native求解器），
 *     native 代码会回调 Java 层的钩子方法（如 runIterHooks）。
 *     在 ForkJoinPool 上，work-stealing 机制会把这些回调嵌套到
 *     当前线程栈上执行，导致 Java→native→Java→native 无限递归，最终
 *     StackOverflowError。
 *
 *   - FixedThreadPool 的线程没有 work-stealing 行为。即使 native
 *     回调触发了新的任务提交，新任务会进入队列等待其他线程执行，
 *     不会在同一栈帧上嵌套。
 *
 *   - 线程以守护模式运行（daemon=true），JVM 关闭时自动回收。
 *     线程命名格式为 "Cryptand-Solver-{hash}"，便于日志追踪。
 *
 * 使用方式：
 *   WorkStealingPool pool = new WorkStealingPool();
 *   CompletableFuture.runAsync(task, pool.getExecutor()).join();
 *   pool.close();  // 世界卸载时关闭
 *
 * 注意：尽管类名为 WorkStealingPool，出于历史兼容性保留此名称。
 *       实际底层是 FixedThreadPool。
 */

package com.hdf.cryptand.neoforge.threading;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WorkStealingPool implements AutoCloseable {
    private final ExecutorService executor;
    private final int parallelism;

    public WorkStealingPool() {
        this.parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "Cryptand-Solver-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        CryptandNeoForge.WAF_LOGGER.info("WorkStealingPool initialized with parallelism={} (fixed pool)", parallelism);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public int getParallelism() {
        return parallelism;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
