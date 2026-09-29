package com.hdf.cryptand.neoforge.waterphysics.mixin;

import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 活塞推动方块会改变局部可容纳性（水被挡住/让开），推完重扫起点与伸出方向那一格。
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "moveBlocks", at = @At("RETURN"))
    private void waterphysics$afterMoveBlocks(final Level level, final BlockPos pos, final Direction facing,
                                              final boolean extending,
                                              final CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        WaterPhysicsModule.bridge(serverLevel).postCell(pos.getX(), pos.getY(), pos.getZ());
        final BlockPos moved = pos.relative(facing);
        WaterPhysicsModule.bridge(serverLevel).postCell(moved.getX(), moved.getY(), moved.getZ());
    }
}
