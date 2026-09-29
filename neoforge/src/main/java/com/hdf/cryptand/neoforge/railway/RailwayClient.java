package com.hdf.cryptand.neoforge.railway;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.railway.catenary.CatenaryHolderRenderer;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端注册（仅 CLIENT 分发加载，服务端不加载本类 → 无 RuntimeDistCleaner 风险）。
 * 注册受电弓 / 接触网悬挂块的方块实体渲染器。
 */
// ⚠ 2026-08-30 根因修复（与 CapacitorStartMotorsClient 同问题）：RegisterRenderers
// 是 MOD 总线事件；bus=Bus.MOD 已弃用 → 编程式注册（CryptandNeoForge 客户端分支
// modEventBus.register(RailwayClient.class)）。
public final class RailwayClient {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // ⚠ 2026-08-22 与注册门控一致：只认 RailwayRegistry.isRegistered()
        //   内存标志（不能用 ConfigLoad——构造期配置可能未加载，时序不一致
        //   导致 get() 未绑定 BE → NPE unbound value）。
        if (!RailwayRegistry.isRegistered()) return;
        event.registerBlockEntityRenderer(RailwayRegistry.CEE_PANTOGRAPH_BE.get(), PantographRenderer::new);
        event.registerBlockEntityRenderer(RailwayRegistry.CEE_CATENARY_HOLDER_BE.get(), CatenaryHolderRenderer::new);
    }

    private RailwayClient() {
    }
}