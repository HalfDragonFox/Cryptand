package com.hdf.cryptand.neoforge.powergrid.mixin;

import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== 禁用原版电流源写入（2026-08-12 用户要求：禁用原版算法） =====
 *
 * CurrentSourceNode.getCurrent() 直接返回原版 setCurrent() 设置的字段。
 * Cryptand 时域求解禁用后，原版电流源（创造性源/交流电流源）仍设置电流 →
 * 与原版网络求解冲突/端子异常。Cryptand 相量求解接管时禁用 setCurrent →
 * 电流源由 Cryptand 引擎 CurrentSource 元件建模。
 */
@Mixin(value = CurrentSourceNode.class, remap = false)
public abstract class CurrentSourceNodeNoWriteMixin {

    @Inject(method = "setCurrent", at = @At("HEAD"), cancellable = true)
    private void cryptand$noVanillaSetCurrent(double current, CallbackInfo ci) {
        if (com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) {
            ci.cancel();
        }
    }
}
