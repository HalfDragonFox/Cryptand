package com.hdf.cryptand.neoforge.railway.pantograph;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 标识一个方块是"受电弓"（供 CarriageContraption 装配识别、以及电气接入）。
 * 移植自 CEE IPantographBlock。
 */
public interface IPantographBlock {
    PantographType getPantographType(BlockState state);

    default boolean isSidewaysPantograph() {
        return false;
    }
}