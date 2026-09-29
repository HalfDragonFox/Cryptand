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
package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.neoforge.powergrid.device.cache.CacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SinkCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
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
    /** 【变化即上报】待刷新位置（2026-09-11 用户：读取改增量）：BE 参数变化
     * （setChanged，经 Lifecycle mixin）→ 标记；syncAll 只处理脏位置 + 周期兜底
     *（引擎→BE 方向的结果应用需及时，故兜底周期 250ms = 5 tick）。 */
    private static final java.util.Set<BlockPos> DIRTY =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long FULL_SWEEP_MS = 250L;
    private static volatile long LAST_FULL_SWEEP_MS;

    /** BE 参数变化上报入口（变化即上报；线程安全幂等，可高频调用）。 */
    public static void markDirty(BlockPos pos) {
        if (pos != null) DIRTY.add(pos.immutable());
    }

    public static void syncAll(Level level) {
        if (level == null || REG.isEmpty()) return;
        // ⚠ 增量门控：无脏且未到兜底周期 → 直接返回（零开销）。
        long nowMs = System.currentTimeMillis();
        boolean full = (nowMs - LAST_FULL_SWEEP_MS) >= FULL_SWEEP_MS;
        java.util.Set<BlockPos> only = null;
        if (full) {
            DIRTY.clear();
            LAST_FULL_SWEEP_MS = nowMs;
        } else {
            if (DIRTY.isEmpty()) return;
            only = new java.util.HashSet<>(DIRTY);
            DIRTY.removeAll(only);
        }
        for (java.util.Map.Entry<BlockPos, Entry> e : REG.entrySet()) {
            BlockPos pos = e.getKey();
            if (only != null && !only.contains(pos)) continue; // 增量：只刷新上报过的位置
            Entry entry = e.getValue();
            try {
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null) {
                    REG.remove(pos);
                    continue;
                }
                // ⚠ 2026-08-30 审计 C14 根因：同位置换类型（拆加热器换电机）时
                // 若中间无 BE==null tick 窗口，computeIfAbsent 复用旧 Entry（旧
                // assembler）→ 新设备按旧组装器刷新缓存（读错字段 → R=0 →
                // 不建模）。按当前 BE 反查组装器，不一致 → 重建 Entry（新缓存 +
                // 新组装器）。
                try {
                    // 2026-09-13：beKey 沿继承链解析（自有 BE 子类 → 原版名）
                    com.hdf.cryptand.neoforge.powergrid.device.Assembler cur =
                            com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                                    .getByClass(com.hdf.cryptand.neoforge.powergrid.device
                                            .Assemblers.beKey(be));
                    if (cur instanceof com.hdf.cryptand.neoforge.powergrid.device.cache.CacheAssembler ca
                            && entry.assembler != ca) {
                        Entry ne = new Entry(new DeviceCache(), ca);
                        if (REG.replace(pos, entry, ne)) entry = ne;
                    }
                } catch (Throwable ignored) {
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
