package com.hdf.cryptand.neoforge.railway.pantograph;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.railway.RailwayPartialModels;
import com.hdf.cryptand.neoforge.railway.train.TrainTapCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;

/**
 * 列车/装置上的受电弓行为（移植自 CEE PantographMovementBehaviour，简化）：
 *   - tick：升弓动画（跟随装配时的 Extended/TargetExtensionState，平滑逼近 0.05/tick）
 *   - renderInContraption：全套局部模型骨架（底座/上下臂/支杆/滑触板/弹簧/连杆，
 *     DOUBLE 并排变体），随装配旋转/平移矩阵烘焙
 *   - disableBlockEntityRendering：受电弓由本行为渲染，关闭 BE 渲染
 * <p>
 * M3b 电气接入：把随车触头接入 Cryptand 自管接触网（滑触头动态拆段）待引擎侧
 * "列车网络对象"建模后接入；当前只做可视化与动画。
 */
public class PantographMovementBehaviour implements MovementBehaviour {

    @Override
    public void tick(MovementContext context) {
        float targetExtensionState = context.blockEntityData.getFloat("TargetExtensionState");
        if (context.blockEntityData.contains("Extended")
                && !context.blockEntityData.getBoolean("Extended"))
            targetExtensionState = 0;

        float currentExtensionState = context.data.getFloat("CurrentExtensionState");
        float prevExtensionState = currentExtensionState;
        currentExtensionState = currentExtensionState < targetExtensionState
                ? Math.min(targetExtensionState, currentExtensionState + 0.05f)
                : Math.max(targetExtensionState, currentExtensionState - 0.05f);

        if (context.world != null && context.world.isClientSide
                && currentExtensionState == targetExtensionState
                && prevExtensionState != currentExtensionState
                && context.position != null) {
            context.world.playLocalSound(context.position.x, context.position.y,
                    context.position.z, SoundEvents.CHAIN_HIT, SoundSource.NEUTRAL, 0.1f, 1f, false);
        }

        context.data.putFloat("PrevExtensionState", prevExtensionState);
        context.data.putFloat("CurrentExtensionState", currentExtensionState);

        // M3b 列车受电弓触头（服务端，低频）：把随车滑触头接入 Cryptand 自管接触网，
        // 经 TrainTapCache（列车触点点位 + 蓄电池状态）→ buildContextFromGraph 拆接触网段取电
        if (context.world != null && !context.world.isClientSide
                && context.contraption != null)
            handleTrainTapServer(context);
    }

    /** 触头命中接触网段（record：两端 holder + 参数 t） */
    private record CatenaryHit(BlockPos holderA, BlockPos holderB, float t) {
    }

    /** 服务端（主线程）：列车受电弓滑触板 → 最近接触网段触头（低频更新防图震荡）。 */
    private void handleTrainTapServer(MovementContext context) {
        try {
            if (!(context.contraption.entity instanceof CarriageContraptionEntity ce))
                return;
            Carriage carriage = ce.getCarriage();
            if (carriage == null) return;
            Train train = carriage.train;
            if (train == null) return;
            String trainKey = "T@" + Integer.toHexString(System.identityHashCode(train));

            float currentExt = context.data.getFloat("CurrentExtensionState");
            Vec3 connector = getConnectorPos(currentExt, context);
            CatenaryHit hit = findCatenaryHit(context, connector);
            TrainTapCache.TrainTap old = TrainTapCache.getTap(trainKey);
            if (hit != null) {
                TrainTapCache.TrainTap next = new TrainTapCache.TrainTap(
                        trainKey, hit.holderA(), hit.holderB(), hit.t(), true);
                // 低频：同一条接触网 + 参数变化 < 阈值 → 保持旧值（图版本稳定 → 求解）
                if (old != null && old.sameEdge(next)
                        && Math.abs(old.t() - next.t()) < TrainTapCache.TAP_EPSILON) {
                    return;
                }
                TrainTapCache.setTap(next);
            } else if (old != null) {
                TrainTapCache.setTap(null); // 驶离接触网 → 断开
            }
        } catch (Throwable ignored) {
        }
    }

