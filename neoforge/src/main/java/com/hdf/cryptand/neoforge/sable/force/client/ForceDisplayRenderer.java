/**
 * ===== 力显示渲染器（客户端，2026-09-14） =====
 *
 * <p>KSP 风格：每个力 一个【球】（力中心）+ 一支【箭头】（力矢量）。
 * 简化模式数据由服务端聚合好（每力组一条 RESULTANT）；
 * 详细模式由服务端按力源下发（每力源一条 POINT，如螺旋桨一个小球）。
 *
 * <p>渲染挂点与 {@code SableScopeBoxDebugRenderer} 相同
 * （RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS + RenderType.lines）。
 * 显示门控：总开关 + 显示意图（F3+B / 强制）+ 服务端已确认支持且有数据。
 *
 * <p>箭头长度：对数标定（log10(1+|F|) × 1.2 × 配置缩放，clamp 到 0.15~6 格），
 * 避免浮力/推力量级差异导致箭头长度失控。
 */
package com.hdf.cryptand.neoforge.sable.force.client;

import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import com.hdf.cryptand.neoforge.sable.force.api.CryptandForceDisplay;
import com.hdf.cryptand.neoforge.sable.force.api.ForceStyle;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

public final class ForceDisplayRenderer {

    /** 球的分段（每轴）。 */
    private static final int SPHERE_SEGMENTS = 10;

