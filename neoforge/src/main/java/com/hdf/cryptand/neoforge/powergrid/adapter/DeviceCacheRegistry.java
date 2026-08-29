/**
 * ===== 设备缓存注册表（2026-08-15 用户设计："主控"） =====
 *
 * 组装器把设备缓存引用【发送到本注册表】（主控）；BE 侧经此绑定缓存，
 * 主线程每 tick {@link #syncAll} 读 BE 字段 → 写缓存（原子替换）；后台组装器
 * 从注册表取缓存原子读。
 *
 * 结构：pos → {DeviceCache, CacheAssembler}（组装器定义如何从 BE 刷新缓存）。
 * 线程安全：ConcurrentHashMap（主线程写/读，后台读——同一缓存条目原子）。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.neoforge.powergrid.device.CacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.SinkCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.concurrent.ConcurrentHashMap;

public final class DeviceCacheRegistry {

    /** 注册条目：缓存 + 定义刷新逻辑的组装器 */
    private static final class Entry {
        final DeviceCache cache;
        final CacheAssembler assembler;
        Entry(DeviceCache cache, CacheAssembler assembler) {
            this.cache = cache;
            this.assembler = assembler;
        }
    }

    private static final ConcurrentHashMap<BlockPos, Entry> REG = new ConcurrentHashMap<>();

    private DeviceCacheRegistry() {
    }

    /** 组装器注册/获取设备缓存（首次创建并注册到主控） */
    public static DeviceCache register(BlockPos pos, CacheAssembler assembler) {
        if (pos == null || assembler == null) return null;
        return REG.computeIfAbsent(pos, p -> new Entry(new DeviceCache(), assembler)).cache;
    }

    /** 后台按 pos 取缓存（未注册返回 null） */
    public static DeviceCache get(BlockPos pos) {
        Entry e = pos == null ? null : REG.get(pos);
        return e == null ? null : e.cache;
    }

    /** 缓存数（诊断） */
    public static int size() {
        return REG.size();
    }

    /** 失效（设备移除/网络重建清理） */
    public static void invalidate(BlockPos pos) {
        if (pos != null) REG.remove(pos);
    }

    /** 清空（世界切换） */
    public static void clearAll() {
        REG.clear();
    }

    /**
     * 主线程每 tick 更新（=发消息）：遍历注册的缓存 → 反查 BE → 按分支处理：
     *   - SourceCacheAssembler：读 BE 字段 → 原子写【输入槽】（BE→组装器）
     *   - SinkCacheAssembler：读【输出槽】→ 应用到 BE（组装器→BE）
     * BE 不存在（拆/未加载）→ 失效该缓存（后台构建时该设备自动跳过）。
     */
    public static void syncAll(Level level) {
        if (level == null || REG.isEmpty()) return;
        for (java.util.Map.Entry<BlockPos, Entry> e : REG.entrySet()) {
            BlockPos pos = e.getKey();
            Entry entry = e.getValue();
            try {
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null) {
                    REG.remove(pos);
                    continue;
                }
                if (entry.assembler instanceof SourceCacheAssembler src) {
                    try {
                        src.refreshCache(be, entry.cache); // 写输入槽
                    } catch (Throwable ignored) {
                    }
                }
                if (entry.assembler instanceof SinkCacheAssembler sink) {
                    try {
                        sink.applyCacheToBe(be, entry.cache); // 读输出槽应用 BE
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
                // 单点失败不影响整批
            }
        }
    }
}
