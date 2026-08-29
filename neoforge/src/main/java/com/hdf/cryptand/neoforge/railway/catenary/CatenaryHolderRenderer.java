package com.hdf.cryptand.neoforge.railway.catenary;

import com.hdf.cryptand.neoforge.railway.RailwayPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 接触网悬挂块渲染器（移植自 CEE CatenaryHolderRenderer）。
 * 依据 attachedTo（最近的杆）渲染：抱箍(mount)/绝缘子/长短悬杆/连接器，
 * 悬杆长度随杆距缩放，hanging 以 yaw 对准杆的方向。
 */
public class CatenaryHolderRenderer extends SmartBlockEntityRenderer<CatenaryHolderBlockEntity> {
    public CatenaryHolderRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(CatenaryHolderBlockEntity blockEntity, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockPos attachedTo = blockEntity.getAttachedTo();
        if (blockEntity.poleWidth == 0 || attachedTo == null)
            return;

        CatenaryHolderBlock.Style style = blockEntity.getBlockState().getValue(CatenaryHolderBlock.STYLE);
        attachedTo = attachedTo.offset(blockEntity.getBlockPos());

        SuperByteBuffer grip;
        if (blockEntity.poleWidth == 4)
            grip = partial(style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_MOUNT_4_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_MOUNT_4, blockEntity);
        else if (blockEntity.poleWidth == 6)
            grip = partial(style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_MOUNT_6_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_MOUNT_6, blockEntity);
        else if (blockEntity.poleWidth == 8)
            grip = partial(style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_MOUNT_8_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_MOUNT_8, blockEntity);
        else
            grip = partial(style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_MOUNT_10_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_MOUNT_10, blockEntity);

        Vec3 difference = Vec3.atLowerCornerOf(attachedTo).subtract(Vec3.atLowerCornerOf(blockEntity.getBlockPos()));
        float yaw = (float) Math.toDegrees(Mth.atan2(difference.x, difference.z)) + 180;

        PartialModel longRod = style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_LONG_ROD_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_LONG_ROD;
        PartialModel shortRod = style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_SHORT_ROD_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_SHORT_ROD;
        PartialModel insulator = style.isWeathered() ? RailwayPartialModels.CATENARY_HOLDER_INSULATOR_WEATHERED : RailwayPartialModels.CATENARY_HOLDER_INSULATOR;

        Vec3 offset = attachedTo.subtract(blockEntity.getBlockPos()).getCenter().subtract(0.5, 0.5, 0.5);

        if (style.isLow()) {
            grip.translate(offset).translate(0.5, 11 / 32f, 0.5).light(light)
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            partial(insulator, blockEntity)
                    .translate(offset).translate(0.5, 8 / 16f, 0.5).rotateYDegrees(yaw)
                    .translate(0, 0, 1 / 16f + blockEntity.poleWidth / 32f).light(light)
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            float shortLength = 17 / 16f;
            float longLength = 28 / 16f;
            float horizontalDistance = (float) difference.horizontalDistance();
            boolean isLong = horizontalDistance > shortLength;
            float size = (horizontalDistance + 0.5f) / (isLong ? longLength : shortLength);
            partial(isLong ? longRod : shortRod, blockEntity)
                    .translate(offset).translate(0.5, 8 / 16f, 0.5).rotateYDegrees(yaw).scaleZ(size).light(light)
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            partial(RailwayPartialModels.CATENARY_HOLDER_CONNECTOR, blockEntity)
                    .translate(offset).translate(0.5, -8 / 16f, 0.5).rotateYDegrees(yaw)
                    .translate(0, 10 / 16f, horizontalDistance).light(light)
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
            return;
        }

        grip.translate(offset).translate(0.5, 43 / 32f, 0.5).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        partial(insulator, blockEntity)
                .translate(offset).translate(0.5, 24 / 16f, 0.5).rotateYDegrees(yaw)
                .translate(0, 0, 1 / 16f + blockEntity.poleWidth / 32f).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        float shortLength = 17 / 16f;
        float longLength = 28 / 16f;
        float horizontalDistance = (float) difference.horizontalDistance();
        boolean isLong = horizontalDistance > shortLength;
        float size = horizontalDistance / (isLong ? longLength : shortLength);
        partial(isLong ? longRod : shortRod, blockEntity)
                .translate(offset).translate(0.5, 24 / 16f, 0.5).rotateYDegrees(yaw)
                .scale(0.95f, 0.95f, size).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        float angle = -(float) Mth.atan2(2, horizontalDistance);
        partial(longRod, blockEntity)
                .translate(offset).translate(0.5, -8 / 16f, 0.5).rotateYDegrees(yaw).rotateX(angle)
                .scaleZ(Mth.sqrt(4f + horizontalDistance * horizontalDistance) / longLength).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        partial(insulator, blockEntity)
                .translate(offset).translate(0.5, -8 / 16f, 0.5).rotateYDegrees(yaw).rotateX(angle)
                .translate(0, 0, 0.25f).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        partial(longRod, blockEntity)
                .translate(offset).translate(0.5, -8 / 16f, 0.5).rotateYDegrees(yaw)
                .translate(0, 15 / 16f, horizontalDistance / 2 - 0.1)
                .scale(0.95f, 0.95f, (horizontalDistance + 1.1f) / (2 * longLength)).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));

        partial(RailwayPartialModels.CATENARY_HOLDER_CONNECTOR, blockEntity)
                .translate(offset).translate(0.5, -8 / 16f, 0.5).rotateYDegrees(yaw)
                .translate(0, 10 / 16f, horizontalDistance).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        grip.translate(offset).translate(0.5, -10 / 16f, 0.5).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }

    private static SuperByteBuffer partial(PartialModel model, CatenaryHolderBlockEntity be) {
        return CachedBuffers.partial(model, be.getBlockState());
    }
}