package com.hdf.cryptand.core.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 异步操作句柄（2026-08-13 用户要求：异步需要 wait 函数，且等待时间分两种——
 * 微秒级 {@link #waitUs} 与正常毫秒级 {@link #wait}）。
 * <p>
 * 由 {@link SqliteStore#asyncWrite} 及各表异步方法返回。等待方式：
 *   - {@link #waitFor()}：无限等待完成
 *   - {@link #waitFor(long)}：毫秒超时等待（正常粒度），返回是否完成
 *   - {@link #waitUs(long)}：微秒超时等待（高精度，底层 parkNanos），返回是否完成
 *   - {@link #thenRun} / {@link #whenComplete}：消息通知方式（完成回调，不阻塞）
 *   - {@link #isDone()}：是否已完成
 * <p>
 * 同步接口也返回本类型（{@link #done()} 已完成的实例）——同步/异步调用方可
 * 统一使用 waitFor/waitUs 接口，代码直接互换（同步立即完成）。
 * <p>
 * 等待超时返回 false（未完成）；异步写失败 → future 异常完成 → wait 返回 true
 * （完成），但 {@link #waitFor()} 会抛 {@link java.util.concurrent.CompletionException}。
 */
public final class AsyncOp {

    private static final AsyncOp DONE = new AsyncOp(CompletableFuture.completedFuture(null));

    private final CompletableFuture<Void> future;

    AsyncOp(CompletableFuture<Void> future) {
        this.future = future;
    }

    /** 已完成的 AsyncOp（同步接口包装用：同步操作立即完成，统一 wait/waitUs 接口） */
    public static AsyncOp done() {
        return DONE;
    }

    /** 无限等待完成（失败抛 CompletionException） */
    public void waitFor() {
        future.join();
    }

    /** 正常等待：毫秒超时。true=已完成，false=超时（未完成）。
     *  ⚠ 命名用 waitFor（非 wait）——避免与 Object.wait(long) 签名冲突。 */
    public boolean waitFor(long timeoutMillis) {
        return wait0(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 微秒级等待：微秒超时（高精度，底层 parkNanos）。true=已完成，false=超时 */
    public boolean waitUs(long timeoutMicros) {
        return wait0(timeoutMicros, TimeUnit.MICROSECONDS);
    }

    private boolean wait0(long timeout, TimeUnit unit) {
        try {
            future.get(timeout, unit);
            return true;
        } catch (TimeoutException e) {
            return false; // 超时未完成
        } catch (Exception e) {
            return true;  // 异常完成也算完成（waitFor 会抛）
        }
    }

    /** 是否已完成（含异常完成） */
    public boolean isDone() {
        return future.isDone();
    }

    /** 消息通知：完成（成功）时回调，返回本句柄可链式 */
    public AsyncOp thenRun(Runnable action) {
        future.thenRun(action);
        return this;
    }

    /** 消息通知：完成（成功或失败）时回调 */
    public AsyncOp whenComplete(java.util.function.BiConsumer<? super Void, ? super Throwable> action) {
        future.whenComplete(action);
        return this;
    }

    /** 底层 future（高级用法：get/thenApply 等） */
    public CompletableFuture<Void> future() {
        return future;
    }
}
