package com.hdf.cryptand.mcp;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * 工具执行调度（<b>平台实现的核心 SPI</b>）。
 *
 * <p>MCP 请求来自传输线程（HTTP 工作线程 / stdin 读线程），但工具操作的游戏对象
 * （世界、界面、渲染）<b>只能在宿主线程碰</b>。框架把"执行"抽象出来：</p>
 *
 * <ul>
 *   <li>平台实现：把任务排进宿主线程队列（如 MC 客户端 tick），返回的 Future 在任务
 *       真正执行完后完成；请求线程可以安全地 {@code get(timeout)} 等待；</li>
 *   <li>{@link #DIRECT}：调用线程直接执行（独立进程 / 自测用）；</li>
 *   <li>面向"跨帧长任务"的平台实现（如截图要等渲染稳定）应自行管理分帧推进，
 *       把 Future 在最终完成后 complete 即可 —— 框架不做任何线程假设。</li>
 * </ul>
 */
public interface McpExecutor {

    /** 把任务投递到宿主线程执行（实现方负责保证执行一次、异常不外逃） */
    void execute(Runnable task);

    /** 提交有返回值的任务（Future 在宿主线程执行完毕后完成） */
    default <T> CompletableFuture<T> submit(Callable<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
        } catch (Throwable ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    /** 调用线程直接执行（无线程切换；自测与独立进程默认） */
    McpExecutor DIRECT = Runnable::run;
}
