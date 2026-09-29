package com.hdf.cryptand.neoforge.cryptandsable.core.allocator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程分配器（ThreadAllocator）。
 *
 * <p>按需为核心任务分配线程（对齐 C6）：多个物理结构各自独立 scene → 可并行进 native。
 * 每个物理结构/结构组可独占一个执行线程（worker 池），实现多 scene 并行（C12 compatible）。
 *
 * <p>架构：核心主 worker（SableWorker）负责心跳/命令调度；本分配器提供额外的并行执行池
 * （例如每个 scene 一个专用线程跑该 scene 的批量步进）。池内线程只做纯计算，不碰 Level。
 */
public final class SableThreadAllocator {
    /** 默认并行度（最多 scene 数上限，保守）。 */
    public static final int DEFAULT_POOL_SIZE = 4;

    private final int poolSize;
    private final ExecutorService pool;
    private final AtomicInteger counter = new AtomicInteger(1);

    public SableThreadAllocator() {
        this(DEFAULT_POOL_SIZE);
    }

    public SableThreadAllocator(int poolSize) {
        this.poolSize = Math.max(1, poolSize);
        this.pool = Executors.newFixedThreadPool(this.poolSize, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cryptand-sable-scene-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 提交一个 scene 任务（该任务必须是纯 native/纯计算，不碰 Level）。 */
    public void submit(int sceneId, Runnable task) {
        pool.submit(task);
    }

    public int poolSize() {
        return poolSize;
    }

    public void shutdown() {
        pool.shutdown();
    }
}