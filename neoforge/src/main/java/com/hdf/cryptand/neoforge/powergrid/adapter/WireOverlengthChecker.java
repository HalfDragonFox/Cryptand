/**
 * ===== 物理化超长导线异步检查器（2026-08-23 用户） =====
 *
 * 职责：后台异步线程【固定间隔】遍历 {@link WireOverlengthTracker} 待检列表，
 * 对"物理化结构 ↔ 物理化/非物理化结构"之间的导线做真实距离检测：两端点经
 * Sable logicalPose 投影到世界坐标后距离超过该线材最大长度 → 自动断开
 * （WireNetworkManager.removeEdge，图写锁线程安全；客户端由主线程下 tick
 * version 检测自动同步）。
 *
 * 规则：
 *   - 两端均非亚层（已回迁/普通世界线）→ 移出待检；
 *   - 两端同一亚层 → 相对距离恒定 → 跳过；
 *   - 跨亚层/亚层-世界 → 投影世界距离 > maxLen → 断开。
 *
 * 线程安全：WireNetworkManager（NetworkWorld 读写锁）线程安全；markDirty
 * 内部主线程检查自动跳过（SavedData 由 onWorldSave 兜底）。daemon 线程，
 * 服务器关闭 JVM 自动退出。
 *
 * 频率：ConfigLoad.WIRE_OVERLENGTH_CHECK_INTERVAL_TICKS（tick，默认 20 ≈ 1s）。
 * 生命周期：WireNetworkManager.onWorldLoad 启动 / onWorldUnload/disposeCurrent 停止。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.neoforge.cee.CeePoseUtil;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.Cryptand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = Cryptand.MOD_ID)
public final class WireOverlengthChecker {

    /** 工作线程（daemon；null = 未启动） */
    private static volatile Thread worker;
    private static volatile boolean running;
    /** 诊断节流（警告最多每 30s 一条） */
    private static volatile long lastWarnMs;

    private WireOverlengthChecker() {
    }

    /** 启动异步检查线程（幂等；服务端世界加载时调用） */
    public static synchronized void startIfNeeded() {
        if (running) return;
        running = true;
        Thread t = new Thread(WireOverlengthChecker::loop, "cryptand-wire-overlength");
        t.setDaemon(true);
        worker = t;
        t.start();
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                "[WireOverlen] async checker started");
    }

    /** 停止检查线程（世界卸载/服务器停止） */
    public static synchronized void stop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
    }

    // ==================== 区块卸载/加载事件（2026-08-23 用户 uint8 计数机制） ====================
    // 端点所在区块卸载 → +1（两端同时卸载 +2）；加载 → -1。计数 > 0 的边在
    // 检测时跳过——避免"区块卸载导致读取/投影失败（null/逻辑位置回退）→ 误判
    // 超长断开"。

    @SubscribeEvent
    public static void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload e) {
        try {
            net.minecraft.world.level.LevelAccessor la = e.getLevel();
            if (la instanceof ServerLevel sl && e.getChunk() != null) {
                WireOverlengthTracker.onChunkUnloaded(sl, e.getChunk().getPos());
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load e) {
        try {
            net.minecraft.world.level.LevelAccessor la = e.getLevel();
            if (la instanceof ServerLevel sl && e.getChunk() != null) {
                WireOverlengthTracker.onChunkLoaded(sl, e.getChunk().getPos());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 检查频率（毫秒）——配置 tick × 50ms，异常兜底 1s */
    private static long intervalMs() {
        try {
            int ticks = ConfigLoad.WIRE_OVERLENGTH_CHECK_INTERVAL_TICKS.get();
            return Math.max(1, Math.min(ticks, 1200)) * 50L;
        } catch (Throwable t) {
            return 1000L;
        }
    }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(intervalMs());
                checkOnce();
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastWarnMs > 30000) {
                    lastWarnMs = now;
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                            "[WireOverlen] checker error", t);
                }
            }
        }
    }

    /** 单轮检查（异步线程） */
    private static void checkOnce() {
        try {
            if (!ConfigLoad.WIRE_OVERLENGTH_ENABLE.get()) return;
            Level lvl = PhasorWriteback.CRYPTAND_LAST_LEVEL;
            if (lvl == null) return; // 无活动世界
            var mgr = WireNetworkManager.get();
            if (mgr.nodeCount() <= 0) return;
            List<WireEdge> edges = mgr.edgeList();
            Map<String, WireEdge> byId = new HashMap<>(edges.size() * 2);
            for (WireEdge e : edges) byId.put(WireOverlengthTracker.edgeId(e), e);
            for (String id : WireOverlengthTracker.all()) {
                try {
                    checkOne(lvl, mgr, byId, id);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void checkOne(Level lvl, WireNetworkManager mgr,
                                 Map<String, WireEdge> byId, String id) {
        // ⚠ 2026-08-23 uint8 卸载计数：任一端点区块处于卸载态 → 跳过检测
        //（此时 projectOut/getContaining 可能失败返回逻辑位置 → 距离误判超长）。
        if (WireOverlengthTracker.unloadedCount(id) > 0) return;
        WireEdge e = byId.get(id);
        if (e == null) {
            // 图里已无此边（删除/拆分/迁移）→ 移出待检
            WireOverlengthTracker.untrack(id);
            return;
        }
        BlockPos a = WireKeyUtil.posOf(e.a.key);
        BlockPos b = WireKeyUtil.posOf(e.b.key);
        if (a == null || b == null) {
            WireOverlengthTracker.untrack(id);
            return;
        }
        boolean aSub = WireOverlengthTracker.isPlotPos(a);
        boolean bSub = WireOverlengthTracker.isPlotPos(b);
        if (!aSub && !bSub) {
            // 已回迁为普通世界导线 → 距离恒定，不再检查
            WireOverlengthTracker.untrack(id);
            return;
        }
        // ⚠ 2026-08-23 防误断（"连接电机的导线过一会消失"）：亚层身份不可知
        // （物理化/取消瞬间 Sable 观察者未注册、getContaining 返回 null）→
        // projectOut 可能返回逻辑位置/错误距离 → 误判超长断开。信息不足 →
        // 跳过本轮（下轮观察者注册后再检），绝不删除。
        if (aSub && CeePoseUtil.getContaining(lvl, a) == null) return;
        if (bSub && CeePoseUtil.getContaining(lvl, b) == null) return;
        if (aSub && bSub) {
            // 同一亚层：相对距离恒定 → 跳过；不同亚层 → 检查
            Object sa = CeePoseUtil.getContaining(lvl, a);
            Object sb = CeePoseUtil.getContaining(lvl, b);
            if (sa != null && sb != null && sa == sb) return;
        }
        Vec3 wa = CeePoseUtil.projectOut(lvl, Vec3.atCenterOf(a));
        Vec3 wb = CeePoseUtil.projectOut(lvl, Vec3.atCenterOf(b));
        if (wa == null || wb == null) return;
        double dist = wa.distanceTo(wb);
        float maxLen = maxLengthOf(e);
        if (maxLen <= 0) return;
        if (dist > maxLen) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[WireOverlen] break {} <-> {} dist={} max={}",
                    e.a.key, e.b.key, (long) dist, maxLen);
            mgr.removeEdge(e.a, e.b);
            WireOverlengthTracker.untrack(id);
            // ⚠ 异步线程 markDirty 无效（仅主线程生效）→ 投递主线程标记存档，
            // 否则世界退出时 WireSavedData 不写 → 断开的边从存档恢复。
            markDirtyOnMainThread(lvl);
        }
    }

    /** 主线程投递存档标记（SavedData 只认主线程 setDirty；异常静默） */
    private static void markDirtyOnMainThread(Level lvl) {
        try {
            if (lvl instanceof net.minecraft.server.level.ServerLevel sl
                    && sl.getServer() != null) {
                sl.getServer().execute(() -> {
                    try {
                        WireSavedData.get(sl).setDirty();
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** 线材最大长度：统一注册器（含 CEE 桥）优先；兜底与放置默认一致（24 格） */
    private static float maxLengthOf(WireEdge e) {
        try {
            if (e.itemId != null) {
                com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireType wt =
                        com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry
                                .byItemId(e.itemId);
                if (wt != null && wt.maximumLength() > 0) return wt.maximumLength();
            }
        } catch (Throwable ignored) {
        }
        return 24f;
    }
}
