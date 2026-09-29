/**
 * ===== 外设拉杆 Flywheel 可视化（客户端，2026-09-13） =====
 *
 * 与官方 `ThrottleLeverVisual` 同款：手柄 / 按钮 / 指示灯三个变换实例，
 * 每帧按档位重算手柄角度、按档位重算指示灯颜色。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class PeripheralLeverVisual extends AbstractBlockEntityVisual<PeripheralLeverBlockEntity>
        implements SimpleDynamicVisual {

    private final TransformedInstance handle;
    private final TransformedInstance button;
    private final TransformedInstance diode;

    private final AttachFace attached;
    private final Direction facing;

    public PeripheralLeverVisual(VisualizationContext ctx, PeripheralLeverBlockEntity blockEntity,
                                float partialTick) {
        super(ctx, blockEntity, partialTick);
        this.attached = blockEntity.getBlockState().getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        this.facing = blockEntity.getBlockState().getValue(HorizontalDirectionalBlock.FACING);

        this.handle = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(PeripheralLeverPartialModels.HANDLE))
                .createInstance();
        this.button = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(PeripheralLeverPartialModels.BUTTON))
                .createInstance();
        this.diode = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(PeripheralLeverPartialModels.DIODE))
                .createInstance();

        this.diode.colorArgb(PeripheralLeverRenderer.redstoneColor(blockEntity.getState() / 15f));
        this.transformAll(partialTick);
    }

    @Override
    public void beginFrame(Context context) {
        this.diode.colorArgb(PeripheralLeverRenderer.redstoneColor(this.blockEntity.getState() / 15f));
        this.transformAll(context.partialTick());
    }

    private void transformAll(float partialTicks) {
        this.handle.setIdentityTransform();
        this.button.setIdentityTransform();
        this.diode.setIdentityTransform();

        this.initialTransform(this.handle);
        this.initialTransform(this.button);
        this.initialTransform(this.diode);

        float angle = PeripheralLeverRenderer.handleAngle(this.blockEntity, partialTicks);
        this.transformHandle(this.handle, angle);
        this.transformHandle(this.button, angle);

        this.handle.setChanged();
        this.button.setChanged();
        this.diode.setChanged();
    }

    private void initialTransform(TransformedInstance instance) {
        instance.translate(this.getVisualPosition());
        float rX = switch (this.attached) {
            case FLOOR -> 0f;
            case WALL -> 90f;
            default -> 180f;
        };
        float rY = AngleHelper.horizontalAngle(this.facing);
        instance.rotateCentered(rY / 180f * (float) Math.PI, Direction.UP);
        instance.rotateCentered(rX / 180f * (float) Math.PI, Direction.EAST);
        instance.rotateCentered(this.attached == AttachFace.CEILING ? (float) Math.PI : 0f, Direction.UP);
    }

    private void transformHandle(TransformedInstance instance, float angle) {
        instance.translate(0.5f, 3f / 16f, 0.5f)
                .rotateX(angle)
                .translateBack(0.5f, 3f / 16f, 0.5f)
                .rotateCentered(this.attached == AttachFace.WALL ? (float) Math.PI : 0f, Direction.UP);
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        consumer.accept(this.handle);
        consumer.accept(this.button);
        consumer.accept(this.diode);
    }

    @Override
    public void updateLight(float partialTick) {
        this.relight(this.handle);
        this.relight(this.button);
        this.relight(this.diode);
    }

    @Override
    protected void _delete() {
        this.handle.delete();
        this.button.delete();
        this.diode.delete();
    }
}
