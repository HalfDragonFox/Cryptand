package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 交错电网【伺服电机】BE（ServoBlockEntity）——继承中间层（2026-08-24）。
 */
public final class PowerGridServoMotorParser extends PowerGridMotorParser {

    PowerGridServoMotorParser(BlockEntity be) {
        super(be);
    }

    @Override
    protected void onMessage(Object m) {
        super.onMessage(m);
        // 伺服：瞬达/瞬停的写入待启用（规避 NPE 后实现）
    }
}
