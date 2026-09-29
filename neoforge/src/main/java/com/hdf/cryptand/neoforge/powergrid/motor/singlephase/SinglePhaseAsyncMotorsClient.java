package com.hdf.cryptand.neoforge.powergrid.motor.singlephase;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.patryk3211.powergrid.kinetics.motor.ElectricMotorRenderer;

/**
 * 客户端注册（仅 CLIENT 分发；2026-08-30 单相异步电机 2/4/6/8 极）。
 * <p>
 * 给 4 个极数的 BE type 注册：
 *  - {@link ElectricMotorRenderer}（非 Flywheel 路径：renderSafe 画 Create shaft 轴）；
 *  - {@code HalfShaftVisual}（Flywheel 路径：SingleAxisRotatingVisual + SHAFT_HALF 半轴）——
 *    两个都注册，保证任何渲染后端下【转杆轴一直显示】（照 PowerGrid 普通电机：
 *    .visual(HalfShaftVisual) + .renderer(ElectricMotorRenderer)）。
 */
public final class SinglePhaseAsyncMotorsClient {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (int poles : SinglePhaseAsyncMotors.POLES) {
            net.minecraft.world.level.block.entity.BlockEntityType<?> type =
                    SinglePhaseAsyncMotors.beTypeFor(poles);
            if (type == null) continue;
            // 非 Flywheel：Create renderer 画轴（getRotatedModel → SHAFT_HALF）
            @SuppressWarnings("unchecked")
            net.minecraft.world.level.block.entity.BlockEntityType<SinglePhaseAsyncMotorBlockEntity> typed =
                    (net.minecraft.world.level.block.entity.BlockEntityType<SinglePhaseAsyncMotorBlockEntity>) type;
            event.registerBlockEntityRenderer(typed,
                    ctx -> new ElectricMotorRenderer<SinglePhaseAsyncMotorBlockEntity>(ctx));
            // Flywheel：HalfShaftVisual（轴视觉）——Flywheel 启用时 renderSafe return，
            // 轴靠视觉渲染（缺它 → 无轴）
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.world.level.block.entity.BlockEntityType<SinglePhaseAsyncMotorBlockEntity> typed2 =
                        (net.minecraft.world.level.block.entity.BlockEntityType<SinglePhaseAsyncMotorBlockEntity>) type;
                dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                        .<SinglePhaseAsyncMotorBlockEntity>builder(typed2)
                        .factory((ctx, be, pt) -> new org.patryk3211.powergrid.kinetics.base
                                .HalfShaftVisual<SinglePhaseAsyncMotorBlockEntity>(ctx, be, pt))
                        .apply();
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[MotorRender] HalfShaftVisual register failed for {}p: {}",
                        poles, t.toString());
            }
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[MotorRender] registered ElectricMotorRenderer + HalfShaftVisual -> single_phase_motor 2/4/6/8p BE");
    }

    /**
     * ===== 物品形态渲染扩展（2026-09-13 用户："手持时转轴没渲染出来，放置是
     *  能渲染的"）=====
     *
     * 放置后的方块有 BE 渲染器（ElectricMotorRenderer）+ Flywheel 视觉
     * （HalfShaftVisual）画轴；但物品形态走的是另一条路 —— 必须注册
     * IClientItemExtensions.getCustomRenderer() 指向 BlockEntityWithoutLevelRenderer，
     * 否则手持/物品栏只显示静态方块模型（没有轴）。
     */
    @SubscribeEvent
    public static void onRegisterClientExtensions(
            net.neoforged.neoforge.client.extensions.common
                    .RegisterClientExtensionsEvent event) {
        for (int poles : SinglePhaseAsyncMotors.POLES) {
            net.minecraft.world.item.Item it = SinglePhaseAsyncMotors.itemFor(poles);
            if (it == null) continue;
            event.registerItem(new net.neoforged.neoforge.client.extensions.common
                    .IClientItemExtensions() {
                @Override
                public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
                        getCustomRenderer() {
                    com.hdf.cryptand.neoforge.powergrid.client.SinglePhaseMotorItemRenderer r =
                            com.hdf.cryptand.neoforge.powergrid.client
                                    .SinglePhaseMotorItemRenderer.get();
                    // 初始化失败 → 交回原版默认渲染器（绝不让 null 传出去）
                    //  ⚠ 匿名类里不能用 IClientItemExtensions.super.xxx()，
                    //    直接用 ItemRenderer 提供的默认 BEWLR 等价物。
                    return r != null ? r
                            : net.minecraft.client.Minecraft.getInstance()
                                    .getItemRenderer().getBlockEntityRenderer();
                }
            }, it);
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[MotorRender] registered IClientItemExtensions (item-form shaft) -> single_phase_motor 2/4/6/8p item");
    }

    private SinglePhaseAsyncMotorsClient() {}
}
