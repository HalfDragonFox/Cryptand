/**
 * ===== 励磁绕组：直流励磁 / 交流励磁检测 + 频率发热（状态存实例，不走全局注册表） =====
 *
 * 励磁绕组（WindingBlockEntity）用 LRSeriesWire（电感+电阻）建模，磁场 = f(励磁电流)。
 *   - 直流励磁（励磁绕组接直流/低频）→ 磁场恒定 → 换向器电枢必须直流驱动
 *   - 交流励磁（励磁绕组接交流）→ 磁场随交流振荡 → 电枢交流可同步驱动（T∝sin²恒正）
 *
 * 本 mixin 在 tick 里检测励磁绕组所在网络频率（网络级，禁止 BFS）：
 *   - 频率 ≥ motorMaxDriveFrequencyHz → 交流励磁（存入本实例 WindingStateHolder）
 *   - 频率 < 阈值 → 直流励磁
 * 并补励磁线圈频率发热（f < 阈值跳过，省性能）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine;
import com.hdf.cryptand.neoforge.powergrid.adapter.WindingStateHolder;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import net.minecraft.core.BlockPos;
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

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.winding.WindingBlockEntity",
       remap = false)
public abstract class WindingBlockEntityAcMixin implements WindingStateHolder {

    // ===== 实例状态字段（声明不初始化，规避 Architectury 构造器赋值破坏；JVM 默认值） =====
    @Unique private double cryptand$freq;
    @Unique private boolean cryptand$ac;

    @Override public double cryptand$getFreq() { return cryptand$freq; }
    @Override public boolean cryptand$isAc() { return cryptand$ac; }

    @Override
    public void cryptand$updateFreq(double freq, boolean ac) {
        this.cryptand$freq = freq;
        this.cryptand$ac = ac;
    }

    /**
     * 完全替换原版线圈温度算法（2026-08-09）：
     * 原版 WindingBlockEntity.electricalTick() 用【失真电流】的 I²R
     * （windingCurrent()² × resistance()）直接加热 thermalBehaviour——
     * PowerGrid 时域电感模型无频率感抗，5kHz 下电流失真巨大（实测 99A），
     * 导致 I²R = 99²×R 瞬间烧穿（日志：我们只施加 1.1 热，温度却被原版推到 2570°C）。
     * 本注入【取消原版 I²R 加热调用】——线圈温度完全由本 mixin 的物理模型
     * （cryptand$windingAc 里频率感抗修正的发热）控制，散热/过热爆炸保留原版。
     * 仅 cancel applyTickPower 调用，保留方法内 setUnsaved()。
     */
    @Inject(method = "electricalTick",
            at = @At(value = "INVOKE",
                     target = "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;applyTickPower(D)V"),
            cancellable = true)
    private void cryptand$cancelVanillaHeating(CallbackInfo ci) {
        ci.cancel(); // 禁用原版失真 I²R 加热（发热完全由自定义模型接管）
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$windingAc(CallbackInfo ci) {
        try {
            Object self = this;
            Object levelObj = reflectField(self, "level");
            if (!(levelObj instanceof Level level) || level.isClientSide) return;
            Object posObj = reflectField(self, "worldPosition");
            if (!(posObj instanceof BlockPos pos)) return;

            // 励磁绕组网络频率（网络级，禁止 BFS）
            double freq = 0;
            Object eb = reflectField(self, "electricBehaviour");
            if (eb instanceof ElectricBehaviour beh) {
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
            }

            double threshold = ConfigLoad.MOTOR_MAX_DRIVE_FREQUENCY_HZ.get();
            boolean ac = freq >= threshold;
            // 励磁状态存本实例（换向器经 level 本地查询读取）
            cryptand$updateFreq(freq, ac);

            // 线圈发热（真实电机物理）：
            //   匹配 AC+AC（同步电机）：
            //     电枢铜耗     = I_arm²·R（堵转电流最大 → 最热）
            //     铁耗         = k_h·f_exc + k_e·f_exc²（磁滞∝f + 涡流∝f²）
            //     风阻/机械损耗 = k_w·f_rotor²（高转速 → 热）
            //     转差损耗     = k_s·(f_arm-f_exc)²（失步 → 热）
            // 励磁线圈发热【只基于本线圈】：
            //   铜耗 = I²R（本线圈电流） + 铁耗（本线圈励磁频率 × 电流因子）
            // 不再用附近换向器的状态（否则周围未连接/悬空的线圈也会被换向器的
            // 电枢电流/转速误加热）。电枢铜耗/转差/风阻已移到换向器侧
            // （CommutatorBlockEntityAcMixin，施加到换向器 thermalBehaviour）。
            double heat = 0;
            // 励磁电流（磁通 ∝ I）。开路/单线无回路 → 无电流 → 无磁通 → 无铁耗！
            // ⚠ 频率感抗修正（2026-08-09 根因修复）：
            //   PowerGrid 的 LRSeriesWire 是【时域离散化电感】G=1/(R+2L/Δt)，
            //   完全没有频率感抗 XL=2πfL → 5kHz 下把线圈当近似纯电阻，
            //   电流物理失真巨大（日志实测 freq=5000 时 20.6A，freq=0 时 99.3A）。
            //   真实物理：Z=sqrt(R²+(2πfL)²)，5kHz 感抗数千欧 → 电流应 <0.1A。
            //   用线圈电压 V / 物理阻抗 Z 重算有效电流 → 高频电流物理正确变小。
            //   电感用配置 COIL_EFFECTIVE_INDUCTANCE_H（真实励磁绕组 0.1~1H，
            //   PowerGrid 实际电感仅 0.001H 太小），直流电阻用 COIL_DC_RESISTANCE_OHM。
            Object coilWire = reflectField(self, "coilWire");
            double vWire = 0, Lcoil = 0, Rcoil = 0;
            if (coilWire != null) {
                // coilWire 仅用于读电感/电阻（调试日志），【不读】其时域电压/电流
                Object lo = reflectField(coilWire, "inductance");
                if (lo instanceof Number n) Lcoil = n.doubleValue();
                Object ro = reflectField(coilWire, "resistance");
                if (ro instanceof Number n) Rcoil = n.doubleValue();
            }
            // ⚠ 完全自定义相量核心（2026-08-10）：【不读 PowerGrid 时域任何值】。
            //   PowerGrid 时域 LRSeriesWire 大步长(0.05s)欠采样，5kHz 下
            //   potentialDifference() 混叠到 120 万V（数值伪影，非物理）。
            //   线圈电压一律从相量核心读（频域精确复数稳态求解，无欠采样）；
            //   相量不可用（无网络/无回路/求解失败）→ vWire=0 → 无发热，
            //   【绝不回退时域失真值】。主/代理方块统一用主方块位置查询。
            Object mainF = reflectField(self, "mainBE");
            Object mainPosObj = (mainF == null) ? pos : reflectField(mainF, "worldPosition");
            if (mainPosObj instanceof BlockPos mainPos) {
                double vPeak = PhasorEngine.voltageAcross(level, mainPos, freq, level.getGameTime());
                if (vPeak > 0) vWire = vPeak / Math.sqrt(2.0); // 峰值 → RMS（匹配现有校准）
            }
            // 线圈电流：物理有效电流 = V / |Z|（Z=√(R²+(2πfL)²)），完全由相量
            // 电压 + 物理阻抗计算，不读 PowerGrid 时域 current()。
            double coilI = 0;
            double Lphys = ConfigLoad.COIL_EFFECTIVE_INDUCTANCE_H.get();
            double Rdc = ConfigLoad.COIL_DC_RESISTANCE_OHM.get();
            if (Lphys > 0 && Rdc > 0) {
                // 频率感抗：freq 用 max(实际频率, 最小 AC 判定频率)——防止 5kHz 源
                // 瞬态归零时被当直流短路（XL=0 → 只剩 5Ω 直流电阻限流仍会爆）
                double fEff = Math.max(freq, ConfigLoad.MOTOR_MAX_DRIVE_FREQUENCY_HZ.get());
                double xl = 2 * Math.PI * fEff * Lphys; // 频率感抗
                double z = Math.sqrt(Rdc * Rdc + xl * xl);
                if (z > 0) coilI = vWire / z; // 物理有效电流（相量电压/物理阻抗）
            }
            // 磁通 B（磁饱和）：真实物理中励磁绕组磁通由【电压】建立（V≈4.44·f·N·A·B），
            //   高频下即使电流被感抗压小（5kHz XL=15708Ω → I≈0.0046A），只要电压存在
            //   铁芯就有交变磁通 → 铁耗。因此 B 由【电压】决定（不看电流、不看频率
            //   衰减——5kHz 下 V/f 很小但真实铁耗由 V² 主导，涡流损耗 ∝ V² 与频率无关）。
            //   V 用 vWire（原始线圈电压），磁饱和封顶 2.0。
            //   这样：50Hz·230V → B≈1.19，铁耗≈66；50Hz·708V → B=2.0（饱和），
            //   铁耗≈130（实测 36°C 可测）；高压过载 → 渐进过热爆炸（统一钳制限速）。
            double b;
            if (ac) {
                double vMag = Math.max(0, vWire);
                double vRatio = vMag / 230.0; // 230V → 饱和附近
                b = Math.min(2.0, 1.5 * Math.tanh(vRatio) + 0.05 * vRatio);
            } else {
                b = 0; // 直流：无交变磁通 → 无铁耗
            }
            double excitation = b * b; // B²
            if (ac) {
                // 铁耗（磁滞∝f + 涡流∝f²）× 磁通因子；高频磁通受限封顶 500Hz。
                // 系数校准（2026-08-09）：原 0.3/0.004 在 500Hz 封顶处铁耗=1150/B²，
                // 乘 excitation 2.77 → 2770 热，稳态 204°C 超 175°C 过热阈值直接炸。
                // 下调到 0.15/0.0016 → 500Hz 处 475/B²，5kHz 满载（B²≈2.77）铁耗≈1316，
                // 稳态 ≈ 100°C（热但安全）；50Hz 正常电网铁耗≈32 几乎不热；
                // 加线圈/过载/电流翻倍才向 175°C 顶。磁饱和（B≤2）继续兜底大电流。
                double fIron = Math.min(freq, 500.0);
                heat = (0.15 * fIron + 0.0016 * fIron * fIron) * excitation;
            }
            if (heat > 0 || coilI > 0) {
                Object thermal = reflectField(self, "thermalBehaviour");
                if (thermal instanceof ThermalBehaviour tb) {
                    heat += coilI * coilI * (Rdc > 0 ? Rdc : 0.5); // 励磁绕组铜耗（本线圈电流）
                    // ⚠ 温升速率钳制（2026-08-09）：任何失真/过载参数都反馈为渐进
                    //   温升而非瞬间爆炸。热容 thermalMass≈1.5 时单 tick 温升 =
                    //   heat/20/thermalMass。钳制到 COIL_MAX_TEMP_RISE_PER_TICK，
                    //   即 heat ≤ maxRise×20×thermalMass（真实电机热惯性）。
                    if (heat > 0) {
                        double maxRise = ConfigLoad.COIL_MAX_TEMP_RISE_PER_TICK.get();
                        if (maxRise > 0) {
                            Object massObj = reflectField(tb, "thermalMass");
                            double thermalMass = (massObj instanceof Number m) ? m.doubleValue() : 1.0;
                            if (thermalMass > 0) {
                                double heatLimit = maxRise * 20.0 * thermalMass;
                                if (heat > heatLimit) heat = heatLimit;
                            }
                        }
                        // ⚠ 仅主方块发热 + 伪平均（2026-08-09 v2）：
                        //   整条线圈 N 段串联，总热容 = N×单段热容，总电阻 = N×单段电阻。
                        //   主方块（唯一持有 coilWire）算出的 heat 是【整条线圈总热】。
                        //   若全加给主方块 → 主方块温度 = 总热/单段热容（虚高 N 倍）；
                        //   正确做法：主方块只承受 heat/N（均分到每段），
                        //   代理方块【不发热】，每 tick 跟随主方块温度 → 任意段温度计
                        //   读数一致，且数值 = 整条线圈平均温度（物理正确）。
                        double partCount = cryptand$coilPartCount(self);
                        double heatPer = (partCount > 0) ? heat / partCount : heat;
                        tb.applyTickPower(heatPer);
                    }
                }
            }
            // 代理方块温度跟随主方块（2026-08-09）：代理无 coilWire 不发热，
            // 每 tick 把主方块温度写入本代理 thermalBehaviour —— 温度计贴任意段
            // 读数一致（伪平均）。主方块自身无操作。
            cryptand$syncProxyTemp(self, level);
            // 电阻温度系数：R(T) = R₀×(1 + α×(T-22))。发热→温度升→电阻升→电流受限
            // （真实电机热稳定负反馈：堵转/过载时电流不会无限增大）
            cryptand$applyResistanceTempCoef(self, level);
        } catch (Throwable t) {
            // 永不崩溃
        }
    }

    /**
     * 励磁绕组电阻随温度变化（电阻温度系数）。
     * 基准电阻用【固定值】COIL_DC_RESISTANCE_OHM（物理直流电阻），【不能】每次
     * 读 coilWire 当前 resistance 再乘 (1+αΔT)——那是复利累积，温度 87°C 时
     * R 从 0.45 指数爆到 7 亿（日志实证）。固定基准 + 单次温度修正才是正确的：
     *   R(T) = R_base×(1 + α×(T-22))，22 = ThermalBehaviour.BASE_TEMPERATURE。
     * R 只由当前温度决定（无记忆），温度回落电阻即回落。
     *
     * 关键：直接反射写 LRSeriesWire.resistance 字段，【不调用 setResistance/setLR】！
     * 因为 setLR 会触发 network.changeConductance → 网络导纳矩阵/拓扑变化 →
     * 服务端与客户端同步状态列表错位（"Buffer read overrun" → 连锁 NPE 崩溃）。
     * 直接改字段：每轮求解时 conductance()=1/(R+2L/Δt) 读新值，功能生效且不破坏网络。
     * 每 5 tick 更新一次（平滑，减少求解扰动）。
     */
    @Unique
    private static void cryptand$applyResistanceTempCoef(Object self, Level level) {
        try {
            double alpha = ConfigLoad.COIL_RESISTANCE_TEMP_COEFF.get();
            if (alpha <= 0) return;
            if (level == null || level.getGameTime() % 5 != 0) return; // 节流
            Object thermal = reflectField(self, "thermalBehaviour");
            if (!(thermal instanceof ThermalBehaviour tb)) return;
            Object tempObj = reflectField(tb, "temperature");
            if (!(tempObj instanceof Number temp)) return;
            Object wire = reflectField(self, "coilWire");
            if (wire == null) return;
            // 固定基准电阻（物理直流电阻，不随温度累积）
            double rBase = ConfigLoad.COIL_DC_RESISTANCE_OHM.get();
            if (!(rBase > 0)) return;
            double rNew = rBase * (1 + alpha * (temp.doubleValue() - 22.0));
            if (rNew > 0 && Double.isFinite(rNew)) {
                java.lang.reflect.Field rf = null;
                Class<?> c = wire.getClass();
                while (c != null && rf == null) {
                    try {
                        rf = c.getDeclaredField("resistance");
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
                if (rf != null) {
                    rf.setAccessible(true);
                    rf.setDouble(wire, rNew);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 励磁线圈电流（物理有效电流 = 相量电压 / 物理阻抗，见发热段；不再读时域） */

    /**
     * 整条线圈分段数（含主方块自身）。主方块发热平分给所有分段。
     * 反射读主方块 mainBE 的 collectedBEs（HashSet，含主自身）；读不到返回 1。
     */
    @Unique
    private static int cryptand$coilPartCount(Object self) {
        try {
            Object mainBe = reflectField(self, "mainBE");
            if (mainBe != null) {
                Object coll = reflectField(mainBe, "collectedBEs");
                if (coll instanceof java.util.Set<?> parts && !parts.isEmpty()) {
                    return parts.size();
                }
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** 反射读 ThermalBehaviour.temperature（读不到返回 0） */
    @Unique
    private static float cryptand$readTemp(ThermalBehaviour tb) {
        try {
            Object tObj = reflectField(tb, "temperature");
            return (tObj instanceof Number n) ? n.floatValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 代理方块温度跟随主方块（伪平均，2026-08-09 v2）。
     * 整条线圈只有主方块真正发热（heat/N 均分），代理方块不发热；
     * 但温度计贴代理方块应读到与主方块一致的读数 —— 每 tick 把主方块
     * thermalBehaviour.temperature 反射写入本代理的 thermalBehaviour.temperature。
     * - 主方块自身（mainBE==self）：无操作（自己已有真实温度）
     * - 代理方块：从 mainBE 读主方块温度 → 写自己的 temperature 字段
     * 注意只写 temperature 字段（不改 prevTemperature/overheatTicks 等，
     * 保持代理的过热爆炸判定仍由主方块触发）。
     */
    @Unique
    private static void cryptand$syncProxyTemp(Object self, Level level) {
        try {
            if (level == null || level.isClientSide) return;
            Object mainBe = reflectField(self, "mainBE");
            if (mainBe == null || mainBe == self) return; // 主方块无操作
            Object mtbObj = reflectField(mainBe, "thermalBehaviour");
            if (!(mtbObj instanceof ThermalBehaviour mtb)) return;
            Object ptbObj = reflectField(self, "thermalBehaviour");
            if (!(ptbObj instanceof ThermalBehaviour ptb)) return;
            float mainTemp = cryptand$readTemp(mtb);
            // 反射写代理 temperature 字段
            java.lang.reflect.Field f = null;
            Class<?> c = ptb.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("temperature");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f != null) {
                f.setAccessible(true);
                f.setFloat(ptb, mainTemp);
            }
        } catch (Throwable ignored) {
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
