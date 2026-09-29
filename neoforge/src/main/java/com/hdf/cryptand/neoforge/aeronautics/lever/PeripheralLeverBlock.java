/**
 * ===== 外设拉杆方块（2026-09-13） =====
 *
 * 复制自航空学（simulated）的油门拉杆 `throttle_lever`：
 * <ul>
 *   <li>贴地 / 贴墙 / 贴顶安装（{@code FaceAttachedHorizontalDirectionalBlock}）+ {@code INVERTED} 反转；</li>
 *   <li><b>输出</b>：直接输出红石强度 0..15（{@code getSignal} = 档位；{@code getDirectSignal} 在连接面给强信号）；</li>
 *   <li><b>右键</b>：打开绑定界面（LDLib2），把外部设备的踏板轴 / 按钮映射成 0..15 档
 *       （走官方的 {@code setSignal} 入口，红石输出、音效、邻居更新全部沿用官方语义）；</li>
 *   <li>官方"抓住手柄拖动调档"的手抓交互已按用户要求<b>取消</b>（2026-09-13，与外设船舵统一为界面绑定）。</li>
 * </ul>
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.hdf.cryptand.neoforge.aeronautics.lever.ui.PeripheralLeverUi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public class PeripheralLeverBlock extends FaceAttachedHorizontalDirectionalBlock
        implements IBE<PeripheralLeverBlockEntity>, IWrenchable, BlockUIMenuType.BlockUI {

    public static final MapCodec<PeripheralLeverBlock> CODEC = simpleCodec(PeripheralLeverBlock::new);
    public static final BooleanProperty INVERTED = BooleanProperty.create("inverted");

    public PeripheralLeverBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(INVERTED, false));
    }

    @Override
    protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** {@code getConnectedDirection} 是 protected static，外部包不可见 —— 由方块暴露给形状工具 */
    public static Direction connectedDirection(BlockState state) {
        return getConnectedDirection(state);
    }

    /** 通知邻居 + 连接面（官方 {@code updateNeighbors}） */
    public static void updateNeighbors(BlockState state, Level level, BlockPos pos) {
        level.updateNeighborsAt(pos, state.getBlock());
        level.updateNeighborsAt(pos.relative(getConnectedDirection(state).getOpposite()), state.getBlock());
    }

    // ==================== 交互 ====================

    /**
     * 右键 = 打开绑定界面（<b>与外设船舵一致</b>）。
     * <p>2026-09-13 用户定稿：<b>取消官方"手抓"交互</b>（抓住手柄拖动调档），
     * 统一改为"界面绑定外部设备"这一套 —— 踏板轴 / 按钮 → 线性 0..15 档。
     */
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
        PeripheralLeverBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof PeripheralLeverBlockEntity lever ? lever : null;
        return PeripheralLeverUi.build(holder, be);
    }

    // ==================== 红石输出（官方语义） ====================

    @Override
    public int getSignal(BlockState blockState, BlockGetter blockAccess, BlockPos pos, Direction side) {
        return getBlockEntityOptional(blockAccess, pos).map(be -> be.getState()).orElse(0);
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

    // ==================== 扳手：反转 + 档位取反 ====================

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos();
        int signal = getSignal(state, level, pos, context.getClickedFace());
        addParticles(state, level, pos, 1.0F);
        level.setBlock(pos, state.cycle(INVERTED), 2);
        withBlockEntityDo(level, pos, be -> be.setSignal(state.getValue(INVERTED) ? 15 - signal : signal));
        return InteractionResult.SUCCESS;
    }

    // ==================== 形状 / 粒子 / 移除 ====================

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PeripheralLeverShapes.base(state);
    }

    /** 手柄形状（模型命中/调试用） */
    public VoxelShape getHandleShape(BlockState state) {
        return PeripheralLeverShapes.handle(state);
    }

    private static void addParticles(BlockState state, LevelAccessor level, BlockPos pos, float alpha) {
        Direction direction = state.getValue(FACING).getOpposite();
        Direction connected = getConnectedDirection(state).getOpposite();
        double x = pos.getX() + 0.5 + 0.1 * direction.getStepX() + 0.2 * connected.getStepX();
        double y = pos.getY() + 0.5 + 0.1 * direction.getStepY() + 0.2 * connected.getStepY();
        double z = pos.getZ() + 0.5 + 0.1 * direction.getStepZ() + 0.2 * connected.getStepZ();
        level.addParticle(new DustParticleOptions(new Vector3f(1.0F, 0.0F, 0.0F), alpha), x, y, z, 0, 0, 0);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        withBlockEntityDo(level, pos, be -> {
            if (be.getState() != 0 && random.nextFloat() < 0.25F) {
                addParticles(state, level, pos, 0.5F);
            }
        });
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (isMoving || state.getBlock() == newState.getBlock()) {
            super.onRemove(state, level, pos, newState, isMoving);
            return;
        }
        withBlockEntityDo(level, pos, be -> {
            if (be.getState() != 0) {
                updateNeighbors(state, level, pos);
            }
        });
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING, FACE, INVERTED));
    }

    @Override
    public Class<PeripheralLeverBlockEntity> getBlockEntityClass() {
        return PeripheralLeverBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PeripheralLeverBlockEntity> getBlockEntityType() {
        return PeripheralLeverRegistry.PERIPHERAL_LEVER_BE.get();
    }
}
