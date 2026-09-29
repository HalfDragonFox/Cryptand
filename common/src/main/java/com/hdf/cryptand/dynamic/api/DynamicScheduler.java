package com.hdf.cryptand.dynamic.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * ===== 执行器抽象（基础核心只依赖它）=====
 *
 * <p>MC 侧适配器接到项目多线程分配核心：{@code background} → {@code TaskMode.NORMAL}
 * （虚拟线程），{@code mainThread} → {@code mc.execute}。离线实现 = 虚拟线程池 + 直接执行。</p>
 *
 * <p><b>注意</b>：项目 {@code submitGenericTimed} 的语义是"超时后任务仍在执行、不可取消"
 * ⇒ 框架不得依赖 {@code future.cancel}，一律"结果落地前校验 {@link #epoch()}，过期即丢"。</p>
 */
public interface DynamicScheduler {

    <T> CompletableFuture<T> background(Stage stage, Supplier<T> task);

    void mainThread(Runnable task);

    /** 当前代次（每次 scan/reload 递增；后台结果落地前校验）。 */
    long epoch();
}
