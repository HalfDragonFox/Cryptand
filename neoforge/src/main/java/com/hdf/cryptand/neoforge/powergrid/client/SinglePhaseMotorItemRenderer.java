package com.hdf.cryptand.neoforge.powergrid.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ===== 自研电机【物品形态】轴渲染（2026-09-13 用户：「手持时转轴没渲染出来，
 *       放置是能渲染的」）=====
 *
 * 原因：放置后的方块由两条路画轴 ——
 *   · ElectricMotorRenderer（BE 渲染器，getRotatedModel → CachedBuffers.block(轴)）
 *   · HalfShaftVisual（Flywheel 路径）
 * 而【物品形态】（手持 / 物品栏 / GUI）走的是完全不同的第三条路：
 *   IClientItemExtensions.getCustomRenderer() → BlockEntityWithoutLevelRenderer。
 * 没注册它 ⇒ 物品只显示静态方块模型 ⇒ 看不到轴。
 *
 * 这里沿用与 BE 渲染器【同一个】轴模型（Create 的 SHAFT，按轴向摆放），
 * 角度固定（物品静止）。物品没有方块状态，因此轴向取默认 Y —— 与放置朝向
 * 无关，但能明确表达「这是个带轴的电机」。
 */
public class SinglePhaseMotorItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static SinglePhaseMotorItemRenderer INSTANCE;

    /** 单例（Minecraft 实例就绪后懒初始化；异常 → null，调用方跳过自定义渲染） */
    public static SinglePhaseMotorItemRenderer get() {
        if (INSTANCE == null) {
            try {
                Minecraft mc = Minecraft.getInstance();
                INSTANCE = new SinglePhaseMotorItemRenderer(
                        mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
            } catch (Throwable t) {
                return null;
            }
        }
        return INSTANCE;
    }

    private SinglePhaseMotorItemRenderer(BlockEntityRenderDispatcher dispatcher,
                                         EntityModelSet models) {
        super(dispatcher, models);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        try {
            // ⚠ 注册了 getCustomRenderer 后，原版 ItemRenderer.render() 会【直接
            //   return】—— 不再渲染物品模型。所以方块本体必须在这里自己画，
            //   否则手持会变成一个"只有轴、没有电机"的怪东西。
            net.minecraft.world.level.block.Block block =
                    net.minecraft.world.level.block.Block.byItem(stack.getItem());
            if (block != null) {
                Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                        block.defaultBlockState(), pose, buffer, light, overlay,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
            }
            // ===== 转子：必须用【半轴】SHAFT_HALF，不能用整根 SHAFT =====
            // 2026-09-13 用户截图实证：物品形态"没有转子建模"。原因不是没渲染，
            // 而是画错了模型 —— Create 的 SHAFT 是 8×8 像素、完全落在方块内部，
            // 被不透明方块本体挡住 ⇒ 看起来像没画。原版电机显示的是【半轴】
            // （AllPartialModels.SHAFT_HALF，向 -Y 伸出方块外），与 BE 侧的
            // HalfShaftVisual / ElectricMotorRenderer 同源。
            // 用法照抄 PowerGrid 的 ServoRenderer：CachedBuffers.partialFacing(...)。
            SuperByteBuffer buf = CachedBuffers.partialFacing(
                    com.simibubi.create.AllPartialModels.SHAFT_HALF,
                    block.defaultBlockState(), Direction.UP);
            pose.pushPose();
            pose.translate(0.5, 0.5, 0.5); // 到方块中心（partial 模型以中心为原点）
            buf.light(light)
                    .renderInto(pose, buffer.getBuffer(RenderType.solid()));
            pose.popPose();
        } catch (Throwable ignored) {
        }
    }
}
