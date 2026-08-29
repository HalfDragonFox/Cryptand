package com.hdf.cryptand.circuitsimulation.comm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通信枢纽（2026-08-22 用户架构：【总接口管理类】）。
 * <p>
 * 汇聚所有接口渠道的请求，统一路由转发：
 * <ul>
 *   <li>注册多个【传输端点】（{@link CommEndpoint}：进程内 / TCP / UDP，各自
 *       传输 + 编解码）——不同实例方式（传输）统一接入；</li>
 *   <li>任一端点收到请求帧 → 统一解码 → {@link CommRequestHandler} **按实例方式
 *       （worldName）转发到具体实例**（NetworkWorld）→ 结果沿原端点发回；</li>
 *   <li>{@link #handle(CommRequest)} 为进程内直调入口（接口类 CommClient 本地模式
 *       经此"数据汇总到总接口管理类"再转发）。</li>
 * </ul>
 * 实例完全隔离：外部（客户端/网络）只能发请求，不能持实例引用；实例创建
 * （CREATE_WORLD）也经本枢纽 → handler → WorldFactory 创建。
 */
public final class CommHub {

    private final CommRequestHandler handler;
    private final ConcurrentMap<String, CommEndpoint> endpoints = new ConcurrentHashMap<>();

    public CommHub(CommRequestHandler handler) {
        this.handler = handler;
    }

    /** 注册传输端点（进程内 / TCP / UDP）；启动接收线程。 */
    public CommEndpoint addEndpoint(String name, CommTransport transport) {
        CommEndpoint ep = new CommEndpoint(name, transport, new ProtoCommCodec(), this);
        CommEndpoint old = endpoints.putIfAbsent(name, ep);
        if (old != null) { ep.close(); return old; }
        ep.start();
        return ep;
    }

    /** 端点到缆 → 解码 → 统一转发 → 编码回复（端点接收线程调用） */
    void onFrame(CommEndpoint ep, byte[] payload) {
        CommResponse res;
        try {
            CommRequest req = ep.codec().decodeRequest(payload);
            res = handler.handle(req);
        } catch (Throwable t) {
            res = CommResponse.fail(-1, null, String.valueOf(t));
        }
        try {
            ep.transport().send(ep.codec().encodeResponse(res));
        } catch (Throwable ignored) {
        }
    }

    /** 进程内直调入口（CommClient 本地模式：数据汇总到本枢纽再按实例方式转发） */
    public CommResponse handle(CommRequest req) {
        return handler.handle(req);
    }

    // ==================== 对话（Dialogue）——创建/包装/销毁 ====================

    /**
     * 打开对话（本地模式，经总通讯管理类）：发创建请求（创建实例，WorldFactory
     * 提供 executor/link）→ 分配【专属虚拟线程】→ 把【实例引用】+ 元数据封装成
     * 句柄返回客户端（用引用而非字符串，性能好）。
     *
     * @param name 对话名（= 实例名）
     * @return 对话句柄（含实例引用 + 专属线程）；创建失败返回 null
     */
    public DialogueHandle openDialogue(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            handler.handle(CommRequest.of(0L, name, CommOp.CREATE_WORLD));
            com.hdf.cryptand.circuitsimulation.cache.NetworkWorld w =
                    com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager.get().getWorld(name);
            if (w == null) return null;
            DialogueExecutor ex = DialogueExecutor.create("dlg-" + name);
            return DialogueHandle.local(name, w, handler, ex);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 包装已存在的本地实例为对话句柄（带专属线程）；实例不存在返回 null */
    public DialogueHandle dialogue(String name) {
        com.hdf.cryptand.circuitsimulation.cache.NetworkWorld w =
                com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager.get().getWorld(name);
        if (w == null) return null;
        return DialogueHandle.local(name, w, handler, DialogueExecutor.create("dlg-" + name));
    }

    /** 端点（快照；空返回空集） */
    public java.util.Collection<CommEndpoint> endpoints() {
        return endpoints.values();
    }

    /** 关闭全部端点 */
    public void close() {
        for (CommEndpoint ep : endpoints.values()) {
            try { ep.close(); } catch (Throwable ignored) { }
        }
        endpoints.clear();
    }
}