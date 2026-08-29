package com.hdf.cryptand.circuitsimulation.comm;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通信接口（2026-08-22 用户架构：【接口类】——外部与实例交互的唯一入口）。
 * <p>
 * 外部（MC / EDA / 客户端）持有本接口，通过它向实例发送请求（数据汇总到
 * {@link CommHub} 总接口管理类，再按实例方式转发到具体实例）。两种模式：
 * <ul>
 *   <li><b>本地（进程内）</b> {@link #local(CommHub)}：直调 hub 转发（高效，同 JVM）；</li>
 *   <li><b>远程（网络）</b> {@link #remote(CommTransport)}：经 TCP/UDP 传输往返。</li>
 * </ul>
 * 实例完全隔离：本接口只发请求/收响应，不持有也不暴露实例引用。
 */
public final class CommClient {

    private final CommHub hub;
    private final CommTransport transport;
    private final CommCodec codec;
    private final AtomicLong ids = new AtomicLong();

    /** 本地模式：数据经 CommHub 总接口管理类汇总转发（进程内） */
    public static CommClient local(CommHub hub) {
        return new CommClient(hub, null, new ProtoCommCodec());
    }

    /** 远程模式：经传输（TCP/UDP）往返 */
    public static CommClient remote(CommTransport transport) {
        return new CommClient(null, transport, new ProtoCommCodec());
    }

    private CommClient(CommHub hub, CommTransport transport, CommCodec codec) {
        this.hub = hub;
        this.transport = transport;
        this.codec = codec;
        if (hub == null && transport == null) throw new IllegalArgumentException("need hub or transport");
    }

    // ===== 通用请求 =====

    /** 发送请求并等待响应（本地直调 / 远程往返） */
    public CommResponse call(CommRequest req) {
        if (req == null) return CommResponse.fail(-1, null, "null request");
        try {
            if (hub != null) return hub.handle(req); // 本地：数据汇总到总接口管理类转发
            byte[] rb = transport.requestReply(codec.encodeRequest(req));
            return codec.decodeResponse(rb);
        } catch (Throwable t) {
            return CommResponse.fail(req.id, req.op, String.valueOf(t));
        }
    }

    /** 便捷：构造 + 调用（无附加数据） */
    public CommResponse call(CommOp op, String worldName) {
        return call(CommRequest.of(ids.incrementAndGet(), worldName, op));
    }

    /** 便捷：构造 + 调用（带 data） */
    public CommResponse call(CommOp op, String worldName, Map<String, Object> data) {
        return call(CommRequest.of(ids.incrementAndGet(), worldName, op, data));
    }

    // ==================== 对话（Dialogue） ====================

    /**
     * 打开对话（需本地模式）：经总通讯管理类（CommHub）发创建请求 → 创建实例
     * + 专属虚拟线程 → 返回【含实例引用的对话句柄】（性能好，直接操作）。
     * 创建流程符合用户：客户端发请求等待句柄，后续只对句柄操作。
     */
    public DialogueHandle openDialogue(String name) {
        if (hub == null) throw new IllegalStateException("openDialogue requires local CommClient");
        DialogueHandle h = hub.openDialogue(name);
        if (h == null) throw new IllegalStateException("open dialogue failed: " + name);
        return h;
    }

    /** 远程对话句柄（操作经网络往返；先发创建请求） */
    public DialogueHandle dialogue(String name) {
        call(CommOp.CREATE_WORLD, name);
        return DialogueHandle.remote(name, this);
    }

    public void close() {
        if (transport != null) {
            try { transport.close(); } catch (Throwable ignored) { }
        }
    }
}