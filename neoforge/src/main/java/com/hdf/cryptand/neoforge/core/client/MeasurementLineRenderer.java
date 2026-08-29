/**
 * ===== 测量线（探针线）渲染（2026-08-18 自管适配） =====
 *
 * 原版 PowerGrid 的测量线由 MultimeterItemRenderer.render（Create
 * SuperRenderTypeBuffer + SableCompanion + HangingWireRenderer.
 * renderFromPositions）渲染，且电流模式只认原版 useOnWire 写的 X/Y/Z 附着点
 * ——自管模式下失效（万用表/温度计存的是端点 Pos/TPos，且渲染调用方依赖
 * Create 管线）。
 *
 * 本类自实现测量线：RenderLevelStageEvent + RenderType.lines（与
 * WireOutlineRenderer/BlockedHighlightRenderer 同款，无 Create 依赖）——
 * 遍历玩家主/副手 MultimeterItem（含 Cryptand 温度计/高级万用表），读
 * ModeData 画探针线：
 *   - 电压模式(mode=0)：Pos → 青色线；Neg → 红色线（两点测量）
 *   - 电流模式(mode=1)：Pos（自管单点）优先，旧 X/Y/Z 兼容 → 红色线
 *   - 温度计(cryptand:thermometer)：TPos → 橙色线（目标方块/端子）
 * 目标位置：BlockWireEndpoint.getExactPosition（客户端端子精确位置）或
 * 方块中心。手持位置：玩家眼睛（不依赖 Create getRopeHoldPosition）。
 * 线程：客户端渲染线程。无目标/无工具时直接返回，零开销。
 */

package com.hdf.cryptand.neoforge.core.client;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.core.measurement.CryptandMeterItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;

@EventBusSubscriber(modid = Cryptand.MOD_ID, value = Dist.CLIENT)
public final class MeasurementLineRenderer {

    /** 目标点十字标记半边长（格） */
    private static final float CROSS = 0.10f;
    /** 导线纹理路径（原版 PowerGrid 铜线纹理，同 MultimeterItemRenderer.TEXTURE） */
    private static final ResourceLocation WIRE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("powergrid", "special/copper_wire");

    private MeasurementLineRenderer() {
    }

    /** 手持位置（原版用 Create getRopeHoldPosition；无 Create 时回退眼睛位置） */
    private static Vec3 ropeHoldPos(LocalPlayer player, float partialTicks) {
        try {
            return player.getRopeHoldPosition(partialTicks);
        } catch (Throwable ignored) {
            return player.getEyePosition(partialTicks);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        // ⚠ 2026-08-18 禁用自定义渲染：改由【原版 MultimeterItemRenderer】渲染探针线
        //   （grabWire 已写原版 X/Y/Z 字段；原版渲染=单根粗线，且不再双重渲染）。
        //   本方法留作 no-op（若原版某场景不渲染可在此回退启用）。
        return;
        /*
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level == null || player == null) return;
            Vec3 cam = event.getCamera().getPosition();
            PoseStack pose = event.getPoseStack();
            // 手持位置（原版 MultimeterItemRenderer 用 Create getRopeHoldPosition）
            Vec3 handPos = ropeHoldPos(player, 1.0f);
            MultiBufferSource.BufferSource buffers =
                    Minecraft.getInstance().renderBuffers().bufferSource();
            // 探针线：TRANSLUCENT 四边形（单根实心粗线，非多根细线）
            VertexConsumer consumer = buffers.getBuffer(RenderType.TRANSLUCENT);
            // 十字标记：独立 lines buffer
            VertexConsumer crossConsumer = buffers.getBuffer(RenderType.lines());
            boolean any = false;
            // 上限：取 CryptandMeterItem.maxProbeLines()（万用表2，温度计/示波器1）
            int remaining = Integer.MAX_VALUE;
            for (ItemStack stack : new ItemStack[]{
                    player.getMainHandItem(), player.getOffhandItem()}) {
                if (stack == null || stack.isEmpty()) continue;
                if (stack.getItem() instanceof CryptandMeterItem meterItem) {
                    remaining = Math.min(remaining, meterItem.maxProbeLines());
                    int drawn = 0;
                    try {
                        CompoundTag md = MultimeterItem.getModeData(stack);
                        if (md != null && !md.isEmpty()) {
                            // 温度计TPos（橙色线，上限约束）— 优先使用导线命中点
                            if (md.contains("TPos")) {
                                diag("renderer sees TPos item=" + stack.getItem()
                                        + " keys=" + md.getAllKeys());
                            }
                            if (md.contains("TPos") && drawn < remaining) {
                                Vec3 t = hitPointPos(md);
                                if (t == null) t = Vec3.atCenterOf(BlockPos.of(md.getLong("TPos")));
                                renderProbeLine(pose, consumer, crossConsumer, handPos, cam, level, t, 0xFF3F3F40, CROSS);
                                any = true; drawn++;
                            } else {
                                int mode = meterItem.getMode(stack);
                                if (mode == 0) {
                                    Vec3 p = endpointPos(level, md, "Pos");
                                    if (p != null && drawn < remaining) {
                                        renderProbeLine(pose, consumer, crossConsumer, handPos, cam, level, p, 0xFF3F3F40, CROSS);
                                        any = true; drawn++;
                                    }
                                    Vec3 n = endpointPos(level, md, "Neg");
                                    if (n != null && drawn < remaining) {
                                        renderProbeLine(pose, consumer, crossConsumer, handPos, cam, level, n, 0xFF3F3F40, CROSS);
                                        any = true; drawn++;
                                    }
                                } else if (mode == 1) {
                                    // 优先使用导线命中点（右键导线时存储的曲线世界坐标）
                                    Vec3 p = hitPointPos(md);
                                    if (p == null) p = endpointPos(level, md, "Pos");
                                    if (p == null) p = xyzPos(md);
                                    if (p != null && drawn < remaining) {
                                        renderProbeLine(pose, consumer, crossConsumer, handPos, cam, level, p, 0xFF3F3F40, CROSS);
                                        any = true; drawn++;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (any) {
                buffers.endBatch(RenderType.TRANSLUCENT);
                buffers.endBatch(RenderType.lines());
            }
        } catch (Throwable ignored) {
        }
        */
    }

    /** ModeData 端点 → 世界坐标（BlockWireEndpoint 精确端子位置；J 点存储位置）。
     *  ⚠ 2026-08-18 修复：客户端用 IElectric.getTerminalPos 替代
     *  BlockWireEndpoint.getExactPosition（后者在客户端可能返回 null → 块中心错位）。 */
    private static Vec3 endpointPos(ClientLevel level, CompoundTag md, String key) {
        if (!md.contains(key)) return null;
        try {
            IWireEndpoint ep = WireEndpointType.deserialize(md.getCompound(key));
            if (ep == null) return null;
            if (ep instanceof BlockWireEndpoint bp) {
                try {
                    // 客户端安全：IElectric.getTerminalPos 直接查方块 IElectric 接口
                    Vec3 exact = org.patryk3211.powergrid.electricity.base.IElectric
                            .getTerminalPos(level, bp.getPos(), bp.getTerminal());
                    if (exact != null) return exact;
                } catch (Throwable ignored) {
                }
                return Vec3.atCenterOf(bp.getPos());
            }
            if (ep instanceof JunctionWireEndpoint jep) {
                try {
                    return jep.getExactPosition(level);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 诊断日志节流（每 2 秒最多一条，防刷屏） */
    private static long lastDiagLog = 0;

    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastDiagLog > 2000) {
            lastDiagLog = now;
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info("[Measure] " + msg);
        }
    }

    /** 导线命中点（右键导线时存储的曲线世界坐标 HitX/HitY/HitZ） */
    private static Vec3 hitPointPos(CompoundTag md) {
        if (!md.contains("HitX")) return null;
        return new Vec3(md.getDouble("HitX"), md.getDouble("HitY"), md.getDouble("HitZ"));
    }

    /** 旧电流模式附着点（原版 useOnWire 写的 X/Y/Z） */
    private static Vec3 xyzPos(CompoundTag md) {
        if (!md.contains("X")) return null;
        return new Vec3(md.getDouble("X"), md.getDouble("Y"), md.getDouble("Z"));
    }

    /** 探针线粗度（格，半径） */
    private static final float WIRE_RADIUS = 0.045f;

    /** 画探针线（手持→目标）+ 目标点十字标记。
     *  ⚠ 2026-08-18 用【四边形粗线】（TRANSLUCENT quads，2 三角/段）——
     *  单根实心粗线，匹配原版 PowerGrid 外观（不再多根细线）。
     *  沿 QuadraticWireHelper 下坠曲线每段画一个四边形带。 */
    private static void renderProbeLine(PoseStack pose, VertexConsumer consumer,
                                        VertexConsumer crossConsumer,
                                        Vec3 handPos, Vec3 cam, ClientLevel level,
                                        Vec3 target, int color, float crossSize) {
        try {
            if (handPos.distanceToSqr(target) < 1e-8) return;

            // 1. 世界坐标下坠曲线采样（cablePoints 含起点不含终点，末尾补 target）
            float distance = (float) handPos.distanceTo(target);
            float dip = Math.min(1.2f, distance * 0.18f);
            java.util.List<Vec3> pts = QuadraticWireHelper.cablePoints(handPos, target, dip, 1.5f);
            if (pts == null || pts.isEmpty()) return;
            pts = new java.util.ArrayList<>(pts);
            pts.add(target);

            // 2. 相机空间 + 每段四边形（单根实心粗线）
            org.joml.Matrix4f m = pose.last().pose();
            java.util.List<Vec3> camPts = new java.util.ArrayList<>(pts.size());
            for (Vec3 wp : pts) camPts.add(wp.subtract(cam));
            drawThickStrip(consumer, m, camPts, WIRE_RADIUS);

            // 3. 目标点十字标记（带模式色）
            Vec3 cRel = target.subtract(cam);
            float cr = ((color >> 16) & 0xFF) / 255f;
            float cg = ((color >> 8) & 0xFF) / 255f;
            float cb = (color & 0xFF) / 255f;
            crossLine(crossConsumer, m, cRel.add(-crossSize, 0, 0), cRel.add(crossSize, 0, 0), cr, cg, cb);
            crossLine(crossConsumer, m, cRel.add(0, -crossSize, 0), cRel.add(0, crossSize, 0), cr, cg, cb);
            crossLine(crossConsumer, m, cRel.add(0, 0, -crossSize), cRel.add(0, 0, crossSize), cr, cg, cb);
        } catch (Throwable ex) {
            diag("renderProbeLine ex=" + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    /** 沿折线画一条粗四边形带（每段 2 个三角形，水平法向偏移）。
     *  黑色实心（原版铜线经深色 tint 的观感）。 */
    private static void drawThickStrip(VertexConsumer c, org.joml.Matrix4f m,
                                       java.util.List<Vec3> camPts, float radius) {
        if (camPts.size() < 2) return;
        // 深色（接近原版深灰 tint）
        float r = 0.12f, g = 0.10f, b = 0.08f, a = 0.9f;
        for (int i = 0; i < camPts.size() - 1; i++) {
            Vec3 p1 = camPts.get(i);
            Vec3 p2 = camPts.get(i + 1);
            // 段方向 → 水平法向
            Vec3 dir = p2.subtract(p1);
            double len = dir.length();
            if (len < 1e-9) continue;
            Vec3 off = new Vec3(-dir.y, dir.x, 0).normalize().scale(radius / len);
            if (Double.isNaN(off.x) || Double.isNaN(off.y)) off = new Vec3(0, 0, radius / len);
            Vec3 a1 = p1.add(off);
            Vec3 a2 = p1.subtract(off);
            Vec3 b1 = p2.add(off);
            Vec3 b2 = p2.subtract(off);
            // 三角形 1: a1, a2, b1；三角形 2: a2, b2, b1
            quadVertex(c, m, a1, r, g, b, a);
            quadVertex(c, m, a2, r, g, b, a);
            quadVertex(c, m, b1, r, g, b, a);
            quadVertex(c, m, a2, r, g, b, a);
            quadVertex(c, m, b2, r, g, b, a);
            quadVertex(c, m, b1, r, g, b, a);
        }
    }

    /** 四边形顶点（TRANSLUCENT NEW_ENTITY 格式：pos+color+uv+light+normal） */
    private static void quadVertex(VertexConsumer c, org.joml.Matrix4f m, Vec3 p,
                                   float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(r, g, b, a)
                .setUv(0f, 0f)
                .setLight(0xF000F0)
                .setNormal(0f, 1f, 0f);
    }

    /** 画一条十字标记线段（GL_LINES，两个顶点）。 */
    private static void crossLine(VertexConsumer c, org.joml.Matrix4f m,
                                   Vec3 a, Vec3 b, float r, float g, float b2) {
        c.addVertex(m, (float) a.x, (float) a.y, (float) a.z)
                .setColor(r, g, b2, 0.9f).setNormal(0f, 1f, 0f);
        c.addVertex(m, (float) b.x, (float) b.y, (float) b.z)
                .setColor(r, g, b2, 0.9f).setNormal(0f, 1f, 0f);
    }
}
