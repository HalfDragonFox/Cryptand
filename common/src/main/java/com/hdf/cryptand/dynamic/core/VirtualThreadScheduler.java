package com.hdf.cryptand.dynamic.core;

import com.hdf.cryptand.dynamic.api.DynamicScheduler;
import com.hdf.cryptand.dynamic.api.Stage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * ===== 默认调度器（离线自测 / common 侧）=====
 *
 * <p>用户定案（2026-09-29）：「使用虚拟线程最好」——后台阶段走
 * {@link Executors#newVirtualThreadPerTaskExecutor()}；主线程回调在离线环境直接执行
 * （MC 侧用适配器改为 {@code mc.execute}，并把 background 接到项目
 * {@code ThreadDispatchers} 的 {@code TaskMode.NORMAL}）。</p>
 *
 * <p><b>绝不使用独占 Worker</b>（TaskMode.EXCLUSIVE 的源码注释明确"不建议，易卡死"）。</p>
 */
public final class VirtualThreadScheduler implements DynamicScheduler {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong epoch = new AtomicLong();
    /** 离线/服务端：主线程回调直接执行（不能后台执行的东西仍会被串行调用）。 */
    private volatile boolean directMain = true;

    @Override
    public <T> CompletableFuture<T> background(Stage stage, Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, pool);
    }

    @Override
    public void mainThread(Runnable task) {
        if (directMain) {
            task.run();
        } else {
            CompletableFuture.runAsync(task, pool);
        }
    }

    @Override
    public long epoch() {
        return epoch.get();
    }

    /** 递增代次（框架在每次 scan/reload 前调用）。 */
    public long nextEpoch() {
        return epoch.incrementAndGet();
    }

    public void setDirectMain(boolean direct) {
        this.directMain = direct;
    }

    public void close() {
        pool.close();
    }
}
