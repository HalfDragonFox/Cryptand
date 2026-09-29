package com.hdf.cryptand.neoforge.waterphysics.mixin;

import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 下落方块（沙/砂砾）每 tick 改变所在位置的可容纳性，需要重扫。
 *
 * <p>下落方块数量通常很少；点名集合本身有去重，持续提交不会失控。
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void waterphysics$onTick(final CallbackInfo ci) {
        final FallingBlockEntity self = (FallingBlockEntity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            final BlockPos pos = self.blockPosition();
            WaterPhysicsModule.bridge(serverLevel).postCell(pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
