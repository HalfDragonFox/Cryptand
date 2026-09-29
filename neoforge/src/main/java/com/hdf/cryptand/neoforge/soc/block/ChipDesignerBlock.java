package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.soc.content.BlueprintItem;
import com.hdf.cryptand.neoforge.soc.ui.ChipDesignerUi;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * ===== 芯片蓝图设计机（方块，2026-09-29）=====
 *
 * <p>用户定案：「设置一个芯片蓝图设计机的方块，可以选择设计哪种类型的芯片（CPU、内存等，目前仅 CPU 即可），
 * 然后可以选择具体芯片种类（MCU、SOC、CPU 等）、芯片频率（按类型给范围）、装载的模块（组件，比如 UART 等）」。</p>
 *
 * <p><b>分层</b>：前端 = LDLib2 面板（{@link ChipDesignerUi}）；后端 = common 的
 * {@code ChipFactory}（申请句柄 / 输入参数 / 获取组件表 / 使用默认组件表 / 输出最终 CPU / 停止销毁）。
 * 本方块只做三件事：蓝图槽、句柄生命周期、把面板打开。</p>
 *
 * <h3>交互（照用户定案）</h3>
 * <ul>
 *   <li>手持<b>蓝图</b>右键：放进蓝图槽（首次放入）+ 打开面板；</li>
 *   <li>空手右键：打开面板（无蓝图也能开，面板提示"先放一张蓝图"）；</li>
 *   <li>潜行空手右键：把蓝图取回手里；</li>
 *   <li>拆掉方块：槽里的蓝图掉落，且在制句柄被工厂销毁。</li>
 * </ul>
 */
public class ChipDesignerBlock extends HorizontalDirectionalBlock implements EntityBlock, BlockUIMenuType.BlockUI {

    public static final MapCodec<ChipDesignerBlock> CODEC = simpleCodec(ChipDesignerBlock::new);

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public ChipDesignerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends ChipDesignerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChipDesignerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return null;   // 面板数据由玩家操作驱动（事件式），不需要每 tick 采集
    }

    // ==================== 交互 ====================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return act(level, pos, player, ItemStack.EMPTY, InteractionHand.MAIN_HAND);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return act(level, pos, player, stack, hand) == InteractionResult.SUCCESS
                ? ItemInteractionResult.SUCCESS
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** 唯一交互入口（服务端做事，客户端只回执） */
    private static InteractionResult act(Level level, BlockPos pos, Player player, ItemStack held,
                                         InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ChipDesignerBlockEntity be)) {
            return InteractionResult.PASS;
        }
        // 潜行空手 = 取回蓝图
        if (player.isCrouching() && held.isEmpty()) {
            final ItemStack blueprint = be.blueprintStack();
            if (!blueprint.isEmpty()) {
                be.handler().setStackInSlot(ChipDesignerBlockEntity.SLOT_BLUEPRINT, ItemStack.EMPTY);
                player.getInventory().add(blueprint);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "[设计机] 已取回蓝图：" + BlueprintItem.blueprint(blueprint).displayName()), false);
            }
            return InteractionResult.SUCCESS;
        }
        // 手持蓝图 = 放进蓝图槽
        if (held.getItem() instanceof BlueprintItem) {
            if (be.blueprintStack().isEmpty()) {
                final ItemStack put = held.copyWithCount(1);
                be.handler().setStackInSlot(ChipDesignerBlockEntity.SLOT_BLUEPRINT, put);
                if (!player.isCreative()) {
                    held.shrink(1);
                }
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "[设计机] 已放入蓝图：" + BlueprintItem.blueprint(put).displayName()), false);
            }
        }
        if (player instanceof ServerPlayer sp) {
            be.openDraft(sp);            // 申请（或导入已有蓝图内容）
            BlockUIMenuType.openUI(sp, pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        final ChipDesignerBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof ChipDesignerBlockEntity d ? d : null;
        return ChipDesignerUi.build(holder, be);
    }

    /** 拆掉方块：槽里的蓝图掉出来（句柄由 BE 的 setRemoved 停止） */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
        if (!level.isClientSide() && blockEntity instanceof ChipDesignerBlockEntity be) {
            final ItemStack blueprint = be.blueprintStack();
            if (!blueprint.isEmpty()) {
                be.handler().setStackInSlot(ChipDesignerBlockEntity.SLOT_BLUEPRINT, ItemStack.EMPTY);
                Block.popResource(level, pos, blueprint);
            }
        }
    }

    /** 放置时不带 NBT 内容（蓝图由玩家放入） */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
    }
}
