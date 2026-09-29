package com.hdf.cryptand.neoforge.soc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * ===== 组装台方块（2026-09-15，参考 OpenComputers 组装机）=====
 *
 * <p>交互（无需 GUI 也能完成组装）：</p>
 * <ul>
 *   <li><b>手持部件右键</b> → 放入对应槽位；</li>
 *   <li><b>空手右键</b> → 显示当前装配与预览，并在可组装时<b>执行组装</b>；</li>
 *   <li><b>潜行 + 空手右键</b> → 取回全部部件与产物。</li>
 * </ul>
 *
 * <p>LDLib2 面板（槽位可视化 + 按钮）由 {@code SocAssemblerUi} 提供。</p>
 */
public class SocAssemblerBlock extends Block implements EntityBlock,
        com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUI {

    /** LDLib2 面板（项目方针：所有 UI 一律 LDLib2） */
    @Override
    public com.lowdragmc.lowdraglib2.gui.ui.ModularUI createUI(
            com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder holder) {
        final SocAssemblerBlockEntity assembler = holder.player.level().getBlockEntity(holder.pos)
                instanceof SocAssemblerBlockEntity a ? a : null;
        return com.hdf.cryptand.neoforge.soc.ui.SocAssemblerUi.build(holder, assembler);
    }

    public SocAssemblerBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SocAssemblerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof SocAssemblerBlockEntity assembler)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            for (ItemStack stack : assembler.takeAll()) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
            player.displayClientMessage(Component.literal("[SoC] 已取回组装台内的全部物品"), false);
            return InteractionResult.SUCCESS;
        }
        // 空手右键：打开 LDLib2 面板（组装/取出/预览都在面板内）
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.openUI(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }

    /** 手持部件右键：放入（NeoForge 1.21.1 用 ItemInteractionResult） */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state,
                                                                  Level level, BlockPos pos, Player player,
                                                                  net.minecraft.world.InteractionHand hand,
                                                                  BlockHitResult hitResult) {
        if (level.isClientSide() || stack.isEmpty()) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof SocAssemblerBlockEntity assembler)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (assembler.insert(stack)) {
            if (!player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            player.displayClientMessage(Component.literal("[SoC] 已放入：" + stack.getHoverName().getString()), false);
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
