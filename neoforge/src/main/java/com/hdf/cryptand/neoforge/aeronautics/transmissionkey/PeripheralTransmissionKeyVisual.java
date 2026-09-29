/**
 * ===== 外设模拟传动器 Flywheel 可视化（客户端，2026-09-14）=====
 *
 * 复制原版 `AnalogTransmissionVisual`：轴用 Create 的 SHAFT 模型，
 * 齿轮用我们自己的 partial model，并按"附加动力学节点"的转速独立旋转。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.core.Direction;

import java.util.function.Consumer;

public class PeripheralTransmissionKeyVisual
        extends SingleAxisRotatingVisual<PeripheralTransmissionKeyBlockEntity> {

    private final RotatingInstance cogInstance;

    public PeripheralTransmissionKeyVisual(VisualizationContext context,
                                        PeripheralTransmissionKeyBlockEntity blockEntity,
                                        float partialTick) {
        super(context, blockEntity, partialTick, Direction.UP, Models.partial(AllPartialModels.SHAFT));
        this.cogInstance = this.instancerProvider()
                .instancer(AllInstanceTypes.ROTATING, Models.partial(PeripheralTransmissionKeyPartialModels.COG))
                .createInstance()
                .rotateToFace(Direction.UP, this.rotationAxis())
                .setup(blockEntity.getExtraKinetics())
                .setPosition(this.getVisualPosition());
        this.cogInstance.setChanged();
    }

    @Override
    public void update(float pt) {
        super.update(pt);
        this.cogInstance.setup(this.blockEntity.getExtraKinetics()).setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        super.updateLight(partialTick);
        this.relight(this.cogInstance);
    }

    @Override
    protected void _delete() {
        super._delete();
        this.cogInstance.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        super.collectCrumblingInstances(consumer);
        consumer.accept(this.cogInstance);
    }
}
