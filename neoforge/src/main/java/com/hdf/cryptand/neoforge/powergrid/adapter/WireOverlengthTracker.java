/**
 * ===== 物理化超长导线待检列表（2026-08-23 用户） =====
 *
 * 需要距离检测的导线：端点任一在【亚层 plot 坐标】的边（物理化结构与
 * 物理化/非物理化结构之间的连接）。这些边的两端真实世界距离会随装置
 * 移动变化（亚层内部相对距离不变 → 同亚层不检）。
 *
 * 判定无需 Level：Sable 亚层内部块坐标特征 = chunk >= 1280_000（块 >=
 * 20,480,000）——物理化/回迁后端点 key 坐标本身就携带该特征。
 *
 * 线程：主线程写（addEdge/remap），检查线程（主线程 tick 定时）读——
 * ConcurrentHashMap 安全。检查逻辑见 {@link WireOverlengthTicker}。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WireOverlengthTracker {

    /** 待检边身份（"a.key|b.key"）——图边变化/删除由检查器自清 */
    private static final Set<String> TRACKED = ConcurrentHashMap.newKeySet();

    /** 边 id → 卸载端点数（uint8 0..255；2026-08-23 用户机制）：
     *  任一端点区块卸载 +1（两端同时卸载 +2），加载 -1（下限 0）。
     *  计数 > 0 = 至少一端处于卸载状态（读取/投影可能失败 → 检查器跳过检测，
     *  避免"卸载导致 null/投影回退 plot 坐标 → 误判超长断开"）。 */
    private static final java.util.Map<String, Integer> UNLOADED = new ConcurrentHashMap<>();

    private WireOverlengthTracker() {
    }

    /** 端点区块卸载事件（服务端主线程）：对待检边命中端点 +1（uint8 封顶 255） */
    public static void onChunkUnloaded(net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.world.level.ChunkPos cp) {
        applyChunkEvent(level, cp, 1);
    }

    /** 端点区块加载事件（服务端主线程）：命中端点 -1（下限 0） */
    public static void onChunkLoaded(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.world.level.ChunkPos cp) {
        applyChunkEvent(level, cp, -1);
    }

    private static void applyChunkEvent(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.level.ChunkPos cp, int delta) {
        try {
            if (level == null || cp == null || TRACKED.isEmpty()) return;
            if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
            for (WireEdge e : WireNetworkManager.get().edgeList()) {
                String id = edgeId(e);
                if (!TRACKED.contains(id)) continue;
                BlockPos pa = WireKeyUtil.posOf(e.a.key);
                BlockPos pb = WireKeyUtil.posOf(e.b.key);
                boolean hitA = pa != null && new net.minecraft.world.level.ChunkPos(pa).equals(cp);
                boolean hitB = pb != null && new net.minecraft.world.level.ChunkPos(pb).equals(cp);
                if (!hitA && !hitB) continue;
                int n = (hitA ? 1 : 0) + (hitB ? 1 : 0);
                UNLOADED.merge(id, n * (delta > 0 ? 1 : -1),
                        (o, d) -> (int) Math.max(0, Math.min(255, o + d)));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前卸载端点数（0 = 全部加载；>0 = 检查器跳过） */
    public static int unloadedCount(String id) {
        return id == null ? 0 : UNLOADED.getOrDefault(id, 0);
    }

    /** 亚层 plot 坐标特征（Sable 亚层内部 chunk >= 1280_000 → 块 >= 20,480,000） */
    public static boolean isPlotPos(BlockPos pos) {
        if (pos == null) return false;
        return (pos.getX() >> 4) >= 1280_000 && (pos.getZ() >> 4) >= 1280_000;
    }

    /** 端点 key 是否亚层坐标（B/J 位置解析失败 = 非亚层） */
    public static boolean isPlotKey(String key) {
        BlockPos p = WireKeyUtil.posOf(key);
        return p != null && isPlotPos(p);
    }

    /** 边身份（无向："a.key|b.key"） */
    public static String edgeId(WireEdge e) {
        return e.a.key + "|" + e.b.key;
    }

    /** 加入待检（仅亚层相关边；幂等）。首次入列自动启动异步检查器。 */
    public static void track(WireEdge e) {
        if (e == null) return;
        if (isPlotKey(e.a.key) || isPlotKey(e.b.key)) {
            if (TRACKED.add(edgeId(e))) {
                try { WireOverlengthChecker.startIfNeeded(); } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 移除（边已删/检查后清除）；同步清理卸载计数防泄漏 */
    public static void untrack(String id) {
        if (id != null) {
            TRACKED.remove(id);
            UNLOADED.remove(id);
        }
    }

    /** 当前待检身份快照（检查器遍历用） */
    public static Set<String> all() {
        return Set.copyOf(TRACKED);
    }

    /** 待检数量（诊断） */
    public static int size() {
        return TRACKED.size();
    }

    /** 重扫（remapBlockPos 坐标迁移后调用）：新 id 的亚层边补入；
     *  旧 id 由检查器按图失配自清。 */
    public static void rescan() {
        try {
            for (WireEdge e : WireNetworkManager.get().edgeList()) track(e);
        } catch (Throwable ignored) {
        }
    }
}
