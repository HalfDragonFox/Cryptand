/**
 * ===== 网络颜色外框渲染（调试，2026-08-21 用户要求） =====
 *
 * 门控：ConfigLoad.DEBUG_NETWORK_COLORS = true。每帧从
 * {@link ClientNetworkColorStore} 取"方块 → 网络颜色"，给每个元件方块画
 * 该网络颜色的 12 条边线框——同网络同色、分裂网络异色，直观检查网络
 * 合并/拆分是否正确（设备是否缺端子/网络是否误分裂）。
 *
 * 渲染：RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS）+ RenderType.lines
 * （与 BlockedHighlightRenderer / WireOutlineRenderer 同机制，方块外框 12 条边）。
 */
package com.hdf.cryptand.neoforge.core.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.Map;

public final class NetworkColorRenderer {

    private NetworkColorRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        try {
            if (!ConfigLoad.DEBUG_NETWORK_COLORS.get()) return;
            ClientNetworkColorStore.refresh();
            Map<BlockPos, Integer> colors = ClientNetworkColorStore.colors();
            if (colors.isEmpty()) return;
            PoseStack pose = event.getPoseStack();
            Vec3 cam = event.getCamera().getPosition();
            MultiBufferSource.BufferSource buffers =
                    Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
            for (Map.Entry<BlockPos, Integer> e : colors.entrySet()) {
                renderBox(pose, consumer, cam, e.getKey(), e.getValue());
            }
            buffers.endBatch(RenderType.lines());
        } catch (Throwable ignored) {
        }
    }

    /** 单个方块彩色线框（12 条边）。
     *  ⚠ 2026-08-23 物理化（Sable 亚层 plot 坐标）：端点方块位置可能是亚层
     *  plot 大坐标（>= 20,480,000），直接 translate 会画到世界外 → 必须按
     *  CeePoseUtil.toWorld（getContaining + logicalPose 投影）换算真实世界
     *  位置渲染（同 CEE WireRenderer getPosition 语义）。非亚层原样。 */
    private static void renderBox(PoseStack pose, VertexConsumer consumer, Vec3 cam,
                                  BlockPos pos, int argb) {
        try {
            pose.pushPose();
            Vec3 world = com.hdf.cryptand.neoforge.cee.CeePoseUtil.toWorld(
                    Minecraft.getInstance().level, pos,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            pose.translate(world.x - 0.5 - cam.x, world.y - 0.5 - cam.y,
                    world.z - 0.5 - cam.z);
            Matrix4f m = pose.last().pose();
            float r = ((argb >> 16) & 0xFF) / 255f;
            float g = ((argb >> 8) & 0xFF) / 255f;
            float b = (argb & 0xFF) / 255f;
            float a = 0.9f;
            float e = 0.002f; // 外扩，防 z-fighting
            float x0 = -e, y0 = -e, z0 = -e;
            float x1 = 1f + e, y1 = 1f + e, z1 = 1f + e;
            // 底面 4 边
            line(consumer, m, r, g, b, a, x0, y0, z0, x1, y0, z0);
            line(consumer, m, r, g, b, a, x1, y0, z0, x1, y0, z1);
            line(consumer, m, r, g, b, a, x1, y0, z1, x0, y0, z1);
            line(consumer, m, r, g, b, a, x0, y0, z1, x0, y0, z0);
            // 顶面 4 边
            line(consumer, m, r, g, b, a, x0, y1, z0, x1, y1, z0);
            line(consumer, m, r, g, b, a, x1, y1, z0, x1, y1, z1);
            line(consumer, m, r, g, b, a, x1, y1, z1, x0, y1, z1);
            line(consumer, m, r, g, b, a, x0, y1, z1, x0, y1, z0);
            // 竖 4 边
            line(consumer, m, r, g, b, a, x0, y0, z0, x0, y1, z0);
            line(consumer, m, r, g, b, a, x1, y0, z0, x1, y1, z0);
            line(consumer, m, r, g, b, a, x1, y0, z1, x1, y1, z1);
            line(consumer, m, r, g, b, a, x0, y0, z1, x0, y1, z1);
            pose.popPose();
        } catch (Throwable ignored) {
            pose.popPose();
        }
    }

    private static void line(VertexConsumer c, Matrix4f m, float r, float g, float b, float a,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1) {
        c.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
        c.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
    }
}
