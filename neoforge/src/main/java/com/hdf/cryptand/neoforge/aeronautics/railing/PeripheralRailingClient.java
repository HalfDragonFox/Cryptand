/**
 * ===== 外设栏杆（按钮）客户端注册（2026-09-13） =====
 * 渲染方式与外设拉杆一致：BER（非 Flywheel 路径）+ Flywheel 可视化 + 额外烘焙模型。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class PeripheralRailingClient {

    private static volatile boolean visualRegistered;

    public static boolean visualRegistered() {
        return visualRegistered;
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!PeripheralRailingRegistry.isRegistered()) {
            return;
        }
        event.registerBlockEntityRenderer(PeripheralRailingRegistry.PERIPHERAL_RAILING_BE.get(),
                PeripheralRailingRenderer::new);
        registerVisual();
    }

    private static void registerVisual() {
        try {
            @SuppressWarnings("unchecked")
            BlockEntityType<PeripheralRailingBlockEntity> type =
                    (BlockEntityType<PeripheralRailingBlockEntity>) PeripheralRailingRegistry
                            .PERIPHERAL_RAILING_BE.get();
            dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                    .<PeripheralRailingBlockEntity>builder(type)
                    .factory((ctx, be, pt) -> new PeripheralRailingVisual(ctx, be, pt))
                    .apply();
            visualRegistered = true;
            log().info("[PeripheralRailing] Flywheel visual 已注册（手柄/按钮/指示灯走可视化路径）");
        } catch (Throwable t) {
            visualRegistered = false;
            log().warn("[PeripheralRailing] Flywheel visual 注册失败，回退 BER 渲染: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        if (!PeripheralRailingRegistry.isRegistered()) {
            return;
        }
        event.register(ModelResourceLocation.standalone(PeripheralRailingPartialModels.HANDLE_ID));
        event.register(ModelResourceLocation.standalone(PeripheralRailingPartialModels.BUTTON_ID));
        event.register(ModelResourceLocation.standalone(PeripheralRailingPartialModels.DIODE_ID));
    }

    private static org.apache.logging.log4j.Logger log() {
        try {
            return com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER;
        } catch (Throwable t) {
            return org.apache.logging.log4j.LogManager.getLogger("cryptand");
        }
    }

    private PeripheralRailingClient() {
    }
}
