package com.hdf.cryptand.circuitsimulation.core;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络注册表（2026-08-16 仿真核心）。
 * <p>
 * 仿真核心（{@link SimulationCore}）管理的全部电路网络：网络键（key，可为任意
 * 对象：网络 ID / 世界坐标 / 前端电路 ID 等）→ {@link Network}。每个注册网络
 * 还缓存最近一次求解结果（{@link #result}），供外部直接查询。
 * <p>
 * 线程安全：ConcurrentHashMap；求解结果用 volatile 写（求解线程 C 写、任意线程读）。
 */
public final class NetworkRegistry {

    /** 网络条目：网络对象 + 最近求解结果（volatile，跨线程发布） */
    static final class Entry {
        final Network network;
        /** ⚠ 2026-08-30 引擎网络上下文（图+组装器+绑定——完整求解——可 null=纯图） */
        final com.hdf.cryptand.engine.NetworkContext<?> ctx;
        volatile SolveResult result;
        volatile long solvedVersion;

        Entry(Network network) {
            this(network, null);
        }

        Entry(Network network, com.hdf.cryptand.engine.NetworkContext<?> ctx) {
            this.network = network;
            this.ctx = ctx;
        }
    }

    private final Map<Object, Entry> networks = new ConcurrentHashMap<>();

    /** 全部条目（快照——setSimSpeed 等批量配置用） */
    public java.util.Collection<Entry> entries() {
        return networks.values();
    }

    /** 注册网络（已存在则替换，结果缓存清空） */
    public void register(Object key, Network net) {
        if (key == null || net == null) return;
        networks.put(key, new Entry(net));
    }

    /** ⚠ 2026-08-30 注册引擎网络上下文（工厂创建——图+组装器+绑定——引擎完整求解） */
    public void registerCtx(Object key, com.hdf.cryptand.engine.NetworkContext<?> ctx) {
        if (key == null || ctx == null || ctx.network == null) return;
        networks.put(key, new Entry(ctx.network, ctx));
    }

    /** 查询引擎网络上下文（未注册/纯图返回 null） */
    public com.hdf.cryptand.engine.NetworkContext<?> ctx(Object key) {
        Entry e = networks.get(key);
        return e == null ? null : e.ctx;
    }

    /** 注销网络（返回被移除的网络，不存在返回 null） */
    public Network unregister(Object key) {
        Entry e = networks.remove(key);
        return e == null ? null : e.network;
    }

    /** 查询网络（未注册返回 null） */
    public Network get(Object key) {
        Entry e = networks.get(key);
        return e == null ? null : e.network;
    }

    /** 是否已注册 */
    public boolean contains(Object key) {
        return networks.containsKey(key);
    }

    /** 注册的网络数量 */
    public int size() {
        return networks.size();
    }

    /** 全部注册网络（快照，迭代安全） */
    public Collection<Network> all() {
        return networks.values().stream().map(e -> e.network).toList();
    }

    /** 全部网络键（快照） */
    public Collection<Object> keys() {
        return networks.keySet();
    }

    // ===== 求解结果缓存 =====

    /** 记录最近求解结果（求解线程 C 调用；key 未注册则忽略） */
    void setResult(Object key, SolveResult r) {
        Entry e = networks.get(key);
        if (e == null) return;
        e.result = r;
        e.solvedVersion++;
    }

    /** 查询最近求解结果（未求解/未注册返回 null） */
    public SolveResult result(Object key) {
        Entry e = networks.get(key);
        return e == null ? null : e.result;
    }

    /** 最近求解版本号（每次求解 +1；未注册 0） */
    public long solvedVersion(Object key) {
        Entry e = networks.get(key);
        return e == null ? 0 : e.solvedVersion;
    }

    /** 清空所有网络与结果 */
    public void clear() {
        networks.clear();
    }

    @Override
    public String toString() {
        return "NetworkRegistry{networks=" + networks.size() + "}";
    }
}
