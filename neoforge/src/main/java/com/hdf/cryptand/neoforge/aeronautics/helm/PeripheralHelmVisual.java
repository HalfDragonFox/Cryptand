/**
 * ===== 外设船舵 Flywheel 可视化（客户端，2026-09-13） =====
 *
 * 原样搬航空学官方 {@code SteeringWheelVisual}：Flywheel 可视化开启时由本类渲染
 * （半轴 {@link AllPartialModels#SHAFT_HALF} + 按材质重建的舵轮），BER 则自行让位
 * （见 {@link PeripheralHelmRenderer} 的 `supportsVisualization` 判断）——与官方
 * `KineticBlockEntityRenderer` 的互斥语义完全一致。
 * <p>
 * 材质缓存用 Flywheel 的 {@code RendererReloadCache}（渲染器重载时自动失效）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.ColoredLitInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class PeripheralHelmVisual extends KineticBlockEntityVisual<PeripheralHelmBlockEntity>
        implements SimpleDynamicVisual {

    private static final RendererReloadCache<BlockState, Model> MODEL_CACHE =
            new RendererReloadCache<>(PeripheralHelmVisual::generateModel);

    private final RotatingInstance shaftInstance;
    private final Direction facing;
    private final boolean onFloor;

    private TransformedInstance wheelInstance;
    private BlockState lastMaterial;

    private final List<ColoredLitInstance> allInstances = new ArrayList<>();

    public PeripheralHelmVisual(VisualizationContext ctx, PeripheralHelmBlockEntity blockEntity,
                                float partialTick) {
        super(ctx, blockEntity, partialTick);
        this.facing = blockEntity.getBlockState().getValue(PeripheralHelmBlock.FACING);
        this.onFloor = blockEntity.getBlockState().getValue(PeripheralHelmBlock.ON_FLOOR);

        this.setupWheel(partialTick);

        this.shaftInstance = this.instancerProvider()
                .instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                .createInstance()
                .setup(blockEntity, Direction.Axis.X)
                .rotateToFace(this.onFloor ? Direction.SOUTH : Direction.NORTH)
                .setPosition(this.getVisualPosition());
        this.allInstances.add(this.shaftInstance);
    }

    @Override
    public void update(float partialTick) {
        super.update(partialTick);
        this.shaftInstance.setup(this.blockEntity, Direction.Axis.Y).setChanged();
    }

    @Override
    public void beginFrame(Context context) {
        if (this.blockEntity.getMaterial() != this.lastMaterial) {
            this.wheelInstance.delete();
            this.allInstances.remove(this.wheelInstance);
            this.setupWheel(context.partialTick());
        } else {
            this.transformWheel(context.partialTick());
        }
    }

    private void setupWheel(float partialTicks) {
        this.lastMaterial = this.blockEntity.getMaterial();
        this.wheelInstance = this.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, MODEL_CACHE.get(this.lastMaterial))
                .createInstance();
        this.transformWheel(partialTicks);
        this.allInstances.add(this.wheelInstance);
        this.relight(this.wheelInstance);
        this.wheelInstance.setChanged();
    }

    private void transformWheel(float partialTicks) {
        this.wheelInstance.setIdentityTransform();
        this.wheelInstance.translate(this.getVisualPosition())
                .rotateCentered(this.facing.getRotation());
        if (this.onFloor) {
            this.wheelInstance.translate(0, 6.5 / 16f, -5 / 16f);
        } else {
            this.wheelInstance.translate(0, 6.5 / 16f, 5 / 16f);
        }
        this.wheelInstance.rotateCentered(
                this.blockEntity.getRenderAngle(partialTicks) * ((float) Math.PI / 180f), Direction.UP);
        this.wheelInstance.setChanged();
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        for (ColoredLitInstance inst : this.allInstances) {
            consumer.accept(inst);
        }
    }

    @Override
    public void updateLight(float v) {
        for (ColoredLitInstance inst : this.allInstances) {
            this.relight(inst);
        }
    }

    @Override
    protected void _delete() {
        for (ColoredLitInstance inst : this.allInstances) {
            inst.delete();
        }
    }

    private static Model generateModel(BlockState material) {
        return new BakedModelBuilder(PeripheralHelmMaterials.wheelModel(material)).build();
    }
}
