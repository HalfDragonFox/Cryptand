/**
 * ===== 亚层物理化结构渲染器（客户端，官方单块渲染算法） =====
 *
 * 参考官方 VanillaSingleSubLevelRenderData.renderSingleBlock（已验证可见）：
 *  - 亚层方块按【锚点位姿】逐块绘制（renderCOR 官方算法：旋转点-方块位置 先旋再平移）。
 *  - 光照：SingleBlockSubLevelWrapper 同款（getBrightness 主世界采光 + getShade 主世界
 *    方向 shade）；tesselateWithoutAO（禁 AO——否则邻居 AIR → 侧面黑）。
 *  - 位姿来自核心位姿快照（SableClientRenderModule.pose）；方块数据来自客户端镜像
 *    （SableSubLevelRenderData.blocks，payload 编入）。
 *
 * 注意：本渲染器是【唯一渲染入口】（不再用自建 RenderSection/OFFSET 域——那是
 * 引入"消失/主世界透明"的根源）。碰撞/射线/破坏各自经 SableSubLevelProjection 投射。
 */
package com.hdf.cryptand.neoforge.cryptandsable.client.render;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.api.SableSubLevelProjection;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;

public final class SableSubLevelWorldRenderer {

    private SableSubLevelWorldRenderer() {
    }

    /** 复用实例（懒初始化：首次渲染时创建；避免服务端类加载时 Minecraft.getInstance() NPE）。 */
    private static ModelBlockRenderer modelRenderer;

    /** 亚层光照包装（每次渲染 setup；邻居 AIR + 全局位置采光）。 */
    private static final SableSubLevelBlockAndTintGetter TINT_GETTER = new SableSubLevelBlockAndTintGetter();

    private static ModelBlockRenderer modelRenderer() {
        if (modelRenderer == null) {
            modelRenderer = new ModelBlockRenderer(Minecraft.getInstance().getBlockColors());
        }
        return modelRenderer;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        // ★ 2026-09-06 【core 总闸】核心关闭 → 渲染器零行为（类经 @EventBusSubscriber
        //   扫描加载不可避免，但静态面无任何 CryptandSable 业务引用，方法体直接返回；
        //   服务端不注册 payload → 也无数据可渲染/请求）
        if (!ConfigCryptandSable
                .ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        // 独立 MultiBufferSource（不与 MC 主批次混用 → 不受 MC 渲染状态影响）
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final Vec3 cam = event.getCamera().getPosition();

        // ★ 2026-09-01 pull 渲染：渲染线程每帧请求位姿（客户端限流；服务端回复）
        if (mc.level != null && mc.getConnection() != null) {
            SableClientRenderModule.INSTANCE.requestPosesFromServer();
        }

        // 一次性探针：确认渲染器真的被调用 + 缓存有亚层
        if (RENDER_PROBED.compareAndSet(false, true)) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] SableSubLevelWorldRenderer.onRenderLevel invoked (subLevels="
                            + SableClientRenderModule.INSTANCE.subLevelCount() + ")");
        }

        // ★ 2026-09-01 渲染插值（不走 tick）：渲染线程每帧按 partialTick 在 prev→curr
        //   位姿间插值（位置 lerp + 四元数 slerp）→ 渲染帧率（60FPS），不卡 tick（20Hz）。
        //   位姿快照更新来自主线程 tick 收包（网络），但渲染读缓存+插值，无跳帧。
        final float partialTick = event.getPartialTick()
                .getGameTimeDeltaPartialTick(false);
        for (SableSubLevelRenderData data : SableClientRenderModule.INSTANCE.allSubLevels()) {
            if (data.blocks().isEmpty()) continue;

            SableMessages.PoseSnapshot ps = SableClientRenderModule.INSTANCE
                    .interpPose(data.runtimeId(), partialTick);
            double px, py, pz;
            double qx = 0, qy = 0, qz = 0, qw = 1;
            if (ps != null) {
                px = ps.px(); py = ps.py(); pz = ps.pz();
                qx = ps.qx(); qy = ps.qy(); qz = ps.qz(); qw = ps.qw();
            } else {
                px = data.anchor().getX() + 0.5; py = data.anchor().getY() + 0.5; pz = data.anchor().getZ() + 0.5;
            }

            renderSubLevel(data, px, py, pz, qx, qy, qz, qw, event.getPoseStack(), buffers, cam);
        }
        buffers.endBatch(RenderType.solid());

