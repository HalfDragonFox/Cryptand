package com.hdf.cryptand.neoforge.railway.train;

import com.hdf.cryptand.neoforge.railway.pantograph.PantographType;
import net.minecraft.core.BlockPos;

/**
 * 列车受电弓条目（移植自 CEE TrainPantographEntry，剥离 CEE 模拟库依赖）。
 * 装配（CarriageContraptionMixin.capture）时，把受电弓方块记录为列车受电弓：
 * originalPos = 装配前 contraption 本地 pos；rotatedPos = 装配（旋转）后 pos；
 * facingForward = 是否朝车头方向。active 表示升弓状态（M3b 电气接入用）。
 */
public final class TrainPantographEntry {
    public final BlockPos originalPos;
    public final PantographType type;
    public final BlockPos rotatedPos;
    public boolean active;
    public boolean facingForward;

    public TrainPantographEntry(BlockPos originalPos, BlockPos rotatedPos,
                                PantographType type, boolean active, boolean facingForward) {
        this.originalPos = originalPos;
        this.rotatedPos = rotatedPos;
        this.type = type;
        this.active = active;
        this.facingForward = facingForward;
    }

    @Override
    public String toString() {
        return "TrainPantograph(" + originalPos + " -> " + rotatedPos
                + " active=" + active + " forward=" + facingForward + ")";
    }
}