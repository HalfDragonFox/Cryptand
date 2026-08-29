package com.hdf.cryptand.circuitsimulation.cache;

import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络世界管理器（2026-08-22 用户架构：核心扩展组件 —— 全局类统一管理）。
 * <p>
 * 全局单例，统一管理全部网络世界实例（{@link NetworkWorld}，具体会话/世界）：
 * <ul>
 *   <li>外部（MC 主线程 / EDA 前端）创建实例：MC 存档 → 一个 NetworkWorld
 *       （如 "mc-overworld"），EDA 电路 → 另一个（如 "eda-scene-1"）；</li>
 *   <li>每个 NetworkWorld 统一管理：与具体对象的对话（{@link AppLink}）+ 个缓存
 *       数据（{@link CachedNetwork}）+ 消息（异步交互管理类集成进核心）；</li>
 *   <li>{@link #submit(CacheOp)}：外部发"世界名 + 网络引用 + 操作"消息 → 路由到
 *       对应世界 → 核心处理。</li>
 * </ul>
 * <p>
 * 纯核心组件：不依赖 Minecraft/EDA——对话（AppLink）与执行器（NetOpExecutor）
 * 都由平台实现注入。
 */
public final class NetworkWorldManager {

    private static final NetworkWorldManager INSTANCE = new NetworkWorldManager();

    public static NetworkWorldManager get() { return INSTANCE; }

    /** 全部网络世界（名 → 实例） */
    private final Map<String, NetworkWorld> worlds = new ConcurrentHashMap<>();

    private NetworkWorldManager() {
    }

    // ==================== 实例生命周期（外部创建） ====================

    /**
     * 注册实例（幂等：同名已注册返回旧实例）——{@link NetworkWorld#create} 调用。
     * 返回 null = 注册成功（新实例）；旧实例 = 已存在（调用方用旧实例）。
     */
    NetworkWorld registerWorld(NetworkWorld world) {
        if (world == null) return null;
        return worlds.putIfAbsent(world.name(), world);
    }

    /** 查询实例（不存在 null） */
    public NetworkWorld getWorld(String name) {
        return worlds.get(name);
    }

    /** 移除并释放实例（通知对话应用 + 清缓存数据 + 清异步记录表） */
    public NetworkWorld removeWorld(String name) {
        NetworkWorld w = worlds.remove(name);
        if (w != null) w.dispose();
        return w;
    }

    /** 全部实例（快照） */
    public Collection<NetworkWorld> allWorlds() {
        return worlds.values();
    }

    /** 全部实例名 */
    public Set<String> names() {
        return worlds.keySet();
    }

    /** 实例数量 */
    public int size() {
        return worlds.size();
    }

    /** 清空全部实例（释放） */
    public void clearAll() {
        for (String n : new ArrayList<>(worlds.keySet())) removeWorld(n);
    }

    // ==================== 消息路由（外部 → 核心） ====================

    /**
     * 提交缓存操作消息：世界名 + 网络引用/名称 + 操作 → 路由到对应世界 →
     * 异步交互管理类（核心）按网络键串行处理。世界不存在 → 忽略（静默）。
     */
    public void submit(CacheOp op) {
        if (op == null) return;
        NetworkWorld w = worlds.get(op.cacheName());
        if (w == null) return;
        w.submit(op.kind(), op.networkKey(), op.data());
    }

    @Override
    public String toString() {
        return "NetworkWorldManager{worlds=" + worlds.size() + " " + worlds.keySet() + "}";
    }
}