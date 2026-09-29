package com.hdf.cryptand.circuitsimulation.core.extensions;

import com.hdf.cryptand.circuitsimulation.core.SimulationCore;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;
import com.hdf.cryptand.circuitsimulation.netop.NetOpRequest;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.UUID;

/**
 * 会话句柄（2026-08-30 用户：注册实例后客户会拿到一个句柄，消息通过此句柄
 * 交互即可）。
 * <p>
 * 前端（EDA / MC / 外部程序）经 {@link SimulationCore#openSession()} 注册到
 * 实例后获得本句柄：后续所有交互（注册网络 / 提交操作 / 同步求解 / 查询结果）
 * 都经句柄——无需再持有实例/连接，句柄是客户与核心实例的唯一交互入口。
 * TCP/UDP/消息总线扩展是【传输通道】（通道消息 → 核心 API）；本句柄是
 * 【API 级统一入口】（本地直连/服务端会话共用）。
 */
public final class SessionHandle {

    /** 会话 id（全局唯一；诊断/路由用） */
    private final String sessionId;
    /** 关联的核心实例 */
    private final SimulationCore core;
    private volatile boolean closed;

    /** 由 SimulationCore.openSession() 创建 */
    public SessionHandle(SimulationCore core) {
        this.core = core;
        this.sessionId = UUID.randomUUID().toString().substring(0, 12);
    }

    /** 会话 id（诊断/日志） */
    public String sessionId() { return sessionId; }

    /** 关联的核心实例（只读） */
    public SimulationCore core() { return core; }

    /** 是否已关闭 */
    public boolean isClosed() { return closed; }

    // ==================== 经句柄交互（封装核心实例 API） ====================

    /** 注册网络（spec = 网络规格，见 {@link NetworkSpec}；freq 默认 0） */
    public SessionHandle register(String key, double freq, String spec) {
        if (closed) return this;
        Network net = NetworkSpec.parse(spec);
        net.frequency = freq;
        core.registerNetwork(key, net);
        return this;
    }

    /** 提交异步网络操作（SOLVE / REBUILD / SPLIT_MERGE） */
    public SessionHandle submit(String key, NetOpKind kind) {
        if (closed) return this;
        core.submit(key, new NetOpRequest(kind, null));
        return this;
    }

    /** 同步求解（solveNow；结果写注册表） */
    public SolveResult solve(String key) {
        if (closed) return null;
        return core.solveNow(key);
    }

    /** 查询最近求解结果 */
    public SolveResult result(String key) {
        if (closed) return null;
        return core.result(key);
    }

    /** 注销网络 */
    public SessionHandle unregister(String key) {
        if (closed) return this;
        core.unregisterNetwork(key);
        return this;
    }

    /** 关闭会话（核心实例注销本会话） */
    public void close() {
        if (closed) return;
        closed = true;
        core.closeSession(sessionId);
    }
}
