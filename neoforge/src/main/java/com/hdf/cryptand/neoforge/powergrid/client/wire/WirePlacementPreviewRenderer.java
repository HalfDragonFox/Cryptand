/**
 * ===== 自管导线放置预览 + 端子选中框（2026-08-23 用户） =====
 *
 * 统一识别层（WireTerminals）驱动的客户端预览：
 *   - 起点：主手物品 CONNECTION_DATA（WireConnection → endpoint pos#term）
 *     → WireTerminals.terminalPosWorld（PowerGrid/CEE/注册表统一精确位置）
 *   - 终点：准星 raycast 指向的端子（WireTerminals.terminalIndexAt 统一识别）
 *     → 精确位置 + 方块选中框
 *   - 渲染：起点/终点方块线框（12 边，黄色）+ 起点→终点半透明预览线
 * 原版 WirePreview（getExactPosition → CEE 中心）由 WirePreviewHideMixin 屏蔽。
 */
package com.hdf.cryptand.neoforge.powergrid.client.wire;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cee.CeePoseUtil;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public final class WirePlacementPreviewRenderer {

    private static final int OUTLINE = 0xFFFFE040; // 黄（端子选中框/预览线）

    private WirePlacementPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || mc.player == null) return;
            if (!PowerGridWireConverter
                    .isEnabled()) return;
            ItemStack stack = mc.player.getMainHandItem();
            if (stack.isEmpty()) return;
            // 导线（统一注册器：PowerGrid / CEE 线盘 / 未来线物品）
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            boolean isWire = org.patryk3211.powergrid.electricity.wire.IWire
                    .isWire(level, stack.getItem())
                    || SaggingWireRegistry
                            .byItemId(itemId) != null;
            if (!isWire) return;
            Object conn = null;
            try {
                conn = stack.get(org.patryk3211.powergrid.collections.ModdedDataComponents
                        .CONNECTION_DATA.get());
            } catch (Throwable ignored) {
            }
            if (!(conn instanceof org.patryk3211.powergrid.electricity.wire.WireConnection wc
                    && wc.endpoint() instanceof
                    org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bep)) {
                return; // 无进行中放置（未选起点）
            }
            BlockPos startPos = bep.getPos();
            int startTerm = bep.getTerminal();

            // 起点精确位置（统一层；CEE=节点映射 / PowerGrid=精确端点 / 注册表偏移）
            Vec3 start = WireTerminals.terminalPosWorld(level, startPos,
                    level.getBlockState(startPos), startTerm);
            if (start == null) return;

            // 终点：准星射线指向的端子（统一识别层）
            Vec3 end = null;
            BlockPos endPos = null;
            var hit = mc.player.pick(
                    org.patryk3211.powergrid.utility.PlayerUtilities
                            .getReachDistance(mc.player) + 1.0f, 1.0f, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                BlockPos hp = bhr.getBlockPos();
                int tIdx = WireTerminals.terminalIndexAt(level, hp,
                        level.getBlockState(hp), bhr.getLocation());
                if (tIdx >= 0) {
                    Vec3 ep = WireTerminals.terminalPosWorld(level, hp,
                            level.getBlockState(hp), tIdx);
                    if (ep != null) {
                        end = ep;
                        endPos = hp;
                    }
                }
            }
            if (end == null) return; // 未指向端子：不画预览（等待指向）

            // 渲染：起点框 + 终点框 + 预览线（RenderType.lines 半透明黄）
            PoseStack pose = event.getPoseStack();
            Vec3 cam = event.getCamera().getPosition();
            MultiBufferSource.BufferSource buffers =
                    mc.renderBuffers().bufferSource();
            VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
            renderBox(pose, consumer, cam, startPos, OUTLINE);
            if (endPos != null) renderBox(pose, consumer, cam, endPos, OUTLINE);
            renderLine(pose, consumer, cam, start, end, OUTLINE);
            buffers.endBatch(RenderType.lines());
        } catch (Throwable ignored) {
        }
    }

    private static void renderLine(PoseStack pose, VertexConsumer c, Vec3 cam,
                                   Vec3 a, Vec3 b, int argb) {
        try {
            float r = ((argb >> 16) & 0xFF) / 255f;
            float g = ((argb >> 8) & 0xFF) / 255f;
            float bl = (argb & 0xFF) / 255f;
            Matrix4f m = pose.last().pose();
            c.addVertex(m, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z))
                    .setColor(r, g, bl, 0.85f).setNormal(0f, 1f, 0f);
            c.addVertex(m, (float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z))
                    .setColor(r, g, bl, 0.85f).setNormal(0f, 1f, 0f);
        } catch (Throwable ignored) {
        }
    }

    /** 方块 12 边线框（同 NetworkColorRenderer 机制）。
     *  ⚠ 2026-08-23 物理化：方块位置为亚层 plot 大坐标时按 CeePoseUtil.toWorld
     *  投影为真实世界坐标渲染（否则框画到 2000 万格外不可见）。 */
    private static void renderBox(PoseStack pose, VertexConsumer c, Vec3 cam,
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
            float e = 0.004f;
            float x0 = -e, y0 = -e, z0 = -e, x1 = 1f + e, y1 = 1f + e, z1 = 1f + e;
            line(c, m, r, g, b, x0, y0, z0, x1, y0, z0);
            line(c, m, r, g, b, x1, y0, z0, x1, y0, z1);
            line(c, m, r, g, b, x1, y0, z1, x0, y0, z1);
            line(c, m, r, g, b, x0, y0, z1, x0, y0, z0);
            line(c, m, r, g, b, x0, y1, z0, x1, y1, z0);
            line(c, m, r, g, b, x1, y1, z0, x1, y1, z1);
            line(c, m, r, g, b, x1, y1, z1, x0, y1, z1);
            line(c, m, r, g, b, x0, y1, z1, x0, y1, z0);
            line(c, m, r, g, b, x0, y0, z0, x0, y1, z0);
            line(c, m, r, g, b, x1, y0, z0, x1, y1, z0);
            line(c, m, r, g, b, x1, y0, z1, x1, y1, z1);
            line(c, m, r, g, b, x0, y0, z1, x0, y1, z1);
            pose.popPose();
        } catch (Throwable ignored) {
            pose.popPose();
        }
    }

    private static void line(VertexConsumer c, Matrix4f m, float r, float g, float b,
                             float x0, float y0, float z0, float x1, float y1, float z1) {
        c.addVertex(m, x0, y0, z0).setColor(r, g, b, 0.9f).setNormal(0f, 1f, 0f);
        c.addVertex(m, x1, y1, z1).setColor(r, g, b, 0.9f).setNormal(0f, 1f, 0f);
    }
}