/**
 * ===== 导线阻挡红色方框高亮（2026-08-14） =====
 *
 * RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS）中把
 * ClientBlockedStore 的阻挡方块画成红色线框（RenderType.lines，12 条边，
 * 1.002 外扩防 z-fighting）——像 Create 冲突/蓝图选区那样提示"这里被挡了"。
 * 数据来源：服务端放置导线阻挡检测 → WireBlockedPayload（3 秒过期）。
 */

package com.hdf.cryptand.neoforge.powergrid.client.wire;

import com.hdf.cryptand.Cryptand;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

public final class BlockedHighlightRenderer {

    private BlockedHighlightRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<BlockPos> blocks = ClientBlockedStore.blocks();
        if (blocks.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers =
                Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
        for (BlockPos pos : blocks) {
            renderBox(pose, consumer, cam, pos);
        }
        buffers.endBatch(RenderType.lines());
    }

    /** 单个方块红色线框（12 条边） */
    private static void renderBox(PoseStack pose, VertexConsumer consumer, Vec3 cam, BlockPos pos) {
        pose.pushPose();
        pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        Matrix4f m = pose.last().pose();
        float e = 0.002f; // 外扩，防与方块面 z-fighting
        float x0 = -e, y0 = -e, z0 = -e;
        float x1 = 1f + e, y1 = 1f + e, z1 = 1f + e;
        // 底面 4 边
        line(consumer, m, x0, y0, z0, x1, y0, z0);
        line(consumer, m, x1, y0, z0, x1, y0, z1);
        line(consumer, m, x1, y0, z1, x0, y0, z1);
        line(consumer, m, x0, y0, z1, x0, y0, z0);
        // 顶面 4 边
        line(consumer, m, x0, y1, z0, x1, y1, z0);
        line(consumer, m, x1, y1, z0, x1, y1, z1);
        line(consumer, m, x1, y1, z1, x0, y1, z1);
        line(consumer, m, x0, y1, z1, x0, y1, z0);
        // 竖 4 边
        line(consumer, m, x0, y0, z0, x0, y1, z0);
        line(consumer, m, x1, y0, z0, x1, y1, z0);
        line(consumer, m, x1, y0, z1, x1, y1, z1);
        line(consumer, m, x0, y0, z1, x0, y1, z1);
        pose.popPose();
    }

    private static void line(VertexConsumer c, Matrix4f m,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1) {
        c.addVertex(m, x0, y0, z0).setColor(1f, 0f, 0f, 0.9f).setNormal(0f, 1f, 0f);
        c.addVertex(m, x1, y1, z1).setColor(1f, 0f, 0f, 0.9f).setNormal(0f, 1f, 0f);
    }
}