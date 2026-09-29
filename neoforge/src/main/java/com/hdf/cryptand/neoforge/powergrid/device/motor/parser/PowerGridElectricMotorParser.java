package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 交错电网【普通电机】BE（ElectricMotorBlockEntity）——继承中间层（2026-08-24）。
 */
public final class PowerGridElectricMotorParser extends PowerGridMotorParser {

    PowerGridElectricMotorParser(BlockEntity be) {
        super(be);
    }

    @Override
    protected void onMessage(Object m) {
        super.onMessage(m);
        // 普通电机：默认只读；待启用写入时可在此实现（需规避 applyNewSpeed NPE）
    }
}
