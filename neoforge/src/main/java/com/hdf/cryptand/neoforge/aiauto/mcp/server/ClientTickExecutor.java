package com.hdf.cryptand.neoforge.aiauto.mcp.server;

import com.hdf.cryptand.mcp.McpExecutor;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===== 客户端 tick 执行器（平台实现：MCP → MC 主线程）=====
 *
 * <p>MCP 请求来自 HTTP 工作线程，而游戏对象（世界/界面/渲染）只能在客户端主线程碰。
 * 本类把工具调用排进队列，由 {@link #tick()}（挂在 {@code ClientTickEvent.Post}）
 * 在主线程逐个执行 —— 框架返回的 Future 因此在主线程执行完毕后完成，
 * <b>HTTP 线程照常阻塞等待，客户端看到的是同步语义</b>。</p>
 *
 * <h3>铁律</h3>
 * <ul>
 *   <li>任务里<b>绝不能</b>再 {@code submit(...).join()} 等自己 —— 主线程会被自己等死
 *       （需要跨帧推进的流程请用 {@code run_plan} + {@code plan_status} 的轮询式接口）；</li>
 *   <li>单帧任务数有上限（{@link #FRAME_BUDGET}），防止一次涌入把帧卡穿；</li>
 *   <li>异常一律就地吞掉并计数，绝不让它逃进 MC 的事件循环。</li>
 * </ul>
 */
public final class ClientTickExecutor implements McpExecutor {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 单帧最多执行的任务数（保护帧率；剩余任务下一帧继续） */
    private static final int FRAME_BUDGET = 64;

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    @Override
    public void execute(Runnable task) {
        if (task == null) {
            return;
        }
        submitted.incrementAndGet();
        queue.add(task);
    }

    /** 客户端每帧调用（主线程）：执行本帧预算内的任务 */
    public void tick() {
        int budget = FRAME_BUDGET;
        Runnable task;
        while (budget-- > 0 && (task = queue.poll()) != null) {
            try {
                task.run();
                executed.incrementAndGet();
            } catch (Throwable ex) {
                failed.incrementAndGet();
                LOGGER.warn("[aiauto/mcp] 主线程任务异常", ex);
            }
        }
    }

    public int pending() {
        return queue.size();
    }

    public long submitted() {
        return submitted.get();
    }

    public long executed() {
        return executed.get();
    }

    public long failed() {
        return failed.get();
    }
}
