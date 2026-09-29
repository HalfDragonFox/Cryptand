/**
 * ===== 外设栏杆（按钮）方块（2026-09-13） =====
 *
 * 与外设拉杆同样的红石输出语义（信号源 0..15、朝向面强信号、扳手反转），
 * 但由【按键映射表】驱动：一组按键 ⇒ 一个信号，可多组（换挡器）。
 * 右键打开 LDLib2 绑定界面（界面支持增删条目、录制按键）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.hdf.cryptand.neoforge.aeronautics.railing.ui.PeripheralRailingUi;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class PeripheralRailingBlock extends FaceAttachedHorizontalDirectionalBlock
        implements IBE<PeripheralRailingBlockEntity>, IWrenchable, BlockUIMenuType.BlockUI {

    public static final MapCodec<PeripheralRailingBlock> CODEC = simpleCodec(PeripheralRailingBlock::new);
    public static final BooleanProperty INVERTED = BooleanProperty.create("inverted");

    public PeripheralRailingBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(INVERTED, false));
    }

    @Override
    protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** {@code getConnectedDirection} 是 protected static，外部包不可见 —— 暴露给形状工具 */
    public static Direction connectedDirection(BlockState state) {
        return getConnectedDirection(state);
    }

    /** 通知邻居 + 连接面 */
    public static void updateNeighbors(BlockState state, Level level, BlockPos pos) {
        level.updateNeighborsAt(pos, state.getBlock());
        level.updateNeighborsAt(pos.relative(getConnectedDirection(state).getOpposite()), state.getBlock());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (AllItems.WRENCH.isIn(player.getMainHandItem())) {
            return InteractionResult.PASS;   // 扳手交给 IWrenchable（反转方向）
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockUIMenuType.openUI(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIHolder holder) {
        PeripheralRailingBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof PeripheralRailingBlockEntity railing ? railing : null;
        return PeripheralRailingUi.build(holder, be);
    }

    // ==================== 红石输出 ====================

    @Override
    public int getSignal(BlockState blockState, BlockGetter blockAccess, BlockPos pos, Direction side) {
        return getBlockEntityOptional(blockAccess, pos).map(PeripheralRailingBlockEntity::getState).orElse(0);
    }

    @Override
    public int getDirectSignal(BlockState blockState, BlockGetter blockAccess, BlockPos pos, Direction side) {
        return getConnectedDirection(blockState) == side ? getSignal(blockState, blockAccess, pos, side) : 0;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos();
        int signal = getSignal(state, level, pos, context.getClickedFace());
        level.setBlock(pos, state.cycle(INVERTED), 2);
        withBlockEntityDo(level, pos, be -> be.setSignal(state.getValue(INVERTED) ? 15 - signal : signal));
        return InteractionResult.SUCCESS;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PeripheralRailingShapes.base(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING, FACE, INVERTED));
    }

    @Override
    public Class<PeripheralRailingBlockEntity> getBlockEntityClass() {
        return PeripheralRailingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PeripheralRailingBlockEntity> getBlockEntityType() {
        return PeripheralRailingRegistry.PERIPHERAL_RAILING_BE.get();
    }
}
