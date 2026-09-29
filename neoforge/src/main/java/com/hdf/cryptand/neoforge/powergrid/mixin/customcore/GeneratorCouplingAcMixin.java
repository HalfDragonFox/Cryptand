/**
 * ===== 换向器电机交流驱动限制 =====
 *
 * 换向器电机（GeneratorCoupling，他励/永磁直流电机）要求输入稳定电流（直流）。
 * 输入交流（频率 ≥ 配置 motorMaxDriveFrequencyHz，默认 3Hz）时，平均转矩为 0，
 * 电机驱动不了——真实物理：T(t) = KΦ·I(t) ∝ sin(ωt)，半个周期正半个周期负，净转矩为 0。
 *
 * 实现：替换原版 postUpperSolve 的转矩施加，按网络频率衰减：
 *   - 频率 < 阈值 → 正常驱动（decay = 1）
 *   - 阈值 ≤ 频率 < 2×阈值 → 线性过渡衰减到 0
 *   - 频率 ≥ 2×阈值 → 完全驱动不了（decay = 0）
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.customcore;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.state.CommutatorStateHolder;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(targets = "org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling",
       remap = false)
public abstract class GeneratorCouplingAcMixin {

    /** 临时诊断计数器（电动机模式节流打印） */
    @Unique private static int cryptand$diagCounter;

    @Inject(method = "postUpperSolve", at = @At("HEAD"), cancellable = true)
    private void cryptand$postUpperSolveAc(CallbackInfo ci) {
        try {
            Object self = this;
            GeneratorCoupling gc = (GeneratorCoupling) (Object) this;
            // 注意：不检查 isConverged！异步化（WorldNetworksMixin）下网络拓扑反复
            // 变化触发 warmUp，converged 长期为 false → 若在此检查会【永不施力】，
            // 电动机模式（靠转矩驱动）将无法转动。原版该检查已被我们替换接管。

            // 网络频率（网络级，禁止 BFS）
            double freq = 0;
            Object network = reflectField(self, "network");
            if (network instanceof ElectricalNetwork en) {
                freq = MultimeterDebug.getNetworkFrequencyHz(en);
            }

            double threshold = ConfigPowerGrid.MOTOR_MAX_DRIVE_FREQUENCY_HZ.get();
            boolean armAc = freq >= threshold;      // 电枢是否交流
            boolean acExc = isAcExcitation(self);   // 本机励磁是否交流（读换向器实例字段）
            // 发电机模式 = 转子被外部机械驱动（GeneratorClutch 连接 Create 动力，
            // RotorBehaviour.forceSupplier 非空，PID 控制目标转速）。
            // 这是比 isGenerating(I·V>0) 可靠的信号：空载发电机电流≈0 也判对，切换瞬间稳定。
            boolean generating = isExternallyDriven(self);

            Object rotor = reflectField(self, "rotor");
            if (rotor instanceof IRotor r) {
                Float field = (Float) reflectField(self, "field");
                float f = field == null ? 0f : field;
                double current = gc.getCurrent();
                int multiTick = 1;
                if (network instanceof ElectricalNetwork en2) multiTick = en2.getMultiTick();

                double force = 0;
                if (generating) {
                    // ===== 发电机模式：转子由外力驱动，输出频率 = 转子转速频率 =====
                    // 转子转速由外部机械（Create 动力 + PID）决定，EMF=KΦω 由 preSolve 设置。
                    // 电枢电流产生【制动】（发电负载反应，机械能→电能），原版 T=KΦI。
                    // 绝不施加转差驱动力——否则会把发电机转子拖向网络同步频率 → 失控。
                    force = f * current / multiTick;
                } else {
                    // ===== 电动机模式 =====
                    // 现实模型闭环——负载反馈：Create 网络实际速度（反映负载拖慢/外部拖动）
                    // 与转子速度之差 → 反作用力。没有这个闭环，电动机转速只由电磁力+摩擦
                    // 决定，不受 Create 网络负载影响（不现实）。
                    //   空载：网络速度≈转子速度 → 无反馈
                    //   带载：网络被拖慢 → 反向力 → 转子减速（转速受外部环境影响）
                    //   外部反向拖动：网络反转 → 大反向力 → 转子反转 → EMF 反向
                    //     → 电流反向 → 再生制动 + I²R 发热
                    double loadK = ConfigPowerGrid.MOTOR_LOAD_COUPLING.get();
                    if (loadK > 0) {
                        double netSpeed = 0;
                        try {
                            Object rotorObj = reflectField(self, "rotor");
                            Object supplier = reflectField(rotorObj, "forceSupplier");
                            // 离合器（GeneratorClutchBlockEntity）是 Create 动能块，
                            // getSpeed() 返回网络实际速度（Create 速度单位，RPM）
                            if (supplier instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kbe) {
                                netSpeed = kbe.getSpeed();
                            }
                        } catch (Throwable ignored) {
                        }
                        // Create 速度单位(RPM) → rad/s；转子速度已是 rad/s
                        double netRadS = netSpeed * (2 * Math.PI / 60.0);
                        double rotorRadS = r.getAngularVelocity();
                        force += loadK * (netRadS - rotorRadS);
                    }

                    if (armAc && acExc) {
                        // ===== 电动机模式：交流励磁 + 交流电枢 = 可逆同步电机（转差模型） =====
                        // 同步频率 = 网络频率（旋转磁场）；转差 s = (f_sync - f_rotor)/f_sync
                        //   亚同步(s>0)：驱动（启动）
                        //   同步(s≈0)：转矩=0，锁定（防失控）
                        //   超同步(s<0)：制动
                        // 注意：曾尝试过载停转（乘电压因子），但 PowerGrid 的电压信号
                        // （EMF/节点电位）在电动机模式不可靠 → 启动即锁死，已回退纯电流驱动。
                        double fSync = freq;   // 网络频率 = 同步频率
                        double fRotor = Math.abs(r.getAngularVelocityRadians()) / (2 * Math.PI);
                        if (fSync > 0) {
                            double slip = (fSync - fRotor) / fSync;
                            slip = Math.max(-1.0, Math.min(1.0, slip));
                            force += f * current * slip * 4.0 / multiTick;
                        }
                    } else if (armAc == acExc) {
                        // ===== 直流励磁 + 直流电枢 = 直流电机：原版 T=KΦI（可逆） =====
                        force += f * current / multiTick;
                    }
                }

                // 临时诊断：节流打印关键值与最终施力（每 100 次）——定位"不转"用
                if (++cryptand$diagCounter % 100 == 0) {
                    double fRotorD = Math.abs(r.getAngularVelocityRadians()) / (2 * Math.PI);
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[MotorDiag] freq={} armAc={} acExc={} generating={} field={} current={} fRotor={}Hz multiTick={} force={}",
                            String.format("%.2f", freq), armAc, acExc, generating,
                            String.format("%.3f", f), String.format("%.3f", current),
                            String.format("%.2f", fRotorD), multiTick,
                            String.format("%.4f", force));
                }

                // 不匹配（交流励磁+直流电枢 / 直流励磁+交流电枢）→ 停转，力=0
                if (Math.abs(force) > 0.0001f) {
                    r.applyTickForce((float) force);
                }
            }
            ci.cancel();
        } catch (Throwable t) {
            // 失败 → 不 cancel，交给原版 postUpperSolve
        }
    }

    /** 是否发电机模式：检查发电机离合器（forceSupplier）的模式
     *  PowerGrid 发电机离合器（GeneratorClutchBlockEntity）有 GENERATOR / MOTOR 两种模式：
     *   - GENERATOR：转子被 Create 动力驱动（PID 保持目标转速）→ 发电
     *   - MOTOR：无外力（sourceForce 返回 0）→ 电枢驱动转子 → 电动
     *  注意：不能只看 forceSupplier != null（离合器构造器总是调用 forceSource(this)）！
     *  必须读取离合器的 mode 字段判断。 */
    @Unique
    private static boolean isExternallyDriven(Object self) {
        try {
            Object rotor = reflectField(self, "rotor");
            if (rotor == null) return false;
            Object supplier = reflectField(rotor, "forceSupplier");
            if (supplier == null) return false;
            // 反射读取离合器的 mode 字段（ClutchMode 枚举）
            Field f = null;
            Class<?> c = supplier.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("mode");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) return false;
            f.setAccessible(true);
            Object mode = f.get(supplier);
            if (mode == null) return false;
            // mode.name() == "GENERATOR" → 发电；"MOTOR" → 电动
            return "GENERATOR".equals(mode.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 本机励磁是否交流 + 励磁频率：rotor → blockEntity(换向器) → 强转接口读实例字段 */
    @Unique
    private static boolean isAcExcitation(Object self) {
        try {
            Object rotor = reflectField(self, "rotor");
            if (rotor == null) return false;
            Object be = reflectField(rotor, "blockEntity");
            if (be instanceof CommutatorStateHolder h) {
                return h.cryptand$isAcExcited();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 本机励磁频率（Hz）：rotor → blockEntity(换向器) → 强转接口读实例字段 */
    @Unique
    private static double excFreq(Object self) {
        try {
            Object rotor = reflectField(self, "rotor");
            if (rotor == null) return 0;
            Object be = reflectField(rotor, "blockEntity");
            if (be instanceof CommutatorStateHolder h) {
                return h.cryptand$getExcFreq();
            }
        } catch (Throwable ignored) {
        }
        return 0;
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
