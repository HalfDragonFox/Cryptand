/**
 * ===== 电风扇（ElectricFan）改为【电流驱动转动】（2026-08-13 用户要求） =====
 *
 * 原版 getSpeed() = motor.current() × 64，其中 current() = potentialDifference ×
 * conductance（PowerGrid 节点电压差 × 电导）。当 Cryptand 网络重建/打掉负载时
 * 节点电压回写可能漏 → 一端 0V 一端有电压 → 伪电流大 → 开路端子接风扇误启动。
 *
 * 本 mixin 在 Cryptand 模式下完全接管 getSpeed()：用 Cryptand 引擎求解的设备
 * 端子电压（2026-08-15 端子即接入模型：直读引擎端子测试点 TerminalElement，
 * TerminalRecorder 每次求解回填电压+频率）计算电流：
 *   I = |Va - Vb| / |Z|，Z = R + jωL（motor 绕组阻抗）。
 * 开路（无闭合回路）→ MNA 两端等电位 → I=0 → 风扇不启动；正常闭合回路 → I 正常。
 * 引擎电压不受 PowerGrid 回写失效影响；网络清零时端子测试点失效 → valid=false
 * → 不转。
 * 2026-08-22 电流方向：I = (Va − Vb)/|Z|（有向）——符号 = 电流方向，speed 为负
 * 时 Create 反向转动（正转/反转随接线方向），速度幅度 = |I|×64。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.patryk3211.powergrid.electricity.fan.ElectricFanBlockEntity",
       remap = false)
public abstract class FanSpeedMixin {

    @Inject(method = "getSpeed", at = @At("HEAD"), cancellable = true)
    private void cryptand$fanSpeedByEngineCurrent(CallbackInfoReturnable<Float> cir) {
        try {
            if (!ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) return; // 原版行为
            net.minecraft.core.BlockPos pos =
                    ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this)
                            .getBlockPos();
            // 2026-08-15 端子即接入模型：直读引擎端子测试点（每次求解回填），
            // 不再经 DEVICE_TERMINAL_V 中转。未求解/失效 → 不转。
            TerminalElement ta = TerminalRegistry.get(pos, 0);
            TerminalElement tb = TerminalRegistry.get(pos, 1);
            if (ta == null || tb == null || !ta.valid() || !tb.valid()) {
                cir.setReturnValue(0.0f);
                return;
            }
            // 2026-08-22 用户需求【供电判断走电流】：直接用后台求解写入的组装器
            // 设备电流缓存（= 电机内部绕组/电阻流过电流）。符号 = 电流方向 → 正/反转
            double i = com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCurrent
                    .read(pos);
            double speed = i * 64.0;
            if (Math.abs(speed) < 1.0) speed = 0.0; // 原版死区
            if (speed > 256.0) speed = 256.0;
            if (speed < -256.0) speed = -256.0;
            cir.setReturnValue((float) speed);
        } catch (Throwable ignored) {
        }
    }
}
