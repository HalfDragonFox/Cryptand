package com.hdf.cryptand.neoforge.powergrid.mixin;

import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== 禁用原版电压源写入（2026-08-12 用户要求：禁用原版算法） =====
 *
 * VoltageSourceCoupling.getVoltage() 直接返回原版 setVoltage() 设置的字段
 * （不经过网络求解）。Cryptand 时域求解禁用后，原版设备/电池/太阳能/创造性源
 * 仍每 tick 调用 setVoltage() → 端子 getVoltage() 显示原版电压 → 而 Cryptand
 * 未写回的对面端子 0V → 导线虚假大电流烧线（[WireBurn] 实锤 AC源 v=5 vs 0）。
 *
 * Cryptand 相量求解接管（ENABLE_CRYPTAND_SOLVER=true）：禁用 setVoltage →
 * 端子电压字段保持 0 → 无压差不烧。源电压由 Cryptand 引擎的 AcVoltageSource/
 * DcVoltageSource 元件建模并回写端子。
 */
@Mixin(value = VoltageSourceCoupling.class, remap = false)
public abstract class VoltageSourceCouplingNoWriteMixin {

    @Inject(method = "setVoltage", at = @At("HEAD"), cancellable = true)
    private void cryptand$noVanillaSetVoltage(double voltage, CallbackInfo ci) {
        if (com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) {
            ci.cancel();
        }
    }
}
