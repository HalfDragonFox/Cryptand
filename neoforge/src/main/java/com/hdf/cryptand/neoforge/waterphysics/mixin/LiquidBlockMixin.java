package com.hdf.cryptand.neoforge.waterphysics.mixin;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.neoforge.waterphysics.FluidApplier;
import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsBridge;
import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsModule;
import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 被 waterphysics 接管的 section 里，原版不许动我们的水。
 *
 * <p>原版 {@code LiquidBlock.updateShape} 的逻辑是「流动水周围没有源就消失」。
 * 我们的水位（LEVEL = 8 - 水位）在它眼里正是「没有源支撑的流动水」，
 * 于是一有邻居更新就被改回 AIR —— 真机表现就是「水有时静止 / 刚写进去就没了」。
 * 这里对已接管的 section 直接保持原状态，水位的生灭完全由求解器决定。
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void waterphysics$keepManagedWater(final BlockState state, final Direction direction,
                                               final BlockState neighborState, final LevelAccessor level,
                                               final BlockPos pos, final BlockPos neighborPos,
                                               final CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;     // ★ 总开关关掉时必须放行，否则「完全回到原版」不成立：
                        //   原版流体 tick 活着，水位生灭却被我们拦住 ⇒ 水不消失、行为更坏。
        }
        final WaterPhysicsBridge bridge = WaterPhysicsModule.bridgeOrNull(serverLevel);
        if (bridge == null) {
            return;
        }
        final long sectionKey = SectionCursor.key(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (bridge.store().get(sectionKey) != null) {
            cir.setReturnValue(state);
        }
    }

    /**
     * 写回投影期间不排原版流体 tick —— 断掉自持环路。
     *
     * <p>原版 {@code LiquidBlock.onPlace} 无条件 {@code scheduleTick}（只挡了
     * {@code FluidInteractionRegistry.canInteract}），于是我们每写一格水就换来下一 tick 一次
     * {@code FlowingFluid.tick}，那一 tick 又会把该格 {@code postCell} 回来 ⇒ 引擎永远不空闲
     * （实机日志 {@code settled=12/16} 就是它）。
     *
     * <p>我们自己的求解器负责水位推进，不依赖原版流体 tick；外部真实流体 tick
     * （玩家倒水、原版源头流动）仍然照常走 {@code FlowingFluidTickMixin}。
     */
    @Inject(method = "onPlace", at = @At("HEAD"), cancellable = true)
    private void waterphysics$skipVanillaFluidTickOnProjection(final BlockState state, final Level level,
                                                               final BlockPos pos, final BlockState oldState,
                                                               final boolean isMoving, final CallbackInfo ci) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;     // 关掉总开关就完全不插手，与 updateShape 的门保持一致
        }
        if (FluidApplier.isApplying()) {
            ci.cancel();
        }
    }
}
