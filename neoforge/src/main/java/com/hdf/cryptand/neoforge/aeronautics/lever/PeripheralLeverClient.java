/**
 * ===== 外设拉杆客户端注册（MOD 总线，2026-09-13） =====
 *
 * 注册：方块实体渲染器（非 Flywheel 路径）、Flywheel 可视化、额外烘焙模型（手柄/按钮/指示灯）。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class PeripheralLeverClient {

    /** Flywheel visual 是否注册成功（BER 让位判定依赖它，防止"两边都不画"） */
    private static volatile boolean visualRegistered;

    public static boolean visualRegistered() {
        return visualRegistered;
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!PeripheralLeverRegistry.isRegistered()) {
            return;
        }
        event.registerBlockEntityRenderer(PeripheralLeverRegistry.PERIPHERAL_LEVER_BE.get(),
                PeripheralLeverRenderer::new);
        registerVisual();
    }

    private static void registerVisual() {
        try {
            @SuppressWarnings("unchecked")
            BlockEntityType<PeripheralLeverBlockEntity> type =
                    (BlockEntityType<PeripheralLeverBlockEntity>) PeripheralLeverRegistry
                            .PERIPHERAL_LEVER_BE.get();
            dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                    .<PeripheralLeverBlockEntity>builder(type)
                    .factory((ctx, be, pt) -> new PeripheralLeverVisual(ctx, be, pt))
                    .apply();
            visualRegistered = true;
            log().info("[PeripheralLever] Flywheel visual 已注册（手柄/按钮/指示灯走可视化路径）");
        } catch (Throwable t) {
            visualRegistered = false;
            log().warn("[PeripheralLever] Flywheel visual 注册失败，回退 BER 渲染: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        if (!PeripheralLeverRegistry.isRegistered()) {
            return;
        }
        event.register(ModelResourceLocation.standalone(PeripheralLeverPartialModels.HANDLE_ID));
        event.register(ModelResourceLocation.standalone(PeripheralLeverPartialModels.BUTTON_ID));
        event.register(ModelResourceLocation.standalone(PeripheralLeverPartialModels.DIODE_ID));
    }

    private static org.apache.logging.log4j.Logger log() {
        try {
            return com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER;
        } catch (Throwable t) {
            return org.apache.logging.log4j.LogManager.getLogger("cryptand");
        }
    }

    private PeripheralLeverClient() {
    }
}
