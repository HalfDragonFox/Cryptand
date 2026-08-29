package com.hdf.cryptand.neoforge.powergrid.device.motor;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 交错电网【恒速电机】BE（ConstantSpeedMotorBlockEntity）——继承中间层（2026-08-24）。
 */
public final class PowerGridConstantSpeedMotorParser extends PowerGridMotorParser {

    PowerGridConstantSpeedMotorParser(BlockEntity be) {
        super(be);
    }

    @Override
    protected void onMessage(Object m) {
        super.onMessage(m);
        // 恒速：设置转速写入待启用（规避 NPE 后实现）
    }
}
