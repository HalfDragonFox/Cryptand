package com.hdf.cryptand.neoforge.railway.pantograph;

import com.hdf.cryptand.neoforge.railway.RailwayRegistry;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 受电弓方块（移植自 CEE PantographBlock）。
 * 状态属性：FACING（水平四向）+ DOUBLE（双受电弓并排）。
 * 交互：空手双击升/降弓；染料右键染色（可染上下双臂）。
 */
public class PantographBlock extends Block implements IBE<PantographBlockEntity>, IPantographBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty DOUBLE = BooleanProperty.create("double");

    private static final VoxelShape BASE = Shapes.or(
            box(1, 0, 1, 15, 5, 15),
            box(4, 5, 4, 12, 12, 12));

    public PantographBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(DOUBLE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, DOUBLE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getPlayer().isShiftKeyDown()
                        ? context.getHorizontalDirection().getOpposite()
                        : context.getHorizontalDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BASE;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                    LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 双受电弓联动：对面同向并排方块出现 → 双方置 DOUBLE；消失 → 解除
        BlockState facingState = level.getBlockState(pos.relative(state.getValue(FACING).getOpposite()));
        if (facingState.getBlock() instanceof PantographBlock
                && facingState.getValue(FACING) == state.getValue(FACING).getOpposite()) {
            if (!state.getValue(DOUBLE)) {
                level.setBlock(pos.relative(state.getValue(FACING).getOpposite()),
                        facingState.setValue(DOUBLE, true), 2);
                return state.setValue(DOUBLE, true);
            }
        } else if (state.getValue(DOUBLE)) {
            return state.setValue(DOUBLE, false);
        }
        return state;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                  BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        BlockState facingState = level.getBlockState(pos.relative(state.getValue(FACING)));
        if (facingState.getBlock() instanceof PantographBlock
                && facingState.getValue(FACING) == state.getValue(FACING).getOpposite()) {
            if (!state.getValue(DOUBLE)) {
                level.setBlockAndUpdate(pos.relative(state.getValue(FACING)), facingState.setValue(DOUBLE, true));
                level.setBlockAndUpdate(pos, state.setValue(DOUBLE, true));
            }
        } else if (state.getValue(DOUBLE)) {
            level.setBlockAndUpdate(pos, state.setValue(DOUBLE, false));
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.isEmpty()) {
            boolean extended = false;
            if (level.getBlockEntity(pos) instanceof PantographBlockEntity be) {
                extended = be.extended ^= true;
                be.targetExtensionState = extended ? (state.getValue(DOUBLE) ? 1.7f : 0.85f) : 0f;
                be.notifyUpdate();
            }
            if (state.getValue(DOUBLE)) {
                if (level.getBlockEntity(pos.relative(state.getValue(FACING).getOpposite())) instanceof PantographBlockEntity be) {
                    be.extended = extended;
                    be.targetExtensionState = extended ? 1.7f : 0f;
                    be.notifyUpdate();
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (!(stack.getItem() instanceof DyeItem di))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (level.getBlockEntity(pos) instanceof PantographBlockEntity be) {
            be.color = di.getDyeColor();
            if (!level.isClientSide)
                be.notifyUpdate();
        }
        if (state.getValue(DOUBLE)
                && level.getBlockEntity(pos.relative(state.getValue(FACING).getOpposite())) instanceof PantographBlockEntity be) {
            be.color = di.getDyeColor();
            if (!level.isClientSide)
                be.notifyUpdate();
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public Class<PantographBlockEntity> getBlockEntityClass() {
        return PantographBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PantographBlockEntity> getBlockEntityType() {
        return RailwayRegistry.CEE_PANTOGRAPH_BE.get();
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public PantographType getPantographType(BlockState state) {
        return state.getValue(DOUBLE) ? PantographType.DOUBLE : PantographType.STANDARD;
    }
}