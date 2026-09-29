/**
 * ===== 网络颜色外框渲染（调试，2026-08-21 用户要求） =====
 *
 * 门控：com.hdf.cryptand.neoforge.core.config.ConfigPowerGrid.DEBUG_NETWORK_COLORS = true。每帧从
 * {@link ClientNetworkColorStore} 取"方块 → 网络颜色"，给每个元件方块画
 * 该网络颜色的 12 条边线框——同网络同色、分裂网络异色，直观检查网络
 * 合并/拆分是否正确（设备是否缺端子/网络是否误分裂）。
 *
 * 渲染：RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS）+ RenderType.lines
 * （与 BlockedHighlightRenderer / WireOutlineRenderer 同机制，方块外框 12 条边）。
 */
package com.hdf.cryptand.neoforge.powergrid.client;

import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.cee.CeePoseUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * ⚠⚠ 2026-09-13 修复（用户："网络没显示"）：本类原先【只有 @SubscribeEvent，没有
 *  @EventBusSubscriber，也没有在 PowergridModule 里注册】⇒ 游戏总线上根本没有它的
 *  监听器，onRenderLevel 从未被调用过（玩家在接线时看到的方框来自另一个渲染器
 *  WirePlacementPreviewRenderer，不是本类）。
 *  `RenderLevelStageEvent` 属于【游戏总线】事件 —— 与 WirePlacementPreviewRenderer
 *  同样用 @EventBusSubscriber 注册（其内部转成 NeoForge.EVENT_BUS 监听）。
 */
@net.neoforged.fml.common.EventBusSubscriber(
        value = net.neoforged.api.distmarker.Dist.CLIENT,
        modid = com.hdf.cryptand.Cryptand.MOD_ID)
public final class NetworkColorRenderer {

    /** 常驻显示的渲染半径（格；超出不画，防大网络每帧上千方框） */
    private static final double MAX_DIST_SQ = 96.0 * 96.0;
    /** 单帧方框上限（极端情况的兜底保护） */
    private static final int MAX_BOXES = 4096;

    private NetworkColorRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        try {
            // ===== 2026-09-13 用户："仅 debug 模式开启时有效" =====
            // 只由 DEBUG_NETWORK_COLORS 控制（默认 false）。关闭时完全不渲染，
            // 也就不会去 refresh/遍历颜色表 —— 零开销。
            // 服务端同步（NetworkColorSyncMixin）用同一个开关，关闭时也不上传。
            if (!ConfigPowerGrid.DEBUG_NETWORK_COLORS.get()) return;
            ClientNetworkColorStore.refresh();
            Map<BlockPos, Integer> colors = ClientNetworkColorStore.colors();
            if (colors.isEmpty()) return;
            PoseStack pose = event.getPoseStack();
            Vec3 cam = event.getCamera().getPosition();
            MultiBufferSource.BufferSource buffers =
                    Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
            int drawn = 0;
            for (Map.Entry<BlockPos, Integer> e : colors.entrySet()) {
                // 常驻显示的保护：远处网络不画（防止大网络每帧上千方框拖慢渲染）
                try {
                    if (cam.distanceToSqr(Vec3.atCenterOf(e.getKey())) > MAX_DIST_SQ) {
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                if (++drawn > MAX_BOXES) break;
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
            Vec3 world = CeePoseUtil.toWorld(
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