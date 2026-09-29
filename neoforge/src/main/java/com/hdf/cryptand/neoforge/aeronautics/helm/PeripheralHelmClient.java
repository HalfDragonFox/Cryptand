/**
 * ===== 外设船舵客户端注册（MOD 总线，2026-09-13） =====
 *
 * 只在 Dist.CLIENT 加载（AeronauticsModule 内判定）；注册：
 * <ul>
 *   <li>方块实体渲染器（非 Flywheel 路径：半轴 + 舵轮）；</li>
 *   <li><b>Flywheel 可视化</b>（{@link PeripheralHelmVisual}，与官方船舵同款双路径）——
 *       Flywheel 开启时 BER 让位、由 visual 渲染；</li>
 *   <li>额外烘焙模型（wheel.obj 不被 blockstate 引用，需显式注册才能 bake）。</li>
 * </ul>
 * ⚠ 让位判定带"visual 是否注册成功"标志：注册失败时 BER 继续渲染，避免方块整体消失。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class PeripheralHelmClient {

    /** Flywheel visual 是否注册成功（BER 的让位判定依赖它，防止"两边都不画"） */
    private static volatile boolean visualRegistered;

    public static boolean visualRegistered() {
        return visualRegistered;
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!PeripheralHelmRegistry.isRegistered()) {
            return;
        }
        event.registerBlockEntityRenderer(PeripheralHelmRegistry.PERIPHERAL_HELM_BE.get(),
                PeripheralHelmRenderer::new);
        registerVisual();
    }

    /** Flywheel 路径（拷贝官方船舵的双路径做法：renderer + visual 都注册，运行时二选一） */
    private static void registerVisual() {
        try {
            @SuppressWarnings("unchecked")
            BlockEntityType<PeripheralHelmBlockEntity> type =
                    (BlockEntityType<PeripheralHelmBlockEntity>) PeripheralHelmRegistry
                            .PERIPHERAL_HELM_BE.get();
            dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                    .<PeripheralHelmBlockEntity>builder(type)
                    .factory((ctx, be, pt) -> new PeripheralHelmVisual(ctx, be, pt))
                    .apply();
            visualRegistered = true;
            log().info("[PeripheralHelm] Flywheel visual 已注册（轴 + 舵轮走可视化路径）");
        } catch (Throwable t) {
            visualRegistered = false;
            log().warn("[PeripheralHelm] Flywheel visual 注册失败，回退 BER 渲染: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        if (!PeripheralHelmRegistry.isRegistered()) {
            return;
        }
        event.register(ModelResourceLocation.standalone(PeripheralHelmPartialModels.WHEEL_ID));
    }

    private static org.apache.logging.log4j.Logger log() {
        try {
            return com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER;
        } catch (Throwable t) {
            return org.apache.logging.log4j.LogManager.getLogger("cryptand");
        }
    }

    private PeripheralHelmClient() {
    }
}