        // ★ F3+B 亚层碰撞箱线框（2026-09-01：对照官方 DebugRendererMixin）。
        //   亚层方块不在主世界区块（注入渲染）→ DebugRenderer 永不显示碰撞框；
        //   这里在 F3+B 开启时自行绘制（SableClientRenderModule 数据 + 位姿投影）。
        if (SHOW_COLLISION_BOXES.get()) {
            renderCollisionBoxes(event, buffers, cam);
        }
    }

    /** F3+B 亚层碰撞箱线框开关（由 KeyboardDebugMixin 切换，客户端）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SHOW_COLLISION_BOXES =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 设置 F3+B 开关（KeyboardDebugMixin 调用）。 */
    public static void setShowCollisionBoxes(final boolean show) {
        SHOW_COLLISION_BOXES.set(show);
    }

    /** 查询 F3+B 亚层碰撞线框开关（KeyboardDebugMixin toggle 用）。 */
    public static boolean isShowCollisionBoxes() {
        return SHOW_COLLISION_BOXES.get();
    }

    /** 绘制亚层方块碰撞箱线框（F3+B）：遍历渲染数据，按物理位姿投影后 DebugRenderer.renderFilledBox。 */
    private static void renderCollisionBoxes(final RenderLevelStageEvent event,
                                             final MultiBufferSource.BufferSource buffers, final Vec3 cam) {
        try {
            final net.minecraft.world.phys.Vec3 cameraPos = cam;
            final PoseStack pose = event.getPoseStack();
            final net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return;

            for (SableSubLevelRenderData data : SableClientRenderModule.INSTANCE.allSubLevels()) {
                if (data == null || data.blocks() == null || data.blocks().isEmpty()) continue;
                final SableMessages.PoseSnapshot ps = SableClientRenderModule.INSTANCE.pose(data.runtimeId());
                final BlockPos anchor = data.anchor();
                for (SableSubLevelRenderData.RenderBlock rb : data.blocks()) {
                    final int sid = rb.stateId();
                    if (sid <= 0) continue;
                    final BlockState state = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(sid);
                    if (state == null || state.isAir()) continue;
                    final BlockPos initPos = new BlockPos(anchor.getX() + rb.dx(), anchor.getY() + rb.dy(), anchor.getZ() + rb.dz());
                    final BlockPos proj = SableSubLevelProjection
                            .projectBlock(ps, anchor, initPos);
                    if (proj == null) continue;
                    // 世界 AABB（含形状，非仅单位立方体，便于看清实际碰撞面）
                    final AABB box = state.getCollisionShape(level, proj).bounds()
                            .move(proj.getX(), proj.getY(), proj.getZ());
                    net.minecraft.client.renderer.debug.DebugRenderer.renderFilledBox(
                            pose, buffers, box, 1.0f, 0.0f, 0.0f, 0.9f);
                }
            }
            buffers.endBatch(); // flush 全部（renderFilledBox 内部用 debugFilledBox buffer）
        } catch (final Throwable t) {
            // 线框渲染失败不影响主渲染
        }
    }

    /** 一次性渲染器探针（确认渲染器被调用；高频渲染→只打印首次）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RENDER_PROBED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 渲染单个亚层：遍历方块，按位姿变换绘制（官方 renderSingleBlock 算法）。 */
    private static void renderSubLevel(SableSubLevelRenderData data,
                                       double px, double py, double pz,
                                       double qx, double qy, double qz, double qw,
                                       PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam) {
        final Minecraft mc = Minecraft.getInstance();

        final Quaterniond rot = new Quaterniond(qx, qy, qz, qw).normalize();

        for (SableSubLevelRenderData.RenderBlock block : data.blocks()) {
            if (block.stateId() <= 0) continue;
            BlockState state = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(block.stateId());
            if (state == null || state.isAir()) continue;
            if (state.getRenderShape() != RenderShape.MODEL) continue;

            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            if (model == null) continue;

            float ox = block.dx(), oy = block.dy(), oz = block.dz();
            // 方块初始世界位置（anchor + 偏移）
            final double bx = data.anchor().getX() + ox;
            final double by = data.anchor().getY() + oy;
            final double bz = data.anchor().getZ() + oz;

            pose.pushPose();
            // 光照包装：★ 2026-09-01 闪烁修复——用【初始稳定世界位置】采光/tint，
            //   不随物理位姿浮动的投影位置（投影位置运动到未加载区 → getLightValue=0 → 黑/正常闪烁）。
            //   官方 setup(renderPos,...) 用亚层位置；我们用每块初始位置（静止/小范围运动足够）。
            final double lightX = bx;
            final double lightY = by;
            final double lightZ = bz;
            TINT_GETTER.setup(mc.level, lightX, lightY, lightZ, BlockPos.ZERO, state);

            // 官方 VanillaSingleSubLevelRenderData 位姿算法：
            //   renderCOR = rot.transform(rotationPoint - blockPos)
            //   translate(renderCOR - cam) → rotate
            // ★ 2026-09-01 旋转中心修正：必须是【初始结构包围盒几何中心 C】
            //   （=(min+max+1)*0.5，与物理投影 SableSubLevelProjection 同基准）。
            //   之前用 pose.position（当前物理位置）作旋转中心 → 当结构动了（P≠C）
            //   渲染方块绕错点旋转 → "渲染块位置由离地面距离决定"（与物理不同步）。
            //   数学：blockWorld = P + R·(blockInitCenter − C)（刚体一致性）。
            final double cx = (data.boundMinX() + data.boundMaxX() + 1) * 0.5;
            final double cy = (data.boundMinY() + data.boundMaxY() + 1) * 0.5;
            final double cz = (data.boundMinZ() + data.boundMaxZ() + 1) * 0.5;
            final double corX = cx, corY = cy, corZ = cz;   // 旋转点 = 初始几何中心（非当前位姿）
            final Vector3d renderCOR = new Vector3d(
                    corX - bx, corY - by, corZ - bz);
            rot.transform(renderCOR);
            renderCOR.negate().add(px, py, pz);
            final Matrix4f tf = new Matrix4f();
            tf.translate((float) (renderCOR.x - cam.x), (float) (renderCOR.y - cam.y), (float) (renderCOR.z - cam.z));
            tf.rotate(new Quaternionf(rot));
            pose.last().pose().mul(tf);
            pose.last().normal().rotate(new Quaternionf(rot));

            final long blockSeed = state.getSeed(net.minecraft.core.BlockPos.containing(bx, by, bz));
            VertexConsumer consumer = buffers.getBuffer(RenderType.solid());
            RandomSource blockRandom = RandomSource.create(blockSeed);
            modelRenderer().tesselateWithoutAO(TINT_GETTER, model, state, BlockPos.ZERO,
                    pose, consumer, true, blockRandom, blockSeed,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            TINT_GETTER.clear();
            pose.popPose();
        }
    }
}