    /**
     * 【穿透渲染】力显示专用线框 RenderType —— 关掉深度测试（NO_DEPTH_TEST），
     * 球与箭头被方块/载具挡住时依然可见（用户要求："渲染能穿透方块"）。
     *
     * <p>与 {@code RenderType.lines()} 的唯一区别 = 无深度测试 + COLOR_WRITE（不写深度，
     * 避免污染后续帧的深度缓冲）。因此绘制阶段仍放在 AFTER_TRANSLUCENT_BLOCKS（半透明之后），
     * endBatch 立即 flush → 视觉上永远压在最上层。
     */
    private static final RenderType FORCE_LINES = RenderType.create(
            "cryptand_force_lines",
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new net.minecraft.client.renderer.RenderStateShard.LineStateShard(java.util.OptionalDouble.of(2.5D)))
                    .setLayeringState(net.minecraft.client.renderer.RenderStateShard.NO_LAYERING)
                    .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
                    .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                    .setDepthTestState(net.minecraft.client.renderer.RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false));

    /** 渲染统计（诊断：每 100 帧打印一次球/箭头数）。 */
    private static int frameCounter = 0;
    private static int lastSpheres = 0;
    private static int lastArrows = 0;
    private static int lastStructures = 0;

    private ForceDisplayRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.renderBuffers() == null) return;
            if (!ConfigSable.ENABLE_SABLE_FORCE_DISPLAY.get()) return;
            if (!ForceDisplayRequester.wantsDisplay(mc)) return;

            final ForceDisplayStore store = ForceDisplayStore.INSTANCE;
            if (store.capability() != ForceDisplayStore.Capability.SUPPORTED || !store.hasData()) return;

            final PoseStack pose = event.getPoseStack();
            final Vec3 cam = event.getCamera().getPosition();
            final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            final VertexConsumer lines = buffers.getBuffer(FORCE_LINES);
            final double arrowScale = ConfigSable.SABLE_FORCE_DISPLAY_ARROW_SCALE.get();

            int spheres = 0;
            int arrows = 0;
            for (final Map.Entry<Integer, List<ForceDisplayStore.Sample>> e : store.frames().entrySet()) {
                for (final ForceDisplayStore.Sample s : e.getValue()) {
                    final ForceStyle style = CryptandForceDisplay.styleOf(s.id);
                    final float r = ((style.color() >> 16) & 0xFF) / 255.0f;
                    final float g = ((style.color() >> 8) & 0xFF) / 255.0f;
                    final float b = (style.color() & 0xFF) / 255.0f;

                    // ① 球（该力的中心；质心样本力为 0 → 只画球）
                    sphere(lines, pose, cam, s.cx, s.cy, s.cz, style.sphereRadius(), r, g, b, 0.9f);
                    spheres++;

                    // ② 箭头（力矢量）
                    final double mag = s.magnitude();
                    if (mag > 1.0e-6) {
                        double len = Math.log10(1.0 + mag) * 1.2 * arrowScale * style.arrowScale();
                        if (len < 0.15) len = 0.15;
                        if (len > 6.0) len = 6.0;
                        arrow(lines, pose, cam, s.cx, s.cy, s.cz, s.fx, s.fy, s.fz, len, r, g, b, 0.95f);
                        arrows++;
                    }
                }
            }

            buffers.endBatch(FORCE_LINES);

            lastSpheres = spheres;
            lastArrows = arrows;
            lastStructures = store.frames().size();
            if (ConfigSable.SABLE_FORCE_DISPLAY_DEBUG.get() && (++frameCounter % 100 == 0)) {
                System.out.println("[ForceDbg] render structures=" + lastStructures
                        + " spheres=" + lastSpheres + " arrows=" + lastArrows
                        + " samples=" + store.frames().values().stream().mapToInt(List::size).sum());
            }
        } catch (final Throwable ignored) {
            // 渲染期异常绝不冒泡（与既有调试渲染器同策略）
        }
    }

    // ===== 几何 =====

    /** 线框球（三个正交圆环）。 */
    private static void sphere(final VertexConsumer c, final PoseStack pose, final Vec3 cam,
                               final double x, final double y, final double z,
                               final float radius, final float r, final float g, final float b, final float a) {
        if (radius <= 0.0f) return;
        pose.pushPose();
        pose.translate(x - cam.x, y - cam.y, z - cam.z);
        final Matrix4f m = pose.last().pose();
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < SPHERE_SEGMENTS; i++) {
                final double t0 = (i * 2.0 * Math.PI) / SPHERE_SEGMENTS;
                final double t1 = ((i + 1) * 2.0 * Math.PI) / SPHERE_SEGMENTS;
                final float[] p0 = ringPoint(axis, t0, radius);
                final float[] p1 = ringPoint(axis, t1, radius);
                line(c, m, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], r, g, b, a);
            }
        }
        pose.popPose();
    }

    private static float[] ringPoint(final int axis, final double t, final float radius) {
        final float cs = (float) (Math.cos(t) * radius);
        final float sn = (float) (Math.sin(t) * radius);
        return switch (axis) {
            case 0 -> new float[]{0.0f, cs, sn};   // YZ 圆
            case 1 -> new float[]{cs, 0.0f, sn};   // XZ 圆
            default -> new float[]{cs, sn, 0.0f};  // XY 圆
        };
    }

    /** KSP 风格箭头：主线 + 4 条头部斜线。 */
    private static void arrow(final VertexConsumer c, final PoseStack pose, final Vec3 cam,
                              final double x, final double y, final double z,
                              final double fx, final double fy, final double fz,
                              final double len, final float r, final float g, final float b, final float a) {
        final double mag = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (mag < 1.0e-9) return;
        final double dx = fx / mag, dy = fy / mag, dz = fz / mag;

        // 与 dir 垂直的两个基向量
        final double[] up = Math.abs(dy) > 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] side = cross(dx, dy, dz, up[0], up[1], up[2]);
        final double sl = Math.sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2]);
        if (sl < 1.0e-9) return;
        side = new double[]{side[0] / sl, side[1] / sl, side[2] / sl};
        final double[] up2 = cross(side[0], side[1], side[2], dx, dy, dz);

        final float ex = (float) (dx * len), ey = (float) (dy * len), ez = (float) (dz * len);
        final float headBack = (float) (len * 0.28);
        final float headWide = (float) (len * 0.12);

        pose.pushPose();
        pose.translate(x - cam.x, y - cam.y, z - cam.z);
        final Matrix4f m = pose.last().pose();

        // 主线
        line(c, m, 0.0f, 0.0f, 0.0f, ex, ey, ez, r, g, b, a);

        // 头部（末端回退 + 垂直偏移）
        for (int i = 0; i < 4; i++) {
            final double sign = (i < 2) ? 1.0 : -1.0;
            final double[] perp = (i % 2 == 0) ? side : up2;
            final float hx = (float) (ex - dx * headBack + perp[0] * headWide * sign);
            final float hy = (float) (ey - dy * headBack + perp[1] * headWide * sign);
            final float hz = (float) (ez - dz * headBack + perp[2] * headWide * sign);
            line(c, m, ex, ey, ez, hx, hy, hz, r, g, b, a);
        }
        pose.popPose();
    }

    private static double[] cross(final double ax, final double ay, final double az,
                                  final double bx, final double by, final double bz) {
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    /** RenderType.lines() 的 vertex format 含 Normal → 必须 setNormal。 */
    private static void line(final VertexConsumer consumer, final Matrix4f m,
                             final float x0, final float y0, final float z0,
                             final float x1, final float y1, final float z1,
                             final float r, final float g, final float b, final float a) {
        consumer.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
        consumer.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
    }
}
