package com.hdf.cryptand.neoforge.waterphysics.mixin;

import com.hdf.cryptand.neoforge.waterphysics.FluidApplier;
import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsBridge;
import com.hdf.cryptand.neoforge.waterphysics.WaterPhysicsModule;
import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管原版流体推进（1.21.1 的目标方法）。
 *
 * <p>这是整套设计的<b>前提</b>：只要主线程还在推进流体，worker 的快照就永远和世界打架。
 * 取消原版 tick 之后，流体推进全部由 waterphysics 的求解器负责；主线程只做
 * 按预算采集「这一格 + 它的 6 个邻居」并写回（单格粒度）。
 *
 * <p>这一格坐标就是「实际水」的来源：/setblock water、倒桶、流水都会走到原版
 * {@code FlowingFluid.tick}，因此都会被这里点名。
 *
 * <p>客户端不接管（客户端靠服务端同步水位渲染）。
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void waterphysics$takeOverTick(final Level level, final BlockPos pos,
                                           final FluidState state, final CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;
        }
        // ★ 只接管「这一格我们写得回去」的流体：门与写回必须同源。
        //   原来这里问的是 FluidTags.WATER，而写回只在 FluidApplier.stateFor 处理得了的方块上成立
        //   （水方块 / 空气 / 带 WATERLOGGED 的含水方块）。标签可被数据包与其它 mod 改写，
        //   一旦「标签说是水、这一格的方块却装不下水位」，下面 cancel 掉的这一格就再没人管
        //   ⇒ 流体永久冻结。所以门改成直接问写回器：这一格你处理得了吗。
        //   岩浆（LavaFluid）也走这个方法，同样被这条判定挡在外面（落块不是水容器）。
        //   ★ 读的是「这一格现在的方块」，不是排 tick 时传进来的 state：tick 是过几 tick 之后才被调用的，
        //   期间方块可能已被换掉；只有现在也装得下水位，取消原版推进才是安全的。
        final BlockState here = serverLevel.getBlockState(pos);
        if (!FluidApplier.canApply(here)) {
            return;
        }
        // ★ fail-safe：拿不到桥接器（模块 init 失败 / 维度未接管）就交还原版。
        //   绝不能无条件 cancel —— 否则原版 tick 被掐、又没人接管，整世界的水会冻住。
        //   latest.log 2026-09-27 21:50 正是这种情况：子包 init 抛异常 ⇒ 水全静止。
        final WaterPhysicsBridge bridge = WaterPhysicsModule.bridgeOrNull(serverLevel);
        if (bridge == null) {
            return;
        }
        // ★ 走 postCellTick（只点名、不推进世代）：原版 tick 已被 cancel，世界没变，
        //   这里只是「这一格水该重新考虑」。用 postCell 会把每 tick 的一次原版 tick
        //   当成外部变更 ⇒ 在途那一轮每 tick 作废 ⇒ 水永远算不完（水源静止回归）。
        bridge.postCellTick(pos.getX(), pos.getY(), pos.getZ());
        ci.cancel();
    }
}
