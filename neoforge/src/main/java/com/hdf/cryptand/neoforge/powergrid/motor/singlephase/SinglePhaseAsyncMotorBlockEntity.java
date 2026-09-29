package com.hdf.cryptand.neoforge.powergrid.motor.singlephase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity;

/**
 * ===== 单相异步电机 BE（2026-08-30） =====
 *
 * 继承原版 {@link ElectricMotorBlockEntity}（机械/Create 行为一致）；极数从
 * Block 读取——引擎（MotorAssembler）按极数创建
 * {@code InductionMotorModel(poles)}（现实 2kW 单相异步电机参数：同步转速
 * 120f/p、额定转矩 P/ω_rated、转差 3.3%、η=0.8）。
 */
public class SinglePhaseAsyncMotorBlockEntity extends ElectricMotorBlockEntity {

    /** 两参构造（同 RailwayRegistry 先例：BlockEntitySupplier 两参）；super 的
     *  type 从 Block 极数取对应 BE type（2026-08-30） */
    public SinglePhaseAsyncMotorBlockEntity(BlockPos pos, BlockState state) {
        super(beTypeOf(state), pos, state);
    }

    private static BlockEntityType<?> beTypeOf(BlockState state) {
        try {
            net.minecraft.world.level.block.Block b = state.getBlock();
            if (b instanceof SinglePhaseAsyncMotorBlock mb) {
                return SinglePhaseAsyncMotors.beTypeFor(mb.poles());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 极数（从 Block 读；MotorAssembler 分发用） */
    public int poles() {
        if (level != null) {
            net.minecraft.world.level.block.Block b =
                    level.getBlockState(worldPosition).getBlock();
            if (b instanceof SinglePhaseAsyncMotorBlock mb) return mb.poles();
        }
        return 2;
    }
}
