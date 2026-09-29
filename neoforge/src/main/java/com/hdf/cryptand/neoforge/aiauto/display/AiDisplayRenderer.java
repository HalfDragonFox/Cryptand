package com.hdf.cryptand.neoforge.aiauto.display;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ===== 展示方块渲染器：按需渲染物品或方块模型 =====
 *
 * <p>展示位置在方块上方（{@code yOffset}），可缩放与自转，便于从各个角度观察模型是否正确。</p>
 */
public class AiDisplayRenderer implements BlockEntityRenderer<AiDisplayBlockEntity> {

    public AiDisplayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AiDisplayBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        final ItemStack item = be.displayItem();
        final BlockState state = be.displayBlock();
        if (item.isEmpty() && state == null) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5, be.yOffset(), 0.5);
        if (be.spin() != 0.0F) {
            final float angle = (System.currentTimeMillis() % 360_000L) / 1000.0F * be.spin();
            pose.mulPose(Axis.YP.rotationDegrees(angle));
        }
        pose.scale(be.scale(), be.scale(), be.scale());
        try {
            if (state != null) {
                Minecraft.getInstance().getBlockRenderer()
                        .renderSingleBlock(state, pose, buffers, light, overlay);
            } else {
                Minecraft.getInstance().getItemRenderer().renderStatic(item,
                        ItemDisplayContext.GROUND, light, overlay, pose, buffers,
                        be.getLevel(), 0);
            }
        } catch (Throwable ignored) {
            // 调试设施：渲染异常不得影响游戏
        }
        pose.popPose();
    }
}
