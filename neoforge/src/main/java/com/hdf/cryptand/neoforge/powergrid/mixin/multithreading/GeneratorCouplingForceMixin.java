/**
 * ===== 修复：GeneratorCoupling.postUpperSolve 力丢失 =====
 *
 * 问题：postUpperSolve 里 if(isConverged()) 才施加力。异步化下网络拓扑反复
 * 变化触发 warmUp，converged 长期为 false → 扭矩永不施加 → 转速持续衰减。
 *
 * 修复：取消原版 postUpperSolve 的力施加（避免双重施加），力统一由
 * WorldNetworksMixin.cryptand$doComputeRound() 在求解后强制施加（绕过 isConverged）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.multithreading;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling",
       remap = false)
public abstract class GeneratorCouplingForceMixin {

    @Inject(method = "postUpperSolve", at = @At("HEAD"), cancellable = true)
    private void cryptand$skipOriginalForce(CallbackInfo ci) {
        // 力统一由后台 doComputeRound 施加，这里取消原版避免双重施加
        ci.cancel();
    }
}
