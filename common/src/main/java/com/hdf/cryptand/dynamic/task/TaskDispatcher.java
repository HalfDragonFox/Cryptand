package com.hdf.cryptand.dynamic.task;

import java.util.concurrent.CompletableFuture;

/**
 * ===== 操作派发器（common 框架工具，2026-09-29）=====
 *
 * <p>用户要求：「**主线程派发部分内容可以放到 common 作为框架工具的一部分**，
 * 外部只需要定义读写文件或者其他操作，然后设置是否多线程以及只读只写等操作，**由后端自动实现**」。</p>
 *
 * <p>本接口就是那个"框架工具的一部分"：外部把 {@link Operation} + {@link TaskSpec} 交进来，
 * 由实现决定线程与互斥。基础核心（common）只依赖本接口；MC 侧后端把主线程执行器注入进来即可
 * （与既有 {@code DynamicScheduler} 的分工一致：common 定接口、MC 注入线程能力）。</p>
 */
public interface TaskDispatcher {

    /** 按声明派发一次操作。 */
    <T> CompletableFuture<T> dispatch(TaskSpec spec, Operation<T> operation);

    /** 只读（可并行）的简写。 */
    default <T> CompletableFuture<T> read(com.hdf.cryptand.dynamic.api.Stage stage, String name,
                                          String resourceKey, Operation<T> operation) {
        return dispatch(TaskSpec.read(name, stage, resourceKey), operation);
    }

    /** 写（独占）的简写。 */
    default <T> CompletableFuture<T> write(com.hdf.cryptand.dynamic.api.Stage stage, String name,
                                           String resourceKey, Operation<T> operation) {
        return dispatch(TaskSpec.write(name, stage, resourceKey), operation);
    }

    /** 回主线程执行（返回 future，便于串接后续步骤）。 */
    default CompletableFuture<Void> mainThread(com.hdf.cryptand.dynamic.api.Stage stage, String name,
                                               Runnable task) {
        return dispatch(TaskSpec.onMainThread(name, stage), () -> {
            task.run();
            return null;
        });
    }
}
