package com.hdf.cryptand.neoforge.railway.catenary;

import com.hdf.cryptand.neoforge.railway.RailwayRegistry;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 接触网悬挂块（移植自 CEE CatenaryHolderBlock）。
 * STYLE：STANDARD / WEATHERED / LOW / WEATHERED_LOW（LLow=低安装高度，用于隧道等）。
 * 外形：两端卡箍，中间是穿越的接触网导线「节点」。
 */
public class CatenaryHolderBlock extends Block implements IBE<CatenaryHolderBlockEntity> {
    public static final EnumProperty<Style> STYLE = EnumProperty.create("style", Style.class);

    private static final VoxelShape SHAPE = Shapes.or(
            box(6, 0, 6, 10, 2, 10),
            box(6, 22, 6, 10, 24, 10),
            box(4, 2, 4, 12, 22, 12));

    public CatenaryHolderBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(STYLE, Style.STANDARD));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STYLE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                    LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public Class<CatenaryHolderBlockEntity> getBlockEntityClass() {
        return CatenaryHolderBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CatenaryHolderBlockEntity> getBlockEntityType() {
        return RailwayRegistry.CEE_CATENARY_HOLDER_BE.get();
    }

    public enum Style implements StringRepresentable {
        STANDARD, WEATHERED, LOW, WEATHERED_LOW;

        @Override
        public @NotNull String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isLow() {
            return this == LOW || this == WEATHERED_LOW;
        }

        public boolean isWeathered() {
            return this == WEATHERED || this == WEATHERED_LOW;
        }
    }
}