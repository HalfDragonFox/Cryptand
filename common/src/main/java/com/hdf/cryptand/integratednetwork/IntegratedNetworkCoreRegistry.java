package com.hdf.cryptand.integratednetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集成网络核心实例注册表（2026-08-26 集成网络核心：核心多对象隔离运行）。
 * <p>
 * MC 世界与每个客户端/子系统各持有一个独立的 {@link IntegratedNetworkCore} 实例，
 * 各自传输图 / 帧结果 / 异步管理类完全隔离。离线 → 释放该实例（stop + 清图 +
 * 移除），相关缓存随之销毁，不污染其他实例。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link #acquire(String)}：获取/创建命名核心实例（幂等，已存在复用）。</li>
 *   <li>{@link #release(String)}：销毁实例——{@code stop()}（清空传输操作记录表）
 *       + 清图/结果缓存 + 从注册表移除。隔离清理。</li>
 *   <li>{@link #releaseAll()}：全部释放（服务端停止时调用）。</li>
 * </ul>
 * 线程安全：{@link ConcurrentHashMap}。任意线程可调。
 */
public final class IntegratedNetworkCoreRegistry {

    private static final ConcurrentHashMap<String, IntegratedNetworkCore> CORES =
            new ConcurrentHashMap<>();

    private IntegratedNetworkCoreRegistry() {
    }

    /** 获取/创建命名核心实例（幂等：已存在复用；不存在创建）。 */
    public static IntegratedNetworkCore acquire(String name) {
        String key = normalize(name);
        return CORES.computeIfAbsent(key, IntegratedNetworkCore::new);
    }

    /** 获取命名核心实例（不存在返回 null；不创建） */
    public static IntegratedNetworkCore get(String name) {
        return CORES.get(normalize(name));
    }

    /**
     * 释放命名核心实例：stop（清传输操作记录表）+ 清图/结果缓存 + 从注册表移除。
     * 幂等。客户端离线 / 子系统关闭时调用——相关缓存随之销毁（隔离清理）。
     */
    public static void release(String name) {
        String key = normalize(name);
        IntegratedNetworkCore core = CORES.remove(key);
        if (core == null) return;
        try {
            core.stop();
        } catch (Throwable ignored) {
        }
        try {
            core.coreExecutor().clear();
        } catch (Throwable ignored) {
        }
    }

    /** 释放全部实例（服务端停止 / 整体下线） */
    public static void releaseAll() {
        List<String> keys = new ArrayList<>(CORES.keySet());
        for (String k : keys) release(k);
    }

    /** 当前活动实例名列表 */
    public static Set<String> names() {
        return CORES.keySet();
    }

    /** 当前活动实例数 */
    public static int activeCount() {
        return CORES.size();
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) return "default";
        return name.trim();
    }
}