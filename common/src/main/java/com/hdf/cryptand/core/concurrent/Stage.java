package com.hdf.cryptand.core.concurrent;

/**
 * 调度器所处的周期阶段（主线程每 tick 推进一步）。
 *
 * <p>异步周期 = {@link #PARALLELIZING} → {@link #EXCLUSIVE}；之后进入 {@link #MAIN} 主线程周期。
 */
public enum Stage {
    /** 并行化：ASYNC+READ 正在工作线程上并行执行，等 pendingReads 归零。 */
    PARALLELIZING,
    /** 独占：ASYNC+WRITE 已派发，等 pendingWrites 归零（同区块由 {@link ChunkLocks} 互斥）。 */
    EXCLUSIVE,
    /** 主线程周期：执行 MAIN 事务。 */
    MAIN
}
