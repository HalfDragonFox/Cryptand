/**
 * ===== 外设拉杆碰撞/选中形状（2026-09-13） =====
 *
 * 数值照抄航空学 simulated 的 `SimBlockShapes`（单位 1/16 格）：
 * <ul>
 *   <li>底座 {@code THROTTLE_LEVER = shape(4,0,3,12,3,13) + box(4,0,6,12,5,10)}</li>
 *   <li>朝向 X 轴时的备用底座 {@code _SWAP = shape(3,0,4,13,3,12) + box(6,0,4,10,5,12)}</li>
 *   <li>手柄 {@code _HANDLE = box(7,3,7,9,15,9) + box(6.8,15,6.8,9.2,21.4,9.2)}</li>
 * </ul>
 * 用 catnip 的 {@code VoxelShaper} 做六向旋转（与官方同一套语义）。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PeripheralLeverShapes {

    /** 底座（贴地/贴墙/贴顶时按连接方向旋转） */
    public static final VoxelShaper LEVER = VoxelShaper.forDirectional(
            Shapes.or(Block.box(4, 0, 3, 12, 3, 13), Block.box(4, 0, 6, 12, 5, 10)), Direction.UP);

    /** 底座备用（官方在"非贴墙且朝向 X 轴"时用它，避免模型与碰撞箱错位） */
    public static final VoxelShaper LEVER_SWAP = VoxelShaper.forDirectional(
            Shapes.or(Block.box(3, 0, 4, 13, 3, 12), Block.box(6, 0, 4, 10, 5, 12)), Direction.UP);

    /** 手柄（模型命中/调试用） */
    public static final VoxelShaper LEVER_HANDLE = VoxelShaper.forDirectional(
            Shapes.or(Block.box(7, 3, 7, 9, 15, 9), Block.box(6.8, 15, 6.8, 9.2, 21.4, 9.2)), Direction.UP);

    private PeripheralLeverShapes() {
    }

    /** 取底座形状（按 FACING/FACE 旋转，含 X 轴备用箱的判定） */
    public static VoxelShape base(net.minecraft.world.level.block.state.BlockState state) {
        Direction connected = PeripheralLeverBlock.connectedDirection(state);
        if (state.getValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE)
                != net.minecraft.world.level.block.state.properties.AttachFace.WALL
                && state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)
                .getAxis() == Direction.Axis.X) {
            return LEVER_SWAP.get(connected);
        }
        return LEVER.get(connected);
    }

    /** 取手柄形状 */
    public static VoxelShape handle(net.minecraft.world.level.block.state.BlockState state) {
        return LEVER_HANDLE.get(PeripheralLeverBlock.connectedDirection(state));
    }
}
