/**
 * ===== 外设船舵方块（2026-09-13） =====
 *
 * 模型复制自 Create: Aeronautics（simulated）的船舵 `steering_wheel`：
 * 底座（block / block_up）+ 舵轮（wheel，由 {@link #createUI} 同包的
 * PeripheralHelmRenderer 单独渲染并随舵角旋转）。
 *
 * 右键 → LDLib2（Create 风格）绑定界面：选择外部方向盘/手柄、轴映射、手感参数。
 * 方块自身按舵角向四周输出【模拟红石信号】（0..15，中心 7/8 = 回正）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.hdf.cryptand.neoforge.core.api.CryptandDirectionalAnalogOutput;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.block.IHaveBigOutline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class PeripheralHelmBlock extends HorizontalDirectionalBlock
        implements IBE<PeripheralHelmBlockEntity>, BlockUIMenuType.BlockUI, IRotate, IHaveBigOutline,
        CryptandDirectionalAnalogOutput {

    /** 地面安装（false = 吊装在天花板，模型上下翻转） */
    public static final BooleanProperty ON_FLOOR = BooleanProperty.create("on_floor");

    public static final MapCodec<PeripheralHelmBlock> CODEC = simpleCodec(PeripheralHelmBlock::new);

    /** 碰撞/选中箱（底座 + 舵轮整体，略小于整方块） */
    private static final VoxelShape SHAPE = Shapes.box(
            2 / 16.0, 0.0, 3 / 16.0, 14 / 16.0, 1.0, 14 / 16.0);

    public PeripheralHelmBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(ON_FLOOR, true));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ON_FLOOR);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean floor = switch (context.getClickedFace()) {
            case UP -> true;
            case DOWN -> false;
            default -> Arrays.stream(context.getNearestLookingDirections())
                    .filter(d -> d.getAxis().isVertical()).findFirst()
                    .map(d -> d == Direction.DOWN).orElse(true);
        };
        Direction horizontal = Arrays.stream(context.getNearestLookingDirections())
                .filter(d -> d.getAxis().isHorizontal()).findFirst()
                .orElse(Direction.NORTH);
        return defaultBlockState()
                .setValue(FACING, horizontal.getOpposite())
                .setValue(ON_FLOOR, floor);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * 手持木板右键 → 更换舵轮木料（官方船舵同款语义；Shift 时放行给其他交互）。
     * 其余物品由 {@link #useWithoutItem} 打开绑定界面。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return this.onBlockEntityUseItemOn(level, pos, be -> be.applyMaterialIfValid(stack));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BlockUIMenuType.openUI(serverPlayer, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public ModularUI createUI(BlockUIHolder holder) {
        PeripheralHelmBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof PeripheralHelmBlockEntity e ? e : null;
        return com.hdf.cryptand.neoforge.aeronautics.helm.ui.PeripheralHelmUi.build(holder, be);
    }

    // ===== 模拟红石输出：舵角 → 0..15（中心 = 回正 8；左转降到 1、右转升到 15） =====

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PeripheralHelmBlockEntity be) {
            return be.analogOutput();
        }
        return 0;
    }

    /**
     * 方向性模拟信号（与原版船舵同语义；见 {@link PeripheralHelmBlockEntity#analogOutputFor}）。
     * <p>⚠ 原版 MC 的比较器只走 {@link #getAnalogOutputSignal}（不分方向），所以想按方向读取，
     * 必须调用本方法（例如其它子包、定向红石读取器）——原版船舵也是这么暴露给外部系统的。
     */
    @Override
    public int getAnalogSignalFrom(BlockState state, Level level, BlockPos pos, Direction side) {
        if (level.getBlockEntity(pos) instanceof PeripheralHelmBlockEntity be) {
            return be.analogOutputFor(side);
        }
        return 0;
    }

    /** 交互形状 = 整体箱体（原版船舵同样覆写了 interaction shape） */
    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPE;
    }

    // ===== Create 动能：输出轴在底面（地面安装）/ 顶面（吊装），轴向 Y（与航空学船舵一致） =====

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == (state.getValue(ON_FLOOR) ? Direction.DOWN : Direction.UP);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public Class<PeripheralHelmBlockEntity> getBlockEntityClass() {
        return PeripheralHelmBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PeripheralHelmBlockEntity> getBlockEntityType() {
        return PeripheralHelmRegistry.PERIPHERAL_HELM_BE.get();
    }
}
