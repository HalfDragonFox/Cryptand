/**
 * ===== 发电机/电动机最大转速可配置 =====
 *
 * PowerGrid 转子最大转速（RotorBehaviour.getMaxRotationSpeed）原版读取
 * rotorRPMMax（默认 256 RPM）。本 mixin 覆盖为 Cryptand 配置
 * generatorMotorMaxRpm（默认 16384），可在 cryptand-common.toml 调整。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour",
       remap = false)
public abstract class RotorBehaviourAcMixin {

    /** 覆盖最大转速读取：返回 Cryptand 配置值（默认 16384） */
    @Inject(method = "getMaxRotationSpeed", at = @At("HEAD"), cancellable = true)
    private static void cryptand$maxRpm(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ConfigPowerGrid.GENERATOR_MOTOR_MAX_RPM.get());
    }
}
