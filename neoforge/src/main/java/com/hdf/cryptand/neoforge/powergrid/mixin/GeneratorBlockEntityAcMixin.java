/**
 * ===== 发电机线圈交流发热 =====
 *
 * 线圈（GeneratorBlockEntity）是发电机的绕组本体。真实物理：
 *   - 绕组铜耗 I²R（一直存在）
 *   - 交流输入时铁耗随频率增大（涡流 ∝ f²，磁滞 ∝ f）→ 频率越高发热越厉害
 *
 * 原版 ElectricKineticBlockEntity.electricalTick() 为空实现，applyPower 未触发
 * → 线圈从不发热。此 mixin 在 tick 末尾按【网络频率】补上频率发热：
 *   - 频率 < 阈值（motorMaxDriveFrequencyHz，默认 3Hz）→ 跳过额外发热计算（省性能）
 *   - 频率 ≥ 阈值 → heat = I²R（绕组）+ 0.005·f² + 0.5·f（铁耗）
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CommutatorStateHolder;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.base.GeneratorBlockEntity",
       remap = false)
public abstract class GeneratorBlockEntityAcMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$coilAcHeat(CallbackInfo ci) {
        try {
            Object self = this;
            Object levelObj = reflectField(self, "level");
            if (!(levelObj instanceof Level level) || level.isClientSide) return;

            Object thermal = reflectField(self, "thermalBehaviour");
            if (!(thermal instanceof ThermalBehaviour tb)) return;

            Object posObj = reflectField(self, "worldPosition");
            if (!(posObj instanceof net.minecraft.core.BlockPos pos)) return;

            Object eb = reflectField(self, "electricBehaviour");
            if (!(eb instanceof ElectricBehaviour beh)) return;

            // 网络频率（网络级，禁止 BFS）
            double freq = 0;
            for (int t = 0; t < 2; t++) {
                try {
                    OwnedFloatingNode node = beh.getTerminal(t);
                    if (node == null) continue;
                    ElectricalNetwork net = node.getNetwork();
                    if (net == null) continue;
                    freq = MultimeterDebug.getNetworkFrequencyHz(net);
                    if (freq > 0) break;
                } catch (Throwable ignored) {
                }
            }

            double threshold = ConfigLoad.MOTOR_MAX_DRIVE_FREQUENCY_HZ.get();
            boolean ac = freq >= threshold;
            // 线圈发热（真实电机物理，与励磁绕组一致）：
            //   匹配 AC+AC（同步电机）：
            //     电枢铜耗     = I_arm²·R（堵转电流最大 → 最热）
            //     铁耗         = k_h·f_exc + k_e·f_exc²（磁滞∝f + 涡流∝f²）
            //     风阻/机械损耗 = k_w·f_rotor²（高转速 → 热）
            //     转差损耗     = k_s·(f_arm-f_exc)²（失步 → 热）
            //   不匹配     → 换向器输入功率全转为热（停转无机械输出）
            double heat = 0;
            // ⚠ 完全自定义相量核心（2026-08-10）：【不读 PowerGrid 时域任何
            //   电压/电流】（windingCurrent/potentialDifference 5kHz 欠采样失真）。
            //   线圈电压从相量核心读（频域精确复数稳态求解，无欠采样）；
            //   电流 = V / |Z|（Z=√(R²+(2πfL)²)）物理有效电流。相量不可用 → 0。
            double coilI = 0;
            try {
                double vPeak = PhasorEngine.voltageAcross(level, pos, freq, level.getGameTime());
                if (vPeak > 0) {
                    double vWireRms = vPeak / Math.sqrt(2.0); // 峰值 → RMS
                    double Lphys = ConfigLoad.COIL_EFFECTIVE_INDUCTANCE_H.get();
                    double Rdc = ConfigLoad.COIL_DC_RESISTANCE_OHM.get();
                    if (Lphys > 0 && Rdc > 0) {
                        double fEff = Math.max(freq, threshold);
                        double xl = 2 * Math.PI * fEff * Lphys;
                        double z = Math.sqrt(Rdc * Rdc + xl * xl);
                        if (z > 0) coilI = vWireRms / z; // 物理有效电流（高频感抗限流）
                    }
                }
            } catch (Throwable ignored) {
            }
            // 磁通 B（磁饱和，与 PowerGrid 磁场公式一致：1.5·tanh(I/2)+0.05·I），
            // 封顶 2.0 → 铁耗 ∝ f·B² 有硬上限，异常大电流也不会按 I² 无限爆炸
            double b = Math.min(2.0, 1.5 * Math.tanh(coilI / 2.0) + 0.05 * coilI);
            double excitation = b * b; // B²
            CommutatorStateHolder comm = MultimeterDebug.nearbyCommutator(level, pos);
            if (comm != null) {
                double armFreq = comm.cryptand$getArmFreq();
                double excFreq = comm.cryptand$getExcFreq();
                float power = comm.cryptand$getPower();
                float armCurrent = comm.cryptand$getCurrent();
                double rotorFreq = comm.cryptand$getRotorFreq();
                boolean armAc = armFreq >= threshold;
                boolean excAc = excFreq >= threshold;
                if (armAc && excAc) {
                    // 电枢铜耗 I²R（R=0.5Ω 近似；堵转电流最大 → 最热）
                    double i = armCurrent;
                    heat = i * i * 0.5;
                    // 铁耗（磁滞∝f + 涡流∝f²，基于励磁频率 × 励磁电流因子）。
                    // 高频磁通受磁芯饱和/趋肤限制，有效频率封顶 500Hz——否则
                    // 5kHz 时 0.004×f²=100000 瞬间烧穿热行为直接爆炸。
                    // 系数 0.15/0.0016（2026-08-09 校准）：500Hz 封顶处 475/B²，
                    // 与 WindingBlockEntityAcMixin 一致，避免上电秒炸。
                    double fIron = Math.min(excFreq, 500.0);
                    heat += (0.15 * fIron + 0.0016 * fIron * fIron) * excitation;
                    // 风阻/机械损耗（高转速 → 热，∝ω²）
                    heat += 0.003 * rotorFreq * rotorFreq;
                    // 转差损耗（失步 → 热，∝(f_arm-f_exc)²；无电流无转矩 → 无转差热）
                    double dF = Math.abs(armFreq - excFreq);
                    heat += 0.05 * dF * dF * excitation;
                } else if (armAc != excAc) {
                    heat = Math.max(0, power);
                }
            } else if (ac) {
                // 无配套换向器：仅绕组自身铁耗（× 电流因子，频率封顶）+ 铜耗
                // 系数 0.15/0.0016 与 Winding 一致（500Hz 封顶处 475/B²）。
                // 线圈电压/电流已由上方相量核心给出（物理有效，无时域失真）。
                double fIron = Math.min(freq, 500.0);
                heat = (0.15 * fIron + 0.0016 * fIron * fIron) * excitation;
            }
            if (heat > 0) {
                Float windingR = (Float) reflectField(self, "windingResistance");
                double r = (windingR == null || windingR <= 0) ? ConfigLoad.COIL_DC_RESISTANCE_OHM.get() : windingR;
                heat += coilI * coilI * r; // 绕组铜耗（随电流²）
                // 温升速率钳制（同 Winding）：失真数据 → 渐进温升而非瞬间爆炸
                double maxRise = ConfigLoad.COIL_MAX_TEMP_RISE_PER_TICK.get();
                if (maxRise > 0) {
                    Object massObj = reflectField(tb, "thermalMass");
                    double thermalMass = (massObj instanceof Number m) ? m.doubleValue() : 1.0;
                    if (thermalMass > 0) {
                        double heatLimit = maxRise * 20.0 * thermalMass;
                        if (heat > heatLimit) heat = heatLimit;
                    }
                }
                tb.applyTickPower(heat);
            }
        } catch (Throwable t) {
            // 永不崩溃
        }
    }

    @Unique
    private static Object reflectField(Object target, String name) {
        if (target == null) return null;
        Field f = null;
        Class<?> c = target.getClass();
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
