/**
 * ===== 外设拉杆渲染（BER，2026-09-13） =====
 *
 * 照搬航空学官方 `ThrottleLeverRenderer` 的变换：手柄绕底座轴（1/2, 3/16, 1/2）旋转，
 * 角度 = (档位/15) × ±40°；贴墙（WALL）时角度取反、并整体转 180°。
 * Flywheel 可视化开启时本渲染器让位（与官方双路径一致）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

public class PeripheralRailingRenderer extends SafeBlockEntityRenderer<PeripheralRailingBlockEntity> {

    /** 手柄最大偏摆角（度）——档位 0 时 -40°、档位 15 时 +40°（官方同值） */
    public static final double ANGLE_LIMIT = 40.0;

    public PeripheralRailingRenderer(BlockEntityRendererProvider.Context context) {
        // Create 的 SafeBlockEntityRenderer 是无参基类（context 只是 MC 的注册约定参数）
    }

    @Override
    protected void renderSafe(PeripheralRailingBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource bufferSource, int light, int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;   // Flywheel 路径负责渲染
        }
        BlockState state = be.getBlockState();
        AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        float angle = handleAngle(be, partialTicks);
        VertexConsumer vb = bufferSource.getBuffer(RenderType.cutoutMipped());

        SuperByteBuffer handle = CachedBuffers.partial(PeripheralRailingPartialModels.HANDLE, state);
        SuperByteBuffer button = CachedBuffers.partial(PeripheralRailingPartialModels.BUTTON, state);
        SuperByteBuffer diode = CachedBuffers.partial(PeripheralRailingPartialModels.DIODE, state);

        transform(handle, state);
        transform(button, state);
        transform(diode, state);

        rotateHandle(handle, angle, face).light(light).renderInto(ms, vb);
        rotateHandle(button, angle, face).light(light).renderInto(ms, vb);
        diode.light(light).color(redstoneColor(be.getState() / 15f)).renderInto(ms, vb);
    }

    /** 手柄角度（弧度；含贴墙取反） */
    public static float handleAngle(PeripheralRailingBlockEntity be, float partialTicks) {
        float state = be.getClientAngle(partialTicks);
        float angle = (float) (((state / 15f) * (ANGLE_LIMIT * 2) - ANGLE_LIMIT) / 180 * Math.PI);
        if (be.getBlockState().getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.WALL) {
            angle = -angle;
        }
        return angle;
    }

    /** 绕底座轴旋转手柄（返回自身类型，调用方才能继续用 SuperByteBuffer 的 light/renderInto） */
    public static <T extends TransformStack<T>> T rotateHandle(T buffer, float angle, AttachFace face) {
        buffer.translate(0.5f, 3f / 16f, 0.5f)
                .rotateX(angle)
                .translateBack(0.5f, 3f / 16f, 0.5f)
                .rotateCentered(face == AttachFace.WALL ? (float) Math.PI : 0f, Direction.UP);
        return buffer;
    }

    /** 按 FACING/FACE 摆放（官方 `transform` 同款） */
    public static <T extends TransformStack<T>> T transform(T buffer, BlockState state) {
        AttachFace attached = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        float rX = switch (attached) {
            case FLOOR -> 0f;
            case WALL -> 90f;
            default -> 180f;
        };
        float rY = AngleHelper.horizontalAngle(facing);
        buffer.rotateCentered(rY / 180f * (float) Math.PI, Direction.UP);
        buffer.rotateCentered(rX / 180f * (float) Math.PI, Direction.EAST);
        buffer.rotateCentered(attached == AttachFace.CEILING ? (float) Math.PI : 0f, Direction.UP);
        return buffer;
    }


    /** 指示灯颜色：暗红（0 档）→ 亮红（15 档） */
    public static int redstoneColor(float strength) {
        float t = Mth.clamp(strength, 0f, 1f);
        int r = (int) Mth.lerp(t, 0x3A, 0xFF);
        int g = (int) Mth.lerp(t, 0x00, 0x2B);
        int b = (int) Mth.lerp(t, 0x00, 0x2B);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
