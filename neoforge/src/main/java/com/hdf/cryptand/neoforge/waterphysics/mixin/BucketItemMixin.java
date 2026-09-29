package com.hdf.cryptand.neoforge.waterphysics.mixin;

import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsModule;
import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 桶的两个方向。
 *
 * <p>放水：{@code emptyContents} 返回 true 时把落点 section 推进队列。
 * <p>取水：{@code use} 内部把水源装进桶，没有可用事件（本版本没有 FillBucketEvent），
 * 于是在返回后按玩家视线重新定位目标方块。
 *
 * <p>倒下去的是原版源方块（LEVEL=0 ⇒ amount 8），快照侧把「侧表为 0 而世界有水」判成
 * 外部注入并采纳（RegionSnapshot.levelOf），所以这里只需把 section 推进队列。
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    @Inject(method = "emptyContents", at = @At("RETURN"))
    private void waterphysics$afterEmptyContents(final Player player, final Level level,
                                                 final BlockPos pos, final BlockHitResult hitResult,
                                                 final CallbackInfoReturnable<Boolean> cir) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;     // 关掉总开关后不介入（否则会白建桥接器、白点名）
        }
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            WaterPhysicsModule.bridge(serverLevel).postCell(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void waterphysics$afterUse(final Level level, final Player player, final InteractionHand hand,
                                       final CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (cir.getReturnValue() == null || !cir.getReturnValue().getResult().consumesAction()) {
            return;
        }
        final BlockHitResult hit = (BlockHitResult) player.pick(player.blockInteractionRange(), 0.0F, true);
        final BlockPos pos = hit.getBlockPos();
        WaterPhysicsModule.bridge(serverLevel).postCell(pos.getX(), pos.getY(), pos.getZ());
    }
}
