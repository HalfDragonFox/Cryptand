package com.hdf.cryptand.neoforge.waterphysics;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 流体物理事件入口（事件驱动，绝不做定时全扫）。
 *
 * <p>只登记「会影响局部水位」的事件；每个事件只把受影响的那一格推进待处理队列
 * （点名），不做范围扫描。
 *
 * <p>注册方式随 powergrid：由子包 init 显式 bus.addListener，不用 @EventBusSubscriber 自动扫描
 * （自动扫描会绕过子包开关，见 hitch-after-disable-root-cause 记忆）。
 */
public final class WaterPhysicsGameEvents {

    private WaterPhysicsGameEvents() {
    }

    public static void register(final IEventBus bus) {
        // ★ 这些事件都属于 game bus，必须挂到 NeoForge.EVENT_BUS。
        //   挂到子包 init 传入的 mod bus 会抛 IllegalArgumentException（latest.log 2026-09-27 21:50:39 已复现）。
        // ★ 世界生命周期（加载 / 保存 / 卸载）与区块生命周期（加载 / 卸载）成对出现：
        //   存档里的水位由 onLevelLoad 灌回、onLevelSave/onLevelUnload 落盘，
        //   区块则靠 onChunkLoad 唤醒、onChunkUnload 把没落地的水位写进正要卸载的区块。
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onLevelSave);
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onEntityPlace);
        NeoForge.EVENT_BUS.addListener(WaterPhysicsGameEvents::onBreak);
    }

    /**
     * 维度加载：建桥接器（= 建侧表 + 把存档里的水位灌回来）。
     *
     * <p>★ 必须挂在区块加载之前：区块唤醒走的是 {@code bridgeOrNull}（只查不建），
     * 桥接器不存在就一格都唤不醒，存档里的水会永远静止（重进世界丢水的入口）。
     * {@code LevelEvent.Load} 正是这个时点 —— 它在维度创建时抛，之后才开始加载区块。
     */
    private static void onLevelLoad(final LevelEvent.Load event) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            WaterPhysicsModule.onLevelLoad(level);
        }
    }

    /**
     * 区块加载：把这个 chunk 里「我们接管过、且侧表还有水」的 section 重新唤醒。
     *
     * <p>★ 这是「重进世界后静止的水永远不再流动」的解药：卸载时我们丢掉了全部内存待办
     * （原版是把待办交还原版 fluid tick 队列），只能靠加载时按侧表重建。
     */
    private static void onChunkLoad(final ChunkEvent.Load event) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        // 唤醒侧表里还有水的水位 + 首次登记自然水源（见 WaterPhysicsModule#onChunkLoad）
        WaterPhysicsModule.onChunkLoad(level, chunk);
    }

    /**
     * 区块卸载：把「已算出但还没写回、且属于这个区块」的水位写进这个正要卸载的区块。
     *
     * <p>★ 这是写这个区块的<b>最后一个窗口</b>：卸载路径上它已经被摘出
     * visibleChunkMap（写回自己用 {@code getChunkNow} 查不到它），紧接着就是
     * {@code ChunkMap.save(chunk)} —— 写回器靠事件给出的区块实例落水，不重新查。
     *
     * <p>只有真加载过的区块才可能走到这里（事件本身就是「一个已加载区块被卸载」），
     * 且事件里的区块实例只在「队首 plan 的 region 正好落在它里面」时被使用。
     */
    private static void onChunkUnload(final ChunkEvent.Unload event) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        WaterPhysicsModule.onChunkUnload(level, chunk);
    }

    /** 任何放置路径（玩家/指令/结构/其他 mod）都会走到这里。 */
    private static void onEntityPlace(final BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            post(level, event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
        }
    }

    /** 破坏会改变局部可容纳性（水位要重算）。 */
    private static void onBreak(final BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            post(level, event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
        }
    }

    /** 世界保存：先把没落地的水位写进世界与侧表，再把侧表存进存档并立刻写盘。 */
    private static void onLevelSave(final LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WaterPhysicsModule.onLevelSave(level);
        }
    }

    /** 维度卸载：落盘 + 释放内存。 */
    private static void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WaterPhysicsModule.onLevelUnload(level);
        }
    }

    private static void post(final ServerLevel level, final int x, final int y, final int z) {
        WaterPhysicsModule.bridge(level).postCell(x, y, z);
    }
}
