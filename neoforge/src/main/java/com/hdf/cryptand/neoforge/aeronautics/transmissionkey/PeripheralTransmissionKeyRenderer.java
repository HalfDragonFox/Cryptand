/**
 * ===== 外设模拟传动器 BER（客户端，2026-09-14）=====
 *
 * 复制原版 `AnalogTransmissionRenderer`：Flywheel 可视化可用时让位（return），
 * 否则画三样 —— 轴（shaft）+ 外壳（父类 renderSafe）+ <b>内部齿轮</b>（附加动力学节点）。
 * 之前只复制了 blockstate/models 而漏掉这个渲染器，所以游戏里只剩外壳、看不到齿轮和杆。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public class PeripheralTransmissionKeyRenderer
        extends KineticBlockEntityRenderer<PeripheralTransmissionKeyBlockEntity> {

    public PeripheralTransmissionKeyRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(PeripheralTransmissionKeyBlockEntity be, float partialTicks,
                              PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;   // 交给 Flywheel
        }
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

        BlockState state = be.getBlockState();
        Direction.Axis axis = ((IRotate) state.getBlock()).getRotationAxis(state);

        // 内部齿轮：按"附加动力学节点"的转速旋转
        SuperByteBuffer cogwheel = kineticRotationTransform(
                CachedBuffers.partialFacingVertical(
                        PeripheralTransmissionKeyPartialModels.COG, state,
                        Direction.fromAxisAndDirection(state.getValue(PeripheralTransmissionKeyBlock.AXIS),
                                Direction.AxisDirection.POSITIVE)),
                be.getExtraKinetics(),
                axis,
                getAngleForBe(be.getExtraKinetics(), be.getBlockPos(), axis),
                light);
        cogwheel.renderInto(ms, buffer.getBuffer(RenderType.solid()));

        // 传动杆
        VertexConsumer vb = buffer.getBuffer(RenderType.solid());
        KineticBlockEntityRenderer.renderRotatingKineticBlock(be, shaft(getRotationAxisOf(be)),
                ms, vb, light);
    }

    @Override
    protected BlockState getRenderedBlockState(PeripheralTransmissionKeyBlockEntity be) {
        return shaft(getRotationAxisOf(be));
    }
}
