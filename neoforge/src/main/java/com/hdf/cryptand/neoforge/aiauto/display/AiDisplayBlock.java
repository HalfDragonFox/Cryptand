package com.hdf.cryptand.neoforge.aiauto.display;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * ===== AI 展示方块（纯调试设施）=====
 *
 * <p>用法：放下来 → 手持物品右键 = 展示该物品；空手右键 = 清空展示。
 * 想看方块模型时用 aiauto 工具 {@code display_set} 指定方块状态。</p>
 */
public class AiDisplayBlock extends Block implements EntityBlock {

    public AiDisplayBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AiDisplayBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof AiDisplayBlockEntity be) {
            if (!level.isClientSide) {
                if (stack.isEmpty()) {
                    be.setDisplay(ItemStack.EMPTY, null, be.scale(), be.spin(), be.yOffset());
                } else {
                    be.setDisplay(stack.copyWithCount(1), null, be.scale(), be.spin(), be.yOffset());
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
