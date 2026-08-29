/**
 * ===== 修复：异步求解下 isConverged() 长期为 false，导致温度/磁场/保险丝等失效 =====
 *
 * 原版 AbstractElectricWire.isConverged()：
 *   if(network == null) return false;
 *   return network.isConverged();   // 求解器收敛标志 mna.isConverged()
 *
 * 异步化后，求解在后台线程运行。主线程 BE tick（electricalTick、温度加热、磁场、
 * 保险丝、电线过热）读取 isConverged() 时：
 *   - 若网络拓扑刚变化（warmUp）或求解器未收敛 → 长期 false
 *   - 导致 windingCurrent() 返回 0（线圈不发热）、fieldCalc() 不更新磁场、
 *     applyWirePower() 不加热、保险丝/继电器不动作 —— 与之前绕过的
 *     GeneratorCoupling.isConverged 是同一根源。
 *
 * 修复：后台线程持续求解，网络存在即认为当前值有效（与原版"每 tick 求解后
 * 值即有效"的语义等效）。@Inject 覆盖 isConverged() 返回值。
 * 注：SwitchedWire / TransmissionLinePart 覆写了 isConverged()，不受影响。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.multithreading;

import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractElectricWire.class, remap = false)
public abstract class AbstractElectricWireMixin {

    @Inject(method = "isConverged", at = @At("HEAD"), cancellable = true)
    private void cryptand$forceConverged(CallbackInfoReturnable<Boolean> cir) {
        AbstractElectricWire self = (AbstractElectricWire) (Object) this;
        // 网络存在即视为当前求解值有效（后台线程持续求解）
        cir.setReturnValue(self.getNetwork() != null);
    }
}