    private CatenaryHit findCatenaryHit(MovementContext context, Vec3 connector) {
        float reach = 1.625f;
        float yaw = CeeTerminalSupport.facingYRotOf(context.state);
        CatenaryHit best = null;
        double bestDist = Double.MAX_VALUE;
        try {
            for (WireEdge e : WireNetworkManager.get().edgeList()) {
                if (!CeeTerminalSupport.isCatenaryEdge(e)) continue;
                BlockPos hA = CeeTerminalSupport.holderPosOf(e.a);
                BlockPos hB = CeeTerminalSupport.holderPosOf(e.b);
                if (hA == null || hB == null) continue;
                Vec3 pA = CeeTerminalSupport.terminalPosWorld(context.world, hA);
                Vec3 pB = CeeTerminalSupport.terminalPosWorld(context.world, hB);
                if (pA == null || pB == null) continue;
                float t = CeeTerminalSupport.closestPointOnWire(pA, pB, connector);
                if (t <= -0.01 || t >= 1.01) continue;
                Vec3 cp = CeeTerminalSupport.checkCatenary(pA, pB, connector, t, reach, yaw);
                if (cp == null) continue;
                double d = cp.distanceToSqr(connector);
                if (d < bestDist) {
                    bestDist = d;
                    best = new CatenaryHit(hA.immutable(), hB.immutable(), t);
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /** 滑触板世界位置（随车矩阵：局部 → 旋转 → 平移至世界；CEE 几何） */
    Vec3 getConnectorPos(float extensionState, MovementContext context) {
        Direction.Axis axis = context.state.getValue(FACING).getAxis();
        if (context.state.getValue(PantographBlock.DOUBLE)) {
            float lowerArmRadians = (-90 + extensionState * 27) * Mth.DEG_TO_RAD;
            double armHingePosY = Mth.cos(lowerArmRadians) * 1.5;
            double armHingePosX = Mth.sin(lowerArmRadians) * 1.5;
            float b = (float) (-0.4f - Math.abs(armHingePosX));
            float a = Mth.sqrt(-b * b + 2f * 2f);
            double pantographX = context.state.getValue(FACING).getAxisDirection()
                    == Direction.AxisDirection.NEGATIVE ? 0.5 : -0.5;
            Vec3 pos = new Vec3(axis == Direction.Axis.X ? pantographX : 0,
                    0.1875 + a + armHingePosY, axis == Direction.Axis.Z ? pantographX : 0);
            pos = context.rotation.apply(pos);
            pos = pos.add(context.position);
            return pos;
        }
        float lowerArmRadians = (-75 + extensionState * 30) * Mth.DEG_TO_RAD;
        double armHingePosY = Mth.cos(lowerArmRadians) * 1.875;
        double armHingePosX = Mth.sin(lowerArmRadians) * 1.875;
        float upperArmRadians = (89 - extensionState * 50) * Mth.DEG_TO_RAD;
        double pantographX = context.state.getValue(FACING).getAxisDirection()
                == Direction.AxisDirection.NEGATIVE ? 0.25 : -0.25;
        Vec3 pos = new Vec3((axis == Direction.Axis.X ? pantographX : 0), armHingePosY,
                        armHingePosX + (axis == Direction.Axis.Z ? pantographX : 0))
                .add(0, Mth.cos(upperArmRadians) * 1.9, Mth.sin(upperArmRadians) * 1.9);
        pos = context.rotation.apply(pos);
        pos = pos.add(context.position);
        return pos;
    }

    @Override
    public boolean disableBlockEntityRendering() {
        return true;
    }

    @Override
    public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
                                    ContraptionMatrices matrices, MultiBufferSource buffer) {
        PoseStack ms = matrices.getViewProjection();
        BlockState state = context.state;
        float extensionState = Mth.lerp(AnimationTickHolder.getPartialTicks(),
                context.data.getFloat("PrevExtensionState"),
                context.data.getFloat("CurrentExtensionState"));

        float yRot = state.getValue(FACING).toYRot();
        if (state.getValue(FACING).getAxis() == Direction.Axis.Z)
            yRot += 180;

        int light = LevelRenderer.getLightColor(renderWorld, context.localPos);
        int color = DyeColor.byName(context.blockEntityData.getString("Color"), DyeColor.WHITE)
                .getTextureDiffuseColor();
        if (state.getValue(PantographBlock.DOUBLE)) {
            renderDouble(context, state, matrices, ms, buffer, extensionState, yRot, color, light);
        } else {
            renderSingle(context, state, matrices, ms, buffer, extensionState, yRot, color, light);
        }
    }

    private void renderSingle(MovementContext context, BlockState state, ContraptionMatrices matrices,
                              PoseStack ms, MultiBufferSource buffer, float e, float yRot, int color, int light) {
        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_BASE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_LOWER_ARM, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.375, 0.8125).rotateXDegrees(-75 + e * 30)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
        float lowerArmRadians = (-75 + e * 30) * Mth.DEG_TO_RAD;
        double armHingePosY = Mth.cos(lowerArmRadians) * 1.875;
        double armHingePosX = Mth.sin(lowerArmRadians) * 1.875;

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_UPPER_ARM, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.375, 0.8125)
                .translate(0, armHingePosY, armHingePosX)
                .rotateXDegrees(-1 - e * 50)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_UPPER_ARM_ARM, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(12 / 16f, 0.375, 0.8125)
                .translate(0, armHingePosY, armHingePosX)
                .rotateXDegrees(-1 - e * 50).rotateYDegrees(12.5f)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_UPPER_ARM_ARM, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(4 / 16f, 0.375, 0.8125)
                .translate(0, armHingePosY, armHingePosX)
                .rotateXDegrees(-1 - e * 50).rotateYDegrees(-12.5f)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        float upperArmRadians = (89 - e * 50) * Mth.DEG_TO_RAD;
        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_CONNECTING_SURFACE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.375, 0.8125)
                .translate(0, armHingePosY, armHingePosX)
                .translate(0, Mth.cos(upperArmRadians) * 1.9, Mth.sin(upperArmRadians) * 1.9)
                .light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_SPRINGS, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.375, 0.25)
                .rotateXDegrees(-22 + e * -20)
                .scale(1, 1, 1 + e / 2)
                .light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_CONNECTING_ROD, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.375, 0.1875)
                .rotateXDegrees(-77 + e * 43)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }

    private void renderDouble(MovementContext context, BlockState state, ContraptionMatrices matrices,
                              PoseStack ms, MultiBufferSource buffer, float e, float yRot, int color, int light) {
        float rotationFactor = 27;
        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_BASE_DOUBLE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_LOWER_ARMS_DOUBLE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.375, 0.5)
                .rotateXDegrees(-90 + e * rotationFactor)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        float lowerArmRadians = (-90 + e * rotationFactor) * Mth.DEG_TO_RAD;
        double armHingePosY = Mth.cos(lowerArmRadians) * 1.5;
        double armHingePosX = Mth.sin(lowerArmRadians) * 1.5;
        float b = (float) (-0.4f - Math.abs(armHingePosX));
        float a = Mth.sqrt(-b * b + 2f * 2f);

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_UPPER_ARMS_DOUBLE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, armHingePosY + 0.375, armHingePosX + 0.5)
                .rotateX((float) Mth.atan2(a, b) - Mth.HALF_PI)
                .color(color).light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_CONNECTING_SURFACE_DOUBLE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, 0.5625 + a + armHingePosY, 1)
                .light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        float springHingePosY = Mth.cos(lowerArmRadians + 0.5f) * 0.425f;
        float springHingePosX = Mth.sin(lowerArmRadians + 0.5f) * 0.425f;
        CachedBuffers.partial(RailwayPartialModels.PANTOGRAPH_SPRINGS_DOUBLE, state)
                .transform(matrices.getModel()).center().rotateYDegrees(yRot).uncenter()
                .translate(0, springHingePosY + 0.375, springHingePosX + 0.5f)
                .scaleZ((-springHingePosX + 0.5f) * (16 / 13f))
                .light(light)
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }
}