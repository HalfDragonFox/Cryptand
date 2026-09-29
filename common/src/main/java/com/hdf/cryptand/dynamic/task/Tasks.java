package com.hdf.cryptand.dynamic.task;

import com.hdf.cryptand.dynamic.api.Stage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ===== 任务门面：外部核心"像跑 python 一样简单"（common 框架工具，2026-09-29）=====
 *
 * <p>用户要求：「框架提供**各种库工具和实现**，外部核心只需要**像 python 跑 ai 一样简单**」。</p>
 *
 * <p>所以真正给外部核心看的就是这个类 —— 不 new 对象、不传执行器、不管线程：</p>
 * <pre>
 *   // 读一个文件（自动并行）
 *   Tasks.read("config.json", () -&gt; Files.readString(path)).thenAccept(this::apply);
 *
 *   // 写一个文件（自动独占同一资源键）
 *   Tasks.write("config.json", () -&gt; { Files.writeString(path, text); return null; });
 *
 *   // 回主线程（MC 侧由后端注入 mc.execute；common/测试默认同线程）
 *   Tasks.mainThread("open-ui", () -&gt; openScreen());
 * </pre>
 *
 * <p>线程、并行、互斥、主线程派发全部由 {@link AutoTaskDispatcher} 按声明自动完成。</p>
 *
 * <p>⚠ MC 侧启动时必须调一次 {@link #installMainExecutor(Consumer)} 把主线程派发器交进来
 * （客户端 `mc::execute` / 服务端 `server::execute`）；不调则主线程任务**同线程直跑**，
 * 在 common/离线测试里这是对的，在 MC 里则会变成"在后台线程开 UI"—— 所以后端不注入就是一个 bug，
 * 这里不做静默兜底：{@link #requireMainExecutorInstalled()} 供后端自检用。</p>
 */
public final class Tasks {

    private static final AutoTaskDispatcher DEFAULT = AutoTaskDispatcher.virtualThreads();

    private static volatile boolean mainExecutorInstalled;

    private Tasks() {
    }

    /** MC 侧注入主线程派发器（幂等）。 */
    public static void installMainExecutor(Consumer<Runnable> executor) {
        DEFAULT.setMainExecutor(executor);
        mainExecutorInstalled = true;
    }

    /** 后端自检：没注入主线程派发器就是接线漏了（不要靠兜底掩盖）。 */
    public static void requireMainExecutorInstalled() {
        if (!mainExecutorInstalled) {
            throw new IllegalStateException("主线程派发器未注入：请在 MC 侧调用 Tasks.installMainExecutor(mc::execute)。"
                    + "（common 离线测试无需注入）");
        }
    }

    public static boolean mainExecutorInstalled() {
        return mainExecutorInstalled;
    }

    /** 只读操作（可并行；不参与资源互斥）。 */
    public static <T> CompletableFuture<T> read(String name, Operation<T> operation) {
        return DEFAULT.read(Stage.PROBE, name, null, operation);
    }

    /** 只读操作，声明资源键（同一键上与写者互斥，彼此可并行）。 */
    public static <T> CompletableFuture<T> read(String resourceKey, String name, Operation<T> operation) {
        return DEFAULT.read(Stage.PROBE, name, resourceKey, operation);
    }

    /** 写操作（同一资源键上独占）。 */
    public static <T> CompletableFuture<T> write(String name, Operation<T> operation) {
        return DEFAULT.write(Stage.EXTRACT, name, null, operation);
    }

    /** 写操作，声明资源键（同一键上独占，绝不并行）。 */
    public static <T> CompletableFuture<T> write(String resourceKey, String name, Operation<T> operation) {
        return DEFAULT.write(Stage.EXTRACT, name, resourceKey, operation);
    }

    /** 指定阶段 + 资源键的完整写法（核心要精细控制时用）。 */
    public static <T> CompletableFuture<T> dispatch(TaskSpec spec, Operation<T> operation) {
        return DEFAULT.dispatch(spec, operation);
    }

    /** 回主线程执行。 */
    public static CompletableFuture<Void> mainThread(String name, Runnable task) {
        return DEFAULT.mainThread(Stage.ATTACH, name, task);
    }

    /** 观测：派发统计。 */
    public static String stats() {
        return DEFAULT.stats();
    }
}
