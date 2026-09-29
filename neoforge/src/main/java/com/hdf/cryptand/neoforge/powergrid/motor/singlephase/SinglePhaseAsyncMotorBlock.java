package com.hdf.cryptand.neoforge.powergrid.motor.singlephase;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlock;

/**
 * ===== 单相异步电机方块（2026-08-30） =====
 *
 * 继承 PowerGrid 普通电机方块 {@link ElectricMotorBlock}（原版电机模型/材质/
 * 端子/朝向/轴逻辑），加【极数】属性；BE 换成
 * {@link SinglePhaseAsyncMotorBlockEntity}（引擎按极数分发
 * {@code InductionMotorModel(poles)}——现实 2kW 单相异步电机参数）。
 */
public class SinglePhaseAsyncMotorBlock extends ElectricMotorBlock {

    /** 极数（2/4/6/8） */
    private final int poles;

    public SinglePhaseAsyncMotorBlock(BlockBehaviour.Properties properties, int poles) {
        super(properties);
        this.poles = (poles == 2 || poles == 4 || poles == 6 || poles == 8) ? poles : 2;
    }

    /** 极数（MotorAssembler 分发用） */
    public int poles() { return poles; }

    @SuppressWarnings("rawtypes")
    @Override
    public Class getBlockEntityClass() {
        return SinglePhaseAsyncMotorBlockEntity.class;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public BlockEntityType getBlockEntityType() {
        return SinglePhaseAsyncMotors.beTypeFor(poles);
    }
}
