/**
 * ===== 外设栏杆（按钮）形状（2026-09-13）=====
 * 与拉杆同一套底座/手柄数值（复制自航空学官方 `SimBlockShapes`）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PeripheralRailingShapes {

    public static final VoxelShaper BASE = VoxelShaper.forDirectional(
            Shapes.or(Block.box(4, 0, 3, 12, 3, 13), Block.box(4, 0, 6, 12, 5, 10)), Direction.UP);

    public static final VoxelShaper BASE_SWAP = VoxelShaper.forDirectional(
            Shapes.or(Block.box(3, 0, 4, 13, 3, 12), Block.box(6, 0, 4, 10, 5, 12)), Direction.UP);

    private PeripheralRailingShapes() {
    }

    public static VoxelShape base(BlockState state) {
        Direction connected = PeripheralRailingBlock.connectedDirection(state);
        if (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) != AttachFace.WALL
                && state.getValue(HorizontalDirectionalBlock.FACING).getAxis() == Direction.Axis.X) {
            return BASE_SWAP.get(connected);
        }
        return BASE.get(connected);
    }
}
