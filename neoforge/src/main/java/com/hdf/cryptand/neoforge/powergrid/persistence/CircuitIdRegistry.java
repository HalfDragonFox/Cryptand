package com.hdf.cryptand.neoforge.powergrid.persistence;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 稳定 64 位 id 注册表（2026-08-15 用户要求：每个组装器需要一个 64 位 id）。
 * <p>
 * 把【稳定键】映射到 64 位 id：键 = 位置（组装器 "x,y,z"）或边签名（导线
 * "a_key|b_key"）。首次见到该键 → 从 {@link LongSupplier}（网表库
 * {@code SimulationIdGen}）分配新 id；之后恒返回同一 id → 跨保存稳定。
 * <p>
 * 跨会话恢复：进世界 {@link #rebuild} 从已恢复记录重建映射（已有 id 复用，
 * 不重新分配）→ 重启后同一方块/导线仍是同一 64 位 id。
 * 线程安全：ConcurrentHashMap（主线程保存/恢复、后台读取）。
 */
public final class CircuitIdRegistry {

    /** 键 → 64 位 id（线程安全） */
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    /** 取（或分配）键对应的 64 位 id */
    public long idFor(String key, LongSupplier allocator) {
        if (key == null) return allocator.getAsLong();
        Long v = ids.get(key);
        if (v != null) return v;
        // computeIfAbsent 原子：并发下同键只分配一次
        return ids.computeIfAbsent(key, k -> allocator.getAsLong());
    }

    /** 已有 id（未分配 → -1） */
    public long existing(String key) {
        if (key == null) return -1;
        Long v = ids.get(key);
        return v == null ? -1 : v;
    }

    /** 移除键（设备/导线被移除时清理；释放 id 池） */
    public void remove(String key) {
        if (key != null) ids.remove(key);
    }

    /** 重建映射（跨会话恢复：从已持久化记录恢复 键→id，保证重启后稳定） */
    public void rebuild(Collection<Map.Entry<String, Long>> entries) {
        if (entries == null) return;
        for (Map.Entry<String, Long> e : entries) {
            if (e != null && e.getKey() != null && e.getValue() != null) {
                ids.put(e.getKey(), e.getValue());
            }
        }
    }

    /** 清空（世界卸载） */
    public void clear() {
        ids.clear();
    }

    /** 已分配数量（诊断） */
    public int size() {
        return ids.size();
    }
}
