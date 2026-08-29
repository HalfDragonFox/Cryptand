package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 定时任务句柄（2026-08-13 用户要求：微秒级定时任务）。
 * <p>
 * 由 {@link ThreadDispatcher#schedule} 系列方法返回；调用方持有句柄可取消
 * 任务或查询状态。任务到期后提交到统一 Worker 池执行（线程数受限）。
 */
public interface ScheduledHandle {

    /** 取消任务（不再触发；已在执行中的本次不受影响） */
    void cancel();

    /** 是否已取消 */
    boolean isCancelled();
}
