package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ===== 禁用原版导线烧毁算法（2026-08-12 用户要求：禁用原版算法） =====
 *
 * PowerGrid 原版导线烧毁链路：
 *   BaseWireEntity.temperatureUpdate()（I²R 能量 → 温度 → OVERHEAT_TICKS ≥ 2）
 *   → isOverheated() = true → tick() 里 dropWire() + 粒子 + discard() 烧毁。
 *
 * Cryptand 相量求解接管时（ENABLE_CRYPTAND_SOLVER=true）：
 *   原版 I²R 温度烧毁在时域禁用下会因"未写回节点 0V vs 源写电压"产生虚假
 *   大电流误烧（[WireBurn] 实锤 833A/11785A → 烧 → 重建 → 死循环）。
 *   导线温度由 Cryptand 段温度模型（WireThermalStore）接管 → 全部导线
 *   isOverheated() 恒 false（原版烧毁禁用）。
 *
 * 2026-08-13：取消【金导线额外保护】（Cryptand 关闭时金导线也永不烧毁的
 * 调试分支）——金导线恢复原版行为（可正常过热烧毁）。只保留 Cryptand 模式
 * 下全部导线不烧毁（WireThermalStore 接管）。
 */
@Mixin(value = BaseWireEntity.class, remap = false)
public abstract class GoldenWireNoBurnMixin {

    /** Cryptand 模式：全部导线永不过热/烧毁（原版烧毁算法禁用，温度由
     *  WireThermalStore 接管）。Cryptand 关闭 → 原版行为（金导线也可烧毁）。 */
    @Inject(method = "isOverheated", at = @At("HEAD"), cancellable = true)
    private void cryptand$noVanillaWireBurn(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ConfigCircuit.ENABLE_CRYPTAND_SOLVER.get()) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
