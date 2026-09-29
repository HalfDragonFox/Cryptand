/**
 * ===== 力源：官方 Sable 力组桥接（框架内置，2026-09-14） =====
 *
 * <p>把官方 `ServerSubLevel.getQueuedForceGroups()` 里每个力组记录的
 * {@code PointForce} 转成框架的 {@link ForceSample}：
 * <ul>
 *   <li><b>简化模式</b>：一个力组 → 一条 {@link ForceSample.Kind#RESULTANT}
 *       （合力 = Σ点力；中心 = Σ(p·|f|)/Σ|f| 力加权中心）</li>
 *   <li><b>详细模式</b>：一个力组 → N 条 {@link ForceSample.Kind#POINT}
 *       （每个力源一个：螺旋桨 / 悬浮方块簇 / 升力组 …）</li>
 * </ul>
 *
 * <p>注意：官方对 LIFT/DRAG 记录的是"组级加权中心 + 总力"（1 条），对
 * LEVITATION/PROPULSION 记录的是每个力源各一条 —— 因此详细模式的粒度天然就是官方粒度。
 *
 * <p>力组 id 直接取官方注册名（{@code sable:lift} 等），Cryptand 侧样式注册零映射。
 */
package com.hdf.cryptand.neoforge.sable.force.impl;

import com.hdf.cryptand.neoforge.sable.force.api.CryptandForceDisplay;
import com.hdf.cryptand.neoforge.sable.force.api.ForceContext;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSample;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Map;

public final class SableForceGroupsSource implements ForceSource {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("cryptand", "sable_force_groups");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    /** 官方力组优先（在自研力源之前跑，便于调试对照）。 */
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean enabled() {
        return ForceReflect.available();
    }

    @Override
    public void collect(final ForceContext ctx) {
        final Object mapLike = ForceReflect.queuedForceGroups(ctx.subLevel());
        if (mapLike == null) return;

        for (final Map.Entry<Object, Object> entry : ForceReflect.asMap(mapLike).entrySet()) {
            // ⚠ 颜色一致性：同一力组在【详细模式下的多条点力】必须共用同一个 id
            //   （一个力组 = 一个颜色）。注册名取不到时用"由力组名派生的稳定回退 id"，
            //   而不是跳过或每实例不同的 id。
            ResourceLocation forceId = ForceReflect.forceGroupId(entry.getKey());
            if (forceId == null) forceId = ForceReflect.forceGroupFallbackId(entry.getKey());
            if (forceId == null) continue;

            final List<Object> points = ForceReflect.recordedPointForces(entry.getValue());
            if (points.isEmpty()) continue;

            if (ctx.detailed()) {
                emitPoints(ctx, forceId, points);
            } else {
                emitResultant(ctx, forceId, points);
            }
        }
    }

    /** 详细模式：每个点力一条（世界化作用点 + 世界化力矢量）。 */
    private static void emitPoints(final ForceContext ctx, final ResourceLocation forceId,
                                   final List<Object> points) {
        for (final Object pf : points) {
            final Vector3dc p = ForceReflect.point(pf);
            final Vector3dc f = ForceReflect.force(pf);
            if (p == null || f == null) continue;
            if (f.lengthSquared() < 1.0e-8) continue;

            final double[] wp = ctx.toWorld(p.x(), p.y(), p.z());
            final double[] wf = ctx.toWorldDir(f.x(), f.y(), f.z());
            ctx.emit(forceId, ForceSample.Kind.POINT, wp[0], wp[1], wp[2], wf[0], wf[1], wf[2]);
        }
    }

    /** 简化模式：合力 + 力加权中心。 */
    private static void emitResultant(final ForceContext ctx, final ResourceLocation forceId,
                                      final List<Object> points) {
        double sx = 0.0, sy = 0.0, sz = 0.0;
        double cx = 0.0, cy = 0.0, cz = 0.0, wsum = 0.0;

        for (final Object pf : points) {
            final Vector3dc p = ForceReflect.point(pf);
            final Vector3dc f = ForceReflect.force(pf);
            if (p == null || f == null) continue;

            sx += f.x();
            sy += f.y();
            sz += f.z();

            final double w = f.length();
            if (w > 1.0e-9) {
                cx += p.x() * w;
                cy += p.y() * w;
                cz += p.z() * w;
                wsum += w;
            }
        }

        if (sx * sx + sy * sy + sz * sz < 1.0e-8) return;

        if (wsum <= 1.0e-9) {
            // 无力矩信息 → 退回质心
            final double[] com = ctx.centerOfMass();
            if (com == null) return;
            final double[] wf = ctx.toWorldDir(sx, sy, sz);
            ctx.emit(forceId, ForceSample.Kind.RESULTANT, com[0], com[1], com[2], wf[0], wf[1], wf[2]);
            return;
        }

        final double[] wc = ctx.toWorld(cx / wsum, cy / wsum, cz / wsum);
        final double[] wf = ctx.toWorldDir(sx, sy, sz);
        ctx.emit(forceId, ForceSample.Kind.RESULTANT, wc[0], wc[1], wc[2], wf[0], wf[1], wf[2]);
    }
}
