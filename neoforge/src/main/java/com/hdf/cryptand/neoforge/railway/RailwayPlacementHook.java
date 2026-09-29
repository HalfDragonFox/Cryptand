package com.hdf.cryptand.neoforge.railway;

import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographTapCache;
import com.hdf.cryptand.neoforge.railway.train.TrainTapCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * CEE 端子放置/拆除入网钩子（2026-08-22 "CEE 全自管电路"）。
 * <p>
 * 与 CryptandNeoForge 的 PowerGrid 放置钩子（ElectricBlockEntity/IElectricEntity）
 * 互补：本钩子只处理 cryptand:cee_* 端子方块——放置 addDevice(1 端子)、拆除 removePoint，
 * 使受电弓/接触网悬挂块可靠进入自管 WireNetwork（放下即建网 / 接拆线）。
 */
public final class RailwayPlacementHook {

    private static volatile boolean registered = false;

    public static void registerBus() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.register(RailwayPlacementHook.class);
    }

    @SubscribeEvent
    public static void onPlaced(BlockEvent.EntityPlaceEvent ev) {
        try {
            if (ev.getLevel() == null || ev.getLevel().isClientSide()) return;
            if (!(ev.getLevel() instanceof ServerLevel sl)) return;
            BlockPos pos = ev.getPos().immutable();
            net.minecraft.world.level.block.state.BlockState st = sl.getBlockState(pos);
            // PowerGrid 端子由 CryptandNeoForge 的 EntityPlaceEvent 钩子（IElectric）处理
            if (st.getBlock() instanceof org.patryk3211.powergrid.electricity.base.IElectric)
                return;
            // 接线端子统一（2026-08-22 用户架构）：非 PowerGrid 端子放置即建网
            if (!WireTerminals
                    .isTerminal(sl, pos, st))
                return;
            int terms = WireTerminals
                    .terminalCount(sl, pos, st);
            int before = WireNetworkManager.get().nodeCount();
            boolean ok = WireNetworkManager.get().addDevice(pos, terms);
            WireNetworkManager.get().reconstruct(sl);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CeeNet] placed {} terms={} ok={} nodes {}->{}", pos, terms, ok, before,
                    WireNetworkManager.get().nodeCount());
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onBroken(BlockEvent.BreakEvent ev) {
        try {
            if (ev.getLevel() == null || ev.getLevel().isClientSide()) return;
            if (!(ev.getLevel() instanceof ServerLevel sl)) return;
            BlockPos pos = ev.getPos();
            if (pos == null) return;
            net.minecraft.world.level.block.state.BlockState st = sl.getBlockState(pos);
            // PowerGrid 端子拆除由 ElectricBlockEntityRemoveMixin 处理
            if (st.getBlock() instanceof org.patryk3211.powergrid.electricity.base.IElectric)
                return;
            if (!WireTerminals
                    .isTerminal(sl, pos, st))
                return;
            // 受电弓拆除 → 清滑触头（避免残留触点跨世界/方块误连）
            if (PantographBlock.class
                    .isInstance(st.getBlock())) {
                PantographTapCache.set(pos, null);
            }
            int terms = WireTerminals
                    .terminalCount(sl, pos, st);
            for (int t = 0; t < terms; t++) {
                WireNetworkManager.get().removePoint(new WirePoint("B" + pos + "#" + t));
            }
            WireNetworkManager.get().reconstruct(sl);
        } catch (Throwable ignored) {
        }
    }

    /** 世界卸载：清滑触头缓存（防止跨世界残留触点误连新世界同位置接触网） */
    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload ev) {
        try {
            PantographTapCache.clear();
            TrainTapCache.clear();
        } catch (Throwable ignored) {
        }
    }

    private RailwayPlacementHook() {
    }
}