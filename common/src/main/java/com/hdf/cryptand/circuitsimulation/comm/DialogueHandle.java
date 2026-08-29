package com.hdf.cryptand.circuitsimulation.comm;

import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager;

import java.util.Map;

/**
 * 对话句柄（2026-08-22 用户架构：客户端发创建请求 → 等待句柄 → 只对句柄操作）。
 * <p>
 * 一个句柄 = 一个【对话】（= 一个隔离实例）的操作入口：
 * <ul>
 *   <li><b>本地（同 JVM）</b>：封装【实例引用】{@link #world()}（用引用而非字符串，
 *       性能好），操作直接经 {@link CommRequestHandler} 映射到实例（不走序列化往返）；</li>
 *   <li><b>远程（网络）</b>：封装 {@link CommClient} + 对话名，操作经通信接口往返；</li>
 *   <li>对话还持有【专属虚拟线程】{@link DialogueExecutor}（{@link #submit} 投递
 *       到该对话独立线程，隔离防卡死）。</li>
 * </ul>
 * 创建流程（符合用户）：客户端经 {@link CommClient} 向 {@link CommHub}（总通讯
 * 管理类）发创建请求 → 创建实例 + 创建单独线程（虚拟线程）的对话 → 把实例引用
 * + 元数据封装成句柄返回。{@link #close()} = 销毁对话（释放实例 + 停专属线程）。
 */
public final class DialogueHandle implements AutoCloseable {

    private final String name;
    /** 本地实例引用（远程模式为 null） */
    private final NetworkWorld world;
    /** 远程客户端（本地模式为 null） */
    private final CommClient remote;
    /** 请求处理器（本地操作映射到实例） */
    private final CommRequestHandler handler;
    /** 对话专属虚拟机线程（隔离） */
    private final DialogueExecutor executor;

    /** 本地句柄（经总通讯管理类创建；含实例引用 + 专属线程） */
    static DialogueHandle local(String name, NetworkWorld world,
                                CommRequestHandler handler, DialogueExecutor executor) {
        return new DialogueHandle(name, world, null, handler, executor);
    }

    /** 远程句柄（网络；不经引用，经 CommClient 往返） */
    public static DialogueHandle remote(String name, CommClient remote) {
        return new DialogueHandle(name, null, remote, null, null);
    }

    private DialogueHandle(String name, NetworkWorld world, CommClient remote,
                           CommRequestHandler handler, DialogueExecutor executor) {
        this.name = name;
        this.world = world;
        this.remote = remote;
        this.handler = handler;
        this.executor = executor;
    }

    public String name() { return name; }

    /** 本地实例引用（远程为 null）——性能好，直接操作 */
    public NetworkWorld world() { return world; }

    /** 对话专属线程（远程为 null） */
    public DialogueExecutor executor() { return executor; }

    /** 操作对话（本地：引用直调经 handler；远程：经 CommClient） */
    public CommResponse call(CommOp op, Map<String, Object> data) {
        CommRequest req = CommRequest.of(System.nanoTime(), name, op, data);
        if (handler != null) return handler.handle(req); // 本地：返回也只创建后调用
        if (remote != null) return remote.call(req);
        return CommResponse.fail(req.id, op, "no path to dialogue");
    }

    /** 便捷：无附加数据的操作 */
    public CommResponse call(CommOp op) {
        return call(op, null);
    }

    /** 投递到对话专属线程执行（异步；本地隔离执行） */
    public void submitOnDialogueThread(Runnable r) {
        if (executor != null) executor.submit(r);
    }

    /** 销毁对话 = 关闭对话（释放实例 + 停专属线程） */
    @Override
    public void close() {
        if (world != null) {
            NetworkWorldManager.get().removeWorld(name); // 销毁实例 → 对话关闭
        }
        if (executor != null) executor.close();
    }

    @Override
    public String toString() {
        return "DialogueHandle{" + name + ", local=" + (world != null)
                + ", remote=" + (remote != null) + "}";
    }
}