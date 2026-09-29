/**
 * ===== 外设模拟传动器客户端注册（MOD 总线，2026-09-14）=====
 *
 * 三件事：注册 BE 渲染器（非 Flywheel 路径）、注册 Flywheel 可视化（主路径）、
 * 注册额外烘焙模型（齿轮 partial model）。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class PeripheralTransmissionClient {

    /** Flywheel visual 是否注册成功（BER 让位判定依赖它，防止"两边都不画"） */
    private static volatile boolean visualRegistered;

    public static boolean visualRegistered() {
        return visualRegistered;
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!PeripheralTransmissionRegistry.isRegistered()) {
            return;
        }
        event.registerBlockEntityRenderer(
                PeripheralTransmissionRegistry.PERIPHERAL_TRANSMISSION_BE.get(),
                PeripheralTransmissionRenderer::new);
        registerVisual();
    }

    private static void registerVisual() {
        try {
            @SuppressWarnings("unchecked")
            BlockEntityType<PeripheralTransmissionBlockEntity> type =
                    (BlockEntityType<PeripheralTransmissionBlockEntity>) PeripheralTransmissionRegistry
                            .PERIPHERAL_TRANSMISSION_BE.get();
            dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                    .<PeripheralTransmissionBlockEntity>builder(type)
                    .factory((ctx, be, pt) -> new PeripheralTransmissionVisual(ctx, be, pt))
                    .apply();
            visualRegistered = true;
            log().info("[PeripheralTransmission] Flywheel visual 已注册（轴 + 内部齿轮走可视化路径）");
        } catch (Throwable t) {
            visualRegistered = false;
            log().warn("[PeripheralTransmission] Flywheel visual 注册失败，回退 BER 渲染: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        if (!PeripheralTransmissionRegistry.isRegistered()) {
            return;
        }
        event.register(ModelResourceLocation.standalone(PeripheralTransmissionPartialModels.COG_ID));
    }

    private static org.apache.logging.log4j.Logger log() {
        try {
            return com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER;
        } catch (Throwable t) {
            return org.apache.logging.log4j.LogManager.getLogger("cryptand");
        }
    }

    private PeripheralTransmissionClient() {
    }
}
