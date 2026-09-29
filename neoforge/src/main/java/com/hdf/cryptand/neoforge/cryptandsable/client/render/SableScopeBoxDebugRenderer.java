/**
 * ===== 物理结构扫描收集范围方框（2026-09-03，debugScopeBox 调试开关） =====
 *
 * 门控：com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable.SABLE_DEBUG_SCOPE_BOX = true（客户端，无需重启）。
 * 每帧绘制三类线框（颜色分工，2026-09-03 + 2026-09-05 用户定案）：
 *   - 白色：结构自身包围盒（boundMin..boundMax，以当前位姿为中心）——物理体实际几何范围
 *   - 蓝色：世界扫描区域 = 结构包围盒（boundMin..boundMax）外扩 radius（世界方块收集
 *     clip = bounds ± radius；结构移动后保持收集时范围）
 *   - 黄色：被扫描到的实心方块（服务端 mixin 采集 SableDebugScanStore；画小方框）
 *
 * 绿色（物理结构扫描区，位姿±(半尺寸+radius)）已放弃置区（2026-09-05 一世界一空间：
 * 不再用于空间合并判定）。
 *
 * 渲染：RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS）+ RenderType.lines
 * （与 NetworkColorRenderer / BlockedHighlightRenderer 同机制，12 条边线框）。
 * 位姿用客户端插值快照（SableClientRenderModule.interpPose），跟随渲染帧率。
 *
 * ⚠ 2026-09-03 注意：本类放【非 mixin 包】（client/render）——cryptandsable.debug 包
 * 被 cryptand.sable.debug.mixins.json 声明为 mixin 专属包，放入普通 @EventBusSubscriber
 * 类会触发 IllegalClassLoadError（启动崩溃）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.client.render;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public final class SableScopeBoxDebugRenderer {

    private SableScopeBoxDebugRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        try {
            // ★ 2026-09-06 【core 总闸】核心关闭 → 调试方框零行为（先于 debugScopeBox 判断）
            if (!ConfigCryptandSable
                    .ENABLE_CRYPTAND_SABLE_CORE.get()) return;
            if (!ConfigCryptandSable.SABLE_DEBUG_SCOPE_BOX.get()) return;
            final net.minecraft.client.Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.renderBuffers() == null) return;

            final float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            final PoseStack pose = event.getPoseStack();
            final Vec3 cam = event.getCamera().getPosition();
            final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            final VertexConsumer lines = buffers.getBuffer(RenderType.lines());

            final int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();

            for (SableSubLevelRenderData data : SableClientRenderModule.INSTANCE.allSubLevels()) {
                if (data.blocks() == null || data.blocks().isEmpty()) continue;

                // 当前物理位姿（插值，跟随渲染帧率）
                final SableMessages.PoseSnapshot ps =
                        SableClientRenderModule.INSTANCE.interpPose(data.runtimeId(), partialTick);
                double px, py, pz;
                if (ps != null) {
                    px = ps.px(); py = ps.py(); pz = ps.pz();
                } else {
                    px = data.anchor().getX() + 0.5;
                    py = data.anchor().getY() + 0.5;
                    pz = data.anchor().getZ() + 0.5;
                }

                // ① 结构自身包围盒（白色）——以 pose 为中心、原始尺寸（跟随结构移动）。
                final int bMinX = data.boundMinX(), bMinY = data.boundMinY(), bMinZ = data.boundMinZ();
                final int bMaxX = data.boundMaxX(), bMaxY = data.boundMaxY(), bMaxZ = data.boundMaxZ();
                final boolean boundsOk = bMaxX >= bMinX && bMaxY >= bMinY && bMaxZ >= bMinZ;
                if (boundsOk) {
                    final double hx = (bMaxX - bMinX + 1) * 0.5;
                    final double hy = (bMaxY - bMinY + 1) * 0.5;
                    final double hz = (bMaxZ - bMinZ + 1) * 0.5;
                    renderBox(pose, lines, cam,
                            px - hx - 0.01, py - hy - 0.01, pz - hz - 0.01,
                            px + hx + 0.01, py + hy + 0.01, pz + hz + 0.01,
                            1.0f, 1.0f, 1.0f, 0.9f);

                    // ② 世界扫描区域（蓝色）——★ 2026-09-05 以【当前物理位姿】为中心 ±（结构
                    //    半尺寸 + radius），跟随结构移动（与服务端 collectWorldAtBounds clip 同
                    //    语义；不再用固定 boundMin/Max——结构移动后蓝框跟随）。
                    renderBox(pose, lines, cam,
                            px - hx - radius, py - hy - radius, pz - hz - radius,
                            px + hx + radius, py + hy + radius, pz + hz + radius,
                            0.0f, 0.0f, 1.0f, 0.8f);
                }
            }

            // ③ 被扫描到的实心方块（黄色小方框）——★ 2026-09-05 服务端 mixin 采集
            //    （WorldChunkUploaderHighlightMixin → SableDebugScanStore；单机集成服务器
            //    同进程共享）。每格画一个半格线框。
            for (final long[] b : com.hdf.cryptand.neoforge.cryptandsable.server
                    .SableDebugScanStore.BLOCKS.values()) {
                renderBox(pose, lines, cam,
                        b[0] + 0.02, b[1] + 0.02, b[2] + 0.02,
                        b[0] + 0.98, b[1] + 0.98, b[2] + 0.98,
                        1.0f, 1.0f, 0.0f, 0.7f);
            }

            buffers.endBatch(RenderType.lines());
        } catch (final Throwable ignored) {
        }
    }

    /** 绘制一个轴对齐线框（12 条边），同 NetworkColorRenderer.renderBox 机制。 */
    private static void renderBox(PoseStack pose, VertexConsumer consumer, Vec3 cam,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ,
                                  float r, float g, float b, float a) {
        pose.pushPose();
        pose.translate(minX - cam.x, minY - cam.y, minZ - cam.z);
        final Matrix4f m = pose.last().pose();
        final float dx = (float) (maxX - minX);
        final float dy = (float) (maxY - minY);
        final float dz = (float) (maxZ - minZ);
        final float x0 = 0f, y0 = 0f, z0 = 0f;
        final float x1 = dx, y1 = dy, z1 = dz;

        // 12 条边
        line(consumer, m, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(consumer, m, x0, y0, z1, x1, y0, z1, r, g, b, a);
        line(consumer, m, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(consumer, m, x0, y1, z1, x1, y1, z1, r, g, b, a);

        line(consumer, m, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(consumer, m, x0, y0, z1, x0, y1, z1, r, g, b, a);
        line(consumer, m, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(consumer, m, x1, y0, z1, x1, y1, z1, r, g, b, a);

        line(consumer, m, x0, y0, z0, x0, y0, z1, r, g, b, a);
        line(consumer, m, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(consumer, m, x0, y1, z0, x0, y1, z1, r, g, b, a);
        line(consumer, m, x1, y1, z0, x1, y1, z1, r, g, b, a);
        pose.popPose();
    }

    private static void line(VertexConsumer consumer, Matrix4f m,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float r, float g, float b, float a) {
        // ⚠ RenderType.lines() 的 vertex format 含 Normal → 必须 setNormal，否则
        //   IllegalStateException: Missing elements in vertex: Normal
        consumer.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
        consumer.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
    }
}