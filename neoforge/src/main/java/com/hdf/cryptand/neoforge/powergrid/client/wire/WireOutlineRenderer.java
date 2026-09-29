/**
 * ===== 选中导线选框（2026-08-17） =====
 *
 * 手持剪线钳看向导线（WireLookPicker 射线检测 → WireLookStore 命中）时，
 * 在导线【表面覆盖一层半透明白色】并【加粗边框】——导线本身不变色：
 *   - 沿二次曲线每采样点取切线 → 世界水平法线 h + 垂直法线 v
 *   - 画 5 层偏移折线：中线(0,0) + 水平 ±OFF + 垂直 ±OFF
 *   - 中线半透明白覆盖导线表面；4 条偏移线在导线四周 → 粗边框
 *   - 层与层间白色叠加 → 表面更亮、边缘更粗，形成“选中框”效果
 *
 * 渲染：RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS）+ RenderType.lines，
 * 与 BlockedHighlightRenderer 同款（纯世界线渲染，无方块交互）。
 * 数据源：WireLookStore.current() → ClientWireGraphStore 反查 ClientWire →
 * QuadraticWireHelper.cablePoints 世界坐标采样 → 减相机 → 多层折线。
 * 线程：客户端渲染线程。未命中（null）时直接返回，零开销。
 */

package com.hdf.cryptand.neoforge.powergrid.client.wire;

import com.hdf.cryptand.Cryptand;
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

import java.util.List;

public final class WireOutlineRenderer {

    /** 偏移量（格）：垂直/水平方向偏离导线中心，形成粗边框（导线粗 1/16≈0.0625） */
    private static final float OFFSET = 0.06f;
    /** 白色半透明（表面覆盖 + 多层叠加变亮） */
    private static final float ALPHA = 0.45f;

    private WireOutlineRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        try {
            WireLookStore.WireHit hit = WireLookStore.current();
            if (hit == null) return;
            ClientWireGraphStore.ClientWire wire = findWire(hit);
            if (wire == null) return;
            float sag = wire.sag() > 0 ? wire.sag() : 2f;
            // 世界坐标二次曲线采样（detail=2 足够平滑）
            List<Vec3> points = QuadraticWireHelper.cablePoints(wire.p1(), wire.p2(), sag, 2f);
            if (points.size() < 2) return;
            Vec3 cam = event.getCamera().getPosition();
            PoseStack pose = event.getPoseStack();
            MultiBufferSource.BufferSource buffers =
                    Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
            Matrix4f m = pose.last().pose();
            // 5 层：中线覆盖表面 + 水平/垂直 ±OFF 粗边框
            drawLayer(consumer, m, cam, points, 0f, 0f);
            drawLayer(consumer, m, cam, points, OFFSET, 0f);
            drawLayer(consumer, m, cam, points, -OFFSET, 0f);
            drawLayer(consumer, m, cam, points, 0f, OFFSET);
            drawLayer(consumer, m, cam, points, 0f, -OFFSET);
            buffers.endBatch(RenderType.lines());
        } catch (Throwable ignored) {
        }
    }

    /** 按端点身份反查客户端导线（命中 → 渲染参数） */
    private static ClientWireGraphStore.ClientWire findWire(WireLookStore.WireHit hit) {
        for (ClientWireGraphStore.ClientWire w : ClientWireGraphStore.wires()) {
            if (hit.matches(w)) return w;
        }
        return null;
    }

    /** 画一层偏移折线：每采样点沿切线法平面平移 (dx,dy) 后连线 */
    private static void drawLayer(VertexConsumer c, Matrix4f m, Vec3 cam,
                                  List<Vec3> pts, float dx, float dy) {
        Vec3 prev = null;
        for (int i = 0; i < pts.size(); i++) {
            Vec3 t = tangent(pts, i);
            Vec3 h = horiz(t);
            Vec3 v = vert(t, h);
            Vec3 p = pts.get(i).add(h.scale(dx)).add(v.scale(dy)).subtract(cam);
            if (prev != null) line(c, m, prev, p);
            prev = p;
        }
    }

    /** 采样点切线（相邻点差，归一化） */
    private static Vec3 tangent(List<Vec3> pts, int i) {
        Vec3 a = pts.get(Math.max(0, i - 1));
        Vec3 b = pts.get(Math.min(pts.size() - 1, i + 1));
        Vec3 t = b.subtract(a);
        double len = t.length();
        return len < 1e-6 ? new Vec3(1, 0, 0) : t.scale(1 / len);
    }

    /** 世界水平法线（绕 Y 旋转 90°；切线垂直时退化兜底） */
    private static Vec3 horiz(Vec3 t) {
        Vec3 h = new Vec3(t.z, 0, -t.x);
        double len = h.length();
        return len < 1e-6 ? new Vec3(1, 0, 0) : h.scale(1 / len);
    }

    /** 垂直法线（切线 × 水平法线） */
    private static Vec3 vert(Vec3 t, Vec3 h) {
        Vec3 v = t.cross(h);
        double len = v.length();
        return len < 1e-6 ? new Vec3(0, 1, 0) : v.scale(1 / len);
    }

    /** 单段折线（半透明白色） */
    private static void line(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b) {
        c.addVertex(m, (float) a.x, (float) a.y, (float) a.z)
                .setColor(1f, 1f, 1f, ALPHA).setNormal(0f, 1f, 0f);
        c.addVertex(m, (float) b.x, (float) b.y, (float) b.z)
                .setColor(1f, 1f, 1f, ALPHA).setNormal(0f, 1f, 0f);
    }
}