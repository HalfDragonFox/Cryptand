/**
 * ===== 外设船舵渲染器（客户端 · 非 Flywheel 路径，2026-09-13） =====
 *
 * 与航空学官方船舵 `SteeringWheelRenderer` 渲染内容一致：
 * <ol>
 *   <li><b>Create 半轴</b>（{@link AllPartialModels#SHAFT_HALF}，地面朝下/吊装朝上）——
 *       用 Create 的 {@link KineticBlockEntityRenderer#renderRotatingBuffer} 按网络转速旋转；</li>
 *   <li><b>舵轮</b>（按 {@link PeripheralHelmBlockEntity#getMaterial() 当前木料} 重建的模型，
 *       sprite 缓存走 {@link SuperByteBufferCache}）—— 朝向 → 舵轮位置 → 绕自身中心转到当前舵角。</li>
 * </ol>
 * 开启 Flywheel 可视化时本渲染器【让位】（{@link VisualizationManager#supportsVisualization}）——
 * 与官方 `KineticBlockEntityRenderer.renderSafe` 首行的互斥判定完全一致，由
 * {@link PeripheralHelmVisual} 接管（否则会双重渲染）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperByteBufferCache;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PeripheralHelmRenderer implements BlockEntityRenderer<PeripheralHelmBlockEntity> {

    /** 舵轮 sprite 缓存域（按木料 BlockState 缓存重建后的模型） */
    public static final SuperByteBufferCache.Compartment<BlockState> HELM_WHEEL =
            new SuperByteBufferCache.Compartment<>();

    public PeripheralHelmRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PeripheralHelmBlockEntity be, float partialTick, PoseStack ms,
                       MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof PeripheralHelmBlock)) {
            return;
        }
        // Flywheel 可视化开启【且 visual 确已注册】→ 交给 PeripheralHelmVisual
        // （与官方 KineticBlockEntityRenderer 同语义；带注册标志防止两边都不画）
        if (PeripheralHelmClient.visualRegistered()
                && VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }
        Direction facing = state.getValue(PeripheralHelmBlock.FACING);
        boolean onFloor = state.getValue(PeripheralHelmBlock.ON_FLOOR);

        // ===== 1) Create 半轴（与官方一致：地面安装朝下 / 吊装朝上，随转速转动） =====
        try {
            SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state,
                    onFloor ? Direction.DOWN : Direction.UP);
            KineticBlockEntityRenderer.renderRotatingBuffer(be, shaft, ms,
                    buffer.getBuffer(RenderType.solid()), light);
        } catch (Throwable ignored) {
        }

        // ===== 2) 舵轮（按木料重建 + 朝向 → 位置 → 舵角） =====
        float angle = be.getRenderAngle(partialTick);
        try {
            SuperByteBuffer wheel = wheelModel(be);
            wheel.center().rotateYDegrees(yRotOf(facing)).uncenter();
            wheel.translate(0f, 6.5f / 16f, onFloor ? -5f / 16f : 5f / 16f);
            wheel.center().rotateYDegrees(-angle).uncenter();
            wheel.light(light);
            wheel.color(0xFFFFFFFF);
            // 官方 simulated 用 solid（wheel.obj 只有实体面，无透明需求）
            wheel.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        } catch (Throwable ignored) {
            // 模型缺失/渲染异常不影响游戏运行（静默跳过）
        }
    }

    /** 舵轮模型（按木料 sprite 替换；SuperByteBufferCache 按材质缓存，换木料自动重建） */
    private static SuperByteBuffer wheelModel(PeripheralHelmBlockEntity be) {
        BlockState material = be.getMaterial();
        return SuperByteBufferCache.getInstance().get(HELM_WHEEL, material, () -> {
            BakedModel model = PeripheralHelmMaterials.wheelModel(material);
            return SuperBufferFactory.getInstance()
                    .createForBlock(model, Blocks.AIR.defaultBlockState(), new PoseStack());
        });
    }

    /** 与 blockstates/peripheral_helm.json 的 y 旋转保持一致 */
    private static float yRotOf(Direction facing) {
        return switch (facing) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f;
        };
    }
}
