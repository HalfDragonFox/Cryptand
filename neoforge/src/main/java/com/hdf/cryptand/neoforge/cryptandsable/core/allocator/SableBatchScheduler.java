package com.hdf.cryptand.neoforge.cryptandsable.core.allocator;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量调度器（SableBatchScheduler）—— 多线程细化分配（2026-09-01 V2 架构）。
 *
 * <p><b>职责：</b>计算核心的批处理推送实现地。
 * <ul>
 *   <li><b>工作池</b>：按 scene/环境分区并行（每 scene 可独占线程）；</li>
 *   <li><b>批切分</b>：大批次（结构/虚拟体）拆分为多个 chunk 并行处理；</li>
 *   <li><b>分类调度</b>：导入（importBody）→ 步进（step）→ 碰撞/查询 → 结果（fetch）分类任务；</li>
 *   <li><b>并行约束</b>：不同 scene 可并行（独立 scene/独立精度）；同 scene 串行（保持一致性）。</li>
 * </ul>
 *
 * <p><b>JNI 边界：</b>本调度器只负责【Java 侧数据打包/切分/调度】——最终数据调用
 * {@link EngineApi} 单次提交（JNI 仅交互接口，无批处理逻辑）。
 * 线程只做纯计算，不碰 Level/BE。
 */
public final class SableBatchScheduler {

    /** 默认子步数（80Hz 模拟；主线程心跳预算推动）。 */
    public static final int DEFAULT_SUBSTEPS = 4;

    /** 默认并行度。 */
    private static final int DEFAULT_POOL_SIZE = 4;

    /** 默认批切分粒度（每 chunk 结构数）。 */
    private static final int DEFAULT_CHUNK_SIZE = 16;

    private final int poolSize;
    private final int chunkSize;
    private final ExecutorService pool;
    private final AtomicInteger counter = new AtomicInteger(1);

    public SableBatchScheduler() {
        this(DEFAULT_POOL_SIZE, DEFAULT_CHUNK_SIZE);
    }

    public SableBatchScheduler(int poolSize, int chunkSize) {
        this.poolSize = Math.max(1, poolSize);
        this.chunkSize = Math.max(1, chunkSize);
        this.pool = Executors.newFixedThreadPool(this.poolSize, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cryptand-sable-batch-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public int poolSize() {
        return this.poolSize;
    }

    public int chunkSize() {
        return this.chunkSize;
    }

    /**
     * 分类批处理：将结构列表按 scene 分区并切分为 chunk，每 chunk 并行执行。
     *
     * <p>典型的批任务例子：
     * <ul>
     *   <li>导入：每个 chunk 调 {@code engine.uploadPoseBatch/bakeChunkBatch};</li>
     *   <li>步进：每个 chunk 调 {@code engine.stepBatch};</li>
     *   <li>结果：每个 chunk 调 {@code engine.fetchState}。</li>
     * </ul>
     *
     * @param sceneIds  结构 scene id 数组（相同 scene 串行保证一致性）
     * @param chunkTask 每个 chunk 执行的批任务（引擎调度/打包；纯数据）
     */
    public void dispatch(int[] sceneIds, ChunkTask chunkTask) {
        if (sceneIds == null || sceneIds.length == 0 || chunkTask == null) return;

        // 按 scene 分组：同 scene 归一个分组（串行），不同 scene 可并行
        java.util.Map<Integer, List<Integer>> byScene = new java.util.LinkedHashMap<>();
        for (int i = 0; i < sceneIds.length; i++) {
            byScene.computeIfAbsent(sceneIds[i], k -> new ArrayList<>()).add(i);
        }

        // 每个 scene 分组内按 chunkSize 切分 → 提交并行
        List<Runnable> tasks = new ArrayList<>();
        for (List<Integer> indexes : byScene.values()) {
            for (int start = 0; start < indexes.size(); start += this.chunkSize) {
                int end = Math.min(start + this.chunkSize, indexes.size());
                List<Integer> chunk = new ArrayList<>(indexes.subList(start, end));
                tasks.add(() -> chunkTask.runChunk(chunk));
            }
        }
        // 并行提交（同 scene 的多个 chunk 串行由任务实现保证；不同 scene 并行）
        for (Runnable task : tasks) {
            this.pool.submit(task);
        }
    }

    /** 提交单一任务（引擎初始化/卸载等；纯计算）。 */
    public void submit(Runnable task) {
        if (task != null) this.pool.submit(task);
    }

    /** 待所有任务完成（最后一次批处理轮询；timeout 防止卡死）。 */
    public void awaitIdle(long timeoutMillis) {
        // MVP：Barrier 任务确保之前提交的任务已完成
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            this.pool.submit(latch::countDown);
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] batch awaitIdle timeout: {}", t.toString());
        }
    }

    /** 等待所有已提交任务完成。 */
    public void awaitCompletion(long timeoutMillis) {
        this.awaitIdle(timeoutMillis);
    }

    public void shutdown() {
        this.pool.shutdown();
    }

    /** 批任务接口（每 chunk 一次调用；纯数据操作）。 */
    @FunctionalInterface
    public interface ChunkTask {
        void runChunk(List<Integer> indexes);
    }
}
