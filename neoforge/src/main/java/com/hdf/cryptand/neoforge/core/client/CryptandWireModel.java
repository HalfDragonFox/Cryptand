/**
 * ===== 单位线段模型（Flywheel，2026-08-13 CEE 方式导线渲染） =====
 *
 * 用 Flywheel LineModelBuilder 构建【单位长度线段模型】（沿 Z 轴 0..1），
 * 所有导线共享同一 Instancer（高效 instancing）。每段导线由
 * TransformedInstance 定位：translate(点) + 旋转朝向 + scaleZ(长度)。
 * <p>
 * 这是 CEE WireRenderer 的 WIRE_SEGMENT PartialModel 方式的 Flywheel 原生替代
 * （不依赖 Create 的 CachedBuffers/PartialModel——slim jar 无 foundation）。
 */
package com.hdf.cryptand.neoforge.core.client;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.LineModelBuilder;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CryptandWireModel {

    private static Model UNIT_SEGMENT;
    /** 按导线贴图缓存的 3D 段模型（不同导线类型不同贴图） */
    private static final Map<ResourceLocation, Model> SEGMENTS = new ConcurrentHashMap<>();
    /** 默认贴图（无导线贴图时）：纯白，染成导线色 */
    private static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");

    private CryptandWireModel() {}

    /** 单位线段模型（沿 Z 轴 0→1，宽由 LineModelBuilder 顶点着色器处理）。 */
    public static Model unitSegment() {
        Model m = UNIT_SEGMENT;
        if (m == null) {
            LineModelBuilder builder = new LineModelBuilder(1);
            builder.line(0, 0, 0, 0, 0, 1);
            m = builder.build();
            UNIT_SEGMENT = m;
        }
        return m;
    }

    /**
     * 3D 方柱段模型（2026-08-14 原版材质 + CEE 居中段）：
     *   - 几何：白色混凝土（纯白，无方块纹理）+ BakedModelBuilder，PoseStack
     *     预缩放截面 1/16（= PowerGrid 原版导线粗细）并【居中】（Z -0.5..0.5）。
     *   - 材质：SimpleMaterial 绑定【原版导线贴图】（WireItemEntry.texture，
     *     powergrid:textures/special/*.png）；顶点色叠加导线颜色 → 原版导线外观。
     *   - 居中段：实例 translate(中点) + scaleZ(间距*2+0.02) → 段向两端各溢出
     *     0.01 与相邻段重叠 → 消除折角缝隙（CEE WIRE_SEGMENT 语义）。
     * 按贴图缓存（不同导线类型各自实例化）。构建失败回退单位线段。
     */
    public static Model segment(ResourceLocation texture) {
        ResourceLocation tex = texture == null ? DEFAULT_TEXTURE : texture;
        return SEGMENTS.computeIfAbsent(tex, CryptandWireModel::buildSegment);
    }

    private static Model buildSegment(ResourceLocation texture) {
        try {
            var white = Minecraft.getInstance().getModelManager().getBlockModelShaper()
                    .getBlockModel(Blocks.WHITE_CONCRETE.defaultBlockState());
            com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
            pose.scale(0.0625f, 0.0625f, 1f);            // 截面 1/16（原版粗细），Z 0..1
            SimpleMaterial mat = SimpleMaterial.builderOf(Materials.SOLID_BLOCK)
                    .texture(texture)
                    .build();
            return new BakedModelBuilder(white)
                    .poseStack(pose)
                    .materialFunc((rt, b) -> mat)
                    .build();
        } catch (Throwable t) {
            return unitSegment();
        }
    }

    // ===== 整条导线【管状网格】一步到位（2026-08-14） =====

    /**
     * 构建整条导线的连续 3D 管状网格（SimpleQuadMesh + SingleMeshModel）：
     *   沿采样点扫掠【方柱截面】，每段 4 个侧面 quad，首尾端面封口——
     *   一个连续网格 = 整条导线，无分段缝隙/折角鼓包。
     *   每采样点独立宽度 widths[i]（动态线宽/LOD）。
     * ⚠ 顶点为 renderOrigin 局部坐标（单 TransformedInstance identity 渲染）；
     *   native 内存用 MemoryBlock.mallocTracked（Flywheel 管理生命周期）。
     *   双面渲染（backfaceCulling=false）规避 winding 方向问题。
     */
    public static Model buildTubeMesh(List<Vec3> localPoints, float[] widths,
                                      ResourceLocation texture) {
        try {
            int segs = Math.max(localPoints.size() - 1, 0);
            if (segs <= 0) return unitSegment();
            if (widths == null || widths.length < localPoints.size()) return unitSegment();
            int vertexCount = segs * 4 * 4 + 2 * 4; // 侧面 + 两端面
            FullVertexView view = new FullVertexView();
            MemoryBlock block = MemoryBlock.mallocTracked((long) vertexCount * view.stride());
            view.ptr(block.ptr());
            view.vertexCount(vertexCount);
            int vi = 0;
            // 沿导线 UV 累计曲线长度：每 1 格平铺一次纹理（原版语义），
            // 避免段长无关的 0..1 映射导致长段纹理被拉伸
            float vAlong = 0f;
            for (int s = 0; s < segs; s++) {
                Vec3 p0 = localPoints.get(s);
                Vec3 p1 = localPoints.get(s + 1);
                Vec3 dir = p1.subtract(p0);
                double len = dir.length();
                if (len < 1e-4) continue;
                dir = dir.normalize();
                float vStart = vAlong;
                vAlong += (float) len;
                Vec3[] r0 = ring(p0, dir, widths[s]);
                Vec3[] r1 = ring(p1, dir, widths[s + 1]);
                Vec3[] norms = sideNormals(p0, r0);
                for (int j = 0; j < 4; j++) {
                    int j2 = (j + 1) % 4;
                    // 绕圆周 U：j/4..(j+1)/4（整圈 0..1 包一次纹理）；
                    // 沿导线 V：vStart..vAlong（每格平铺一次）
                    quad(view, vi, r0[j], r1[j], r1[j2], r0[j2], norms[j],
                            j / 4f, (j + 1) / 4f, vStart, vAlong);
                    vi += 4;
                }
            }
            // 端面封口（法线 ±dir；双面渲染 winding 不敏感）
            if (segs >= 1) {
                Vec3 dir0 = localPoints.get(1).subtract(localPoints.get(0)).normalize();
                Vec3[] rStart = ring(localPoints.get(0), dir0, widths[0]);
                quad(view, vi, rStart[0], rStart[3], rStart[2], rStart[1],
                        dir0.scale(-1), 0f, 1f, 0f, 1f);
                vi += 4;
                Vec3 dirN = localPoints.get(localPoints.size() - 1)
                        .subtract(localPoints.get(localPoints.size() - 2)).normalize();
                Vec3[] rEnd = ring(localPoints.get(localPoints.size() - 1), dirN,
                        widths[widths.length - 1]);
                quad(view, vi, rEnd[0], rEnd[1], rEnd[2], rEnd[3], dirN, 0f, 1f, 0f, 1f);
                vi += 4;
            }
            SimpleQuadMesh mesh = new SimpleQuadMesh(view, "cryptand_wire_tube");
            SimpleMaterial mat = SimpleMaterial.builderOf(Materials.SOLID_BLOCK)
                    .texture(texture == null ? DEFAULT_TEXTURE : texture)
                    .backfaceCulling(false)
                    .build();
            return new SingleMeshModel(mesh, mat);
        } catch (Throwable t) {
            return unitSegment();
        }
    }

    /** 方柱截面环（4 角，绕 dir，宽 w）：返回 [p±right±fwd] 4 点 */
    private static Vec3[] ring(Vec3 p, Vec3 dir, float w) {
        Vec3 up = Math.abs(dir.y) < 0.99f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = up.cross(dir).normalize();
        Vec3 fwd = dir.cross(right).normalize();
        float h = w / 2f;
        return new Vec3[]{
                p.add(right.scale(h)).add(fwd.scale(h)),
                p.subtract(right.scale(h)).add(fwd.scale(h)),
                p.subtract(right.scale(h)).subtract(fwd.scale(h)),
                p.add(right.scale(h)).subtract(fwd.scale(h))
        };
    }

    /** 4 侧面法线（面中心 - 截面中心 → 朝外） */
    private static Vec3[] sideNormals(Vec3 center, Vec3[] ring) {
        Vec3[] n = new Vec3[4];
        for (int j = 0; j < 4; j++) {
            Vec3 a = ring[j];
            Vec3 b = ring[(j + 1) % 4];
            Vec3 faceCenter = a.add(b).scale(0.5);
            Vec3 v = faceCenter.subtract(center);
            n[j] = v.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : v.normalize();
        }
        return n;
    }

    /** 填充一个 quad（4 顶点：pos + normal + uv） */
    private static void quad(FullVertexView v, int i, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             Vec3 n, float u0, float u1, float v0, float v1) {
        put(v, i, a, n, u0, v0);
        put(v, i + 1, b, n, u1, v0);
        put(v, i + 2, c, n, u1, v1);
        put(v, i + 3, d, n, u0, v1);
    }

    private static void put(FullVertexView v, int i, Vec3 p, Vec3 n, float u, float t) {
        v.x(i, (float) p.x);
        v.y(i, (float) p.y);
        v.z(i, (float) p.z);
        v.normalX(i, (float) n.x);
        v.normalY(i, (float) n.y);
        v.normalZ(i, (float) n.z);
        v.u(i, u);
        v.v(i, t);
        // ⚠ 顶点色/光照必须为白/FULL_BRIGHT——否则与实例色相乘 → 全黑
        v.r(i, 1f);
        v.g(i, 1f);
        v.b(i, 1f);
        v.a(i, 1f);
        v.light(i, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
        v.overlay(i, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }
}
