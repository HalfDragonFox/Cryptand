package com.hdf.cryptand.neoforge.net;

import com.hdf.cryptand.neoforge.threading.config.ConfigThreading;
import com.hdf.cryptand.neoforge.inc.config.ConfigInc;
import com.hdf.cryptand.integratednetwork.IntegratedNetworkCore;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度实例池 / 平台集成（2026-08-29 P2 neoforge 平台）。
 * <p>
 * 用户架构：【每存档（维度）一个 IntegratedNetworkCore 实例 + 每 mod 一个网络
 * 总管理类句柄】。本类即为维度级实例池/句柄管理：
 * <ul>
 *   <li>每 {@link ServerLevel}（按 {@link ResourceKey}）一个 {@link DimensionNetworkManager}
 *       （网络总管理类句柄），内部持有独立 {@link IntegratedNetworkCore}；</li>
 *   <li>Level Load → {@link #acquire}；Level Unload / ServerStopping → {@link #release}
 *       （stop + 清图，隔离清理）；</li>
 *   <li>ServerTick.Post → 每维度 {@code manager.tick()}（主线程门面：收集/组表/
 *       提交/执行表写回）。</li>
 * </ul>
 * 跨 mod 混用：其它 mod 经 {@code DimensionNetworkManager#registerDevice} 把管道
 * BE（实现 {@link INetworkDevice}）注册进同一维度实例，共享虚拟网络。
 */
public final class IntegratedNetworkPlatform {

    private IntegratedNetworkPlatform() {
    }

    /** 维度键 → 网络总管理类（每 ServerLevel 一个） */
    private static final ConcurrentHashMap<ResourceKey<Level>, DimensionNetworkManager> MANAGERS =
            new ConcurrentHashMap<>();

    /** 平台总线注册（在 CryptandNeoForge 构造里调用） */
    public static void register(IEventBus gameBus) {
        // 2026-08-29 审计钩子：核心关键路径（拓扑/TICK/TRANSFER/拆合）日志接到 log4j。
        // 日志行带【线程名】——核心线程（虚拟线程 inc-*）与主线程（Server thread）
        // 不同，据此可直接验证管道路由/传输是否为【异步】执行。
        com.hdf.cryptand.integratednetwork.CoreTransportExecutor.AUDIT =
                msg -> CryptandNeoForge.WAF_LOGGER
                        .info("[INC-async] " + msg);
        gameBus.addListener((net.neoforged.neoforge.event.level.LevelEvent.Load ev) -> {
            if (ev.getLevel() instanceof ServerLevel sl) {
                // 2026-08-29 均衡检测间隔（ms）喂入全局分配器（自查式，无需外部 tick）。
                // 此时 ModConfigSpec 已 build，可安全 get()。
                try {
                    com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                            .setBalanceIntervalMs(ConfigThreading.THREAD_BALANCE_INTERVAL_MS.get());
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER
                            .warn("[INC-platform] setBalanceIntervalMs err", t);
                }
                acquire(sl);
            }
        });
        gameBus.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload ev) -> {
            if (ev.getLevel() instanceof ServerLevel sl) release(sl);
        });
        gameBus.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent ev) -> {
            releaseAll();
        });
        gameBus.addListener((ServerTickEvent.Post ev) -> {
            MinecraftServer srv = ev.getServer();
            if (srv == null) return;
            for (ServerLevel lvl : srv.getAllLevels()) {
                DimensionNetworkManager m = manager(lvl);
                if (m != null) {
                    try {
                        m.tick();
                    } catch (Throwable t) {
                        CryptandNeoForge.WAF_LOGGER
                                .warn("[INC-platform] tick err dim={}", 
                                        lvl.dimension().location(), t);
                    }
                }
            }
        });
    }

    /** 获取/创建维度网络总管理类（进维度；幂等） */
    public static synchronized DimensionNetworkManager acquire(ServerLevel level) {
        if (level == null) return null;
        ResourceKey<Level> key = level.dimension();
        return MANAGERS.computeIfAbsent(key, k -> {
            IntegratedNetworkCore core =
                    new IntegratedNetworkCore("net:" + k.location());
            core.start();
            return new DimensionNetworkManager(level, core);
        });
    }

    /** 查询维度网络总管理类（未 acquire 返回 null） */
    public static DimensionNetworkManager manager(ServerLevel level) {
        return level == null ? null : MANAGERS.get(level.dimension());
    }

    /** 释放维度网络总管理类（出维度/卸载；stop + 清图，隔离清理） */
    public static synchronized void release(ServerLevel level) {
        if (level == null) return;
        DimensionNetworkManager m = MANAGERS.remove(level.dimension());
        if (m != null) releaseInternal(m);
    }

    /** 释放全部（服务端停止） */
    public static synchronized void releaseAll() {
        List<DimensionNetworkManager> all = new ArrayList<>(MANAGERS.values());
        MANAGERS.clear();
        for (DimensionNetworkManager m : all) releaseInternal(m);
    }

    private static void releaseInternal(DimensionNetworkManager m) {
        IntegratedNetworkCore core = m.core();
        try {
            core.stop();
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER
                    .warn("[INC-platform] core.stop err", t);
        }
        try {
            core.coreExecutor().clear();
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER
                    .warn("[INC-platform] executor.clear err", t);
        }
    }

    /** 当前活动维度数量（诊断） */
    public static int activeCount() {
        return MANAGERS.size();
    }

    /** 当前活动维度键（诊断） */
    public static java.util.Set<ResourceKey<Level>> activeKeys() {
        return MANAGERS.keySet();
    }
}