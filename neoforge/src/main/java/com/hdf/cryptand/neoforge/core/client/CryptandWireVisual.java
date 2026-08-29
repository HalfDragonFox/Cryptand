/**
 * ===== 导线 Flywheel 视觉（2026-08-14 管状网格一步到位 + LOD） =====
 *
 * 整条导线【一个连续 3D 管状网格】（CryptandWireModel.buildTubeMesh）——
 * 沿二次曲线采样点扫掠方柱截面，单 SimpleModel 渲染：无分段缝隙、无折角鼓包。
 * 仍保留 CEE 完整 LOD 机制：
 *   - 【LOD 降采样】cablePoints(pos1,pos2,dip,cameraWorld)：按相机最近距离
 *     动态选 detail 档位；相机移动超阈值（8 格）才重建网格（防每帧重建）。
 *   - 【动态线宽】每采样点宽度 clamp(1.0/(camDist/30), 0.4, 1.0) × 0.0625
 *     （原版 1/16 粗细）——远处线变细。
 *   - 【渲染距离】任一端超 512 → 隐藏实例。
 *   - 【区块加载】端点区块未加载 → 隐藏实例。
 *   - 【原版材质】SimpleMaterial 绑定原版导线贴图 + 顶点色。
 *
 * 数据：CryptandWireEffect（pos1/pos2/dip/color/texture），端点世界坐标固定。
 * 线程：客户端主线程（tick）。每导线独立网格/实例（导线数量有限，可接受）。
 */
package com.hdf.cryptand.neoforge.core.client;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class CryptandWireVisual implements EffectVisual<CryptandWireEffect>, SimpleTickableVisual {

    /** 渲染距离（CEE wireRenderDistance 默认 512）：导线任一端超此距 → 隐藏 */
    private static final double RENDER_DISTANCE = 512;
    /** 相机移动超该距离（格）才重建网格（LOD 档位变化检测，防每帧重建） */
    private static final double RECREATE_DISTANCE = 8;
    /** 动态线宽（CEE：clamp(baseWidth/(dist/30), 0.4, baseWidth)，baseWidth=1.0） */
    private static final float BASE_THICKNESS = 1.0f;
    private static final float MIN_THICKNESS = 0.4f;
    /** 原版导线粗细（1/16 格） */
    private static final float BASE_WIDTH = 0.0625f;

    private final VisualizationContext ctx;
    private final CryptandWireEffect effect;
    /** 当前管状网格模型 + 单实例（一步到位） */
    private Model tubeModel;
    private TransformedInstance instance;
    private Vec3 lastCameraPos;
    private boolean chunksLoaded = true;
    private boolean tooFar = false;
    /** 当前渲染端点（世界坐标；每 tick 按身份现算覆盖——物理化跟随） */
    private Vec3 curPos1;
    private Vec3 curPos2;
    /** 上次重建网格时的端点（变化才重建，防每 tick 全量重建网格） */
    private Vec3[] lastEndpointPos;

    public CryptandWireVisual(VisualizationContext ctx, CryptandWireEffect effect) {
        this.ctx = ctx;
        this.effect = effect;
        this.curPos1 = effect.pos1;
        this.curPos2 = effect.pos2;
        Vec3 cam = cameraPos();
        lastCameraPos = cam;
        recreate(cam);
    }

    /** 按身份现算端点真实世界坐标（2026-08-23 物理化跟随：亚层 plot 坐标经
     *  Sable last/logical pose【partialTicks 插值】投影——与物理化结构方块渲染
     *  同步，移动中平滑跟随不跳变；非亚层 = 快照位置）。失败保留当前值。
     *  ⚠ 每帧调用（tick 即渲染帧）。 */
    private void refreshEndpointPositions(float partialTicks) {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return;
            if (effect.aTerm >= 0) {
                Vec3 p1 = resolveTerminal(level, effect.ax, effect.ay, effect.az,
                        effect.aTerm, curPos1, partialTicks);
                if (p1 != null) curPos1 = p1;
            }
            if (effect.bTerm >= 0) {
                Vec3 p2 = resolveTerminal(level, effect.bx, effect.by, effect.bz,
                        effect.bTerm, curPos2, partialTicks);
                if (p2 != null) curPos2 = p2;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 端子真实世界位置（客户端渲染专用：局部坐标 + toWorldInterp 插值投影，
     *  每帧跟随物理化结构；CEE 节点映射 / PowerGrid 精确端点 / 注册表偏移）。
     *  失败回退给定值。 */
    private static Vec3 resolveTerminal(ClientLevel level, int x, int y, int z,
                                        int term, Vec3 fallback, float partialTicks) {
        try {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
            Vec3 local = com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals
                    .terminalLocalWorld(level, pos, st, term);
            if (local == null) return fallback;
            Vec3 wp = com.hdf.cryptand.neoforge.cee.CeePoseUtil.toWorldInterp(
                    level, pos, local, partialTicks);
            return wp != null ? wp : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Vec3 cameraPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /** 重建整条导线管状网格（cameraWorld 世界坐标）：LOD 降采样 + 动态线宽。 */
    private void recreate(Vec3 cameraWorld) {
        deleteInstances();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 renderOrigin = new Vec3(ctx.renderOrigin().getX(),
                ctx.renderOrigin().getY(), ctx.renderOrigin().getZ());

        // LOD 降采样（CEE）：世界坐标端点 + 世界坐标相机 → detail 档位
        List<Vec3> worldPoints = QuadraticWireHelper.cablePoints(
                curPos1, curPos2, effect.dip, cameraWorld);
        if (worldPoints.size() < 2) return;
        // 世界采样点 → 局部坐标；末尾补终点
        List<Vec3> points = new ArrayList<>(worldPoints.size() + 1);
        for (Vec3 p : worldPoints) points.add(p.subtract(renderOrigin));
        points.add(curPos2.subtract(renderOrigin));

        // 每采样点动态线宽（CEE：按世界坐标点与相机距离）
        float[] widths = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            Vec3 wp = points.get(i).add(renderOrigin);
            float camDist = (float) wp.distanceTo(cameraWorld);
            float factor = (float) Mth.clamp(
                    BASE_THICKNESS / Math.max(0.001f, camDist / 30f),
                    MIN_THICKNESS, BASE_THICKNESS);
            widths[i] = factor * BASE_WIDTH;
        }

        net.minecraft.resources.ResourceLocation tex =
                (effect.texture == null || effect.texture.isEmpty())
                        ? null : net.minecraft.resources.ResourceLocation.tryParse(effect.texture);
        tubeModel = CryptandWireModel.buildTubeMesh(points, widths, tex);

        // 单实例：identity（模型顶点已为局部坐标），颜色/光照实例级
        var instancer = ctx.instancerProvider()
                .instancer((InstanceType<TransformedInstance>) InstanceTypes.TRANSFORMED,
                        tubeModel);
        int r = (effect.color >> 16) & 0xFF;
        int g = (effect.color >> 8) & 0xFF;
        int b = effect.color & 0xFF;
        instance = instancer.createInstance();
        instance.setIdentityTransform()
                .color(r, g, b)
                .light((int) (net.minecraft.client.renderer.LightTexture.FULL_BRIGHT))
                .setChanged();
        // 诊断（节流）：整线网格实例就绪
        if (++diagSeg % 60 == 0) {
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[WireVisual] tube recreated points={} dip={} color={:08X}",
                        points.size(), effect.dip, effect.color);
            } catch (Throwable ignored) {
            }
        }
    }

    private static int diagSeg;

    @Override
    public void tick(Context context) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 cam = cameraPos();

        // 2026-08-23 物理化跟随：每帧按端点身份现算真实世界位置
        //（last/logical pose + partialTicks 插值），物理化结构移动 → 导线同步跟随。
        float pt = net.createmod.catnip.animation.AnimationTickHolder
                .getPartialTicks();
        refreshEndpointPositions(pt);

        // 区块加载检查（CEE isFullyLoaded 语义）：端点区块未加载 → 隐藏
        boolean loaded = level.isLoaded(BlockPos.containing(curPos1))
                && level.isLoaded(BlockPos.containing(curPos2));
        if (loaded != chunksLoaded) {
            chunksLoaded = loaded;
            setVisible(loaded && !tooFar);
        }
        // 渲染距离检查（CEE wireRenderDistance）：任一端超距 → 隐藏
        double distSqr = Math.min(curPos1.distanceToSqr(cam), curPos2.distanceToSqr(cam));
        boolean far = distSqr > RENDER_DISTANCE * RENDER_DISTANCE;
        if (far != tooFar) {
            tooFar = far;
            setVisible(!far && chunksLoaded);
        }
        if (tooFar || !chunksLoaded) return;

        // LOD 重建：相机移动超阈值（档位变化才重建网格，防每帧重建）
        if (lastCameraPos == null
                || lastCameraPos.distanceToSqr(cam) > RECREATE_DISTANCE * RECREATE_DISTANCE) {
            lastCameraPos = cam;
            recreate(cam);
            return;
        }

        // 2026-08-23 端点位置变化（物理化装置移动）→ 重建网格（跟随）
        if (lastEndpointPos == null
                || lastEndpointPos[0].distanceToSqr(curPos1) > 1e-4
                || lastEndpointPos[1].distanceToSqr(curPos2) > 1e-4) {
            lastEndpointPos = new Vec3[]{curPos1, curPos2};
            recreate(cam);
            return;
        }

        // 光照刷新（CEE maxLightLevel 语义；简化取中点）
        if (instance == null) return;
        Vec3 mid = curPos1.add(curPos2).scale(0.5);
        int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(
                level, BlockPos.containing(mid));
        instance.light(light).setChanged();
    }

    private void setVisible(boolean visible) {
        if (instance != null) {
            instance.setVisible(visible);
        }
    }

    private void deleteInstances() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
        tubeModel = null; // 旧网格交 Flywheel 引用回收（mallocTracked 托管）
    }

    @Override
    public void update(float partialTick) {
        // 无动态动画（端点固定）→ 空实现
    }

    @Override
    public void delete() {
        deleteInstances();
    }
}
