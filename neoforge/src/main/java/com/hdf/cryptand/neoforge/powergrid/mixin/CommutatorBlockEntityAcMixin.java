/**
 * ===== 换向器：励磁检测 + 匹配判断（状态存实例，不走全局注册表） =====
 *
 * 换向器本身不发热——现实物理中热量最终传导到绕组（线圈）。
 * 本 mixin：
 *   - buildCircuit 注册端子节点为网络级频率源（转子转速频率）
 *   - tick 检测本机励磁类型（本地查询附近励磁绕组实例）
 *   - tick 计算励磁/电枢是否匹配，全部状态存入本实例字段（CommutatorStateHolder）
 *     → 线圈经 level 本地查询换向器实例后强转接口读取，无全局注册表
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CommutatorStateHolder;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.adapter.WindingStateHolder;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.sound.MotorSoundPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity",
       remap = false)
public abstract class CommutatorBlockEntityAcMixin implements CommutatorStateHolder {

    // ===== 实例状态字段（声明不初始化，规避 Architectury 构造器赋值破坏；JVM 默认值） =====
    @Unique private double cryptand$armFreq;
    @Unique private double cryptand$excFreq;
    @Unique private float cryptand$power;
    @Unique private float cryptand$current;
    @Unique private double cryptand$voltage;
    @Unique private double cryptand$rotorFreq;
    @Unique private boolean cryptand$acExcited;
    @Unique private boolean cryptand$mismatched;

    @Override public double cryptand$getArmFreq() { return cryptand$armFreq; }
    @Override public double cryptand$getExcFreq() { return cryptand$excFreq; }
    @Override public float cryptand$getPower() { return cryptand$power; }
    @Override public float cryptand$getCurrent() { return cryptand$current; }
    @Override public double cryptand$getVoltage() { return cryptand$voltage; }
    @Override public double cryptand$getRotorFreq() { return cryptand$rotorFreq; }
    @Override public boolean cryptand$isAcExcited() { return cryptand$acExcited; }
    @Override public boolean cryptand$isMismatched() { return cryptand$mismatched; }

    @Override
    public void cryptand$updateState(double armFreq, double excFreq, float power, float current,
                                     double voltage, double rotorFreq,
                                     boolean acExcited, boolean mismatched) {
        this.cryptand$armFreq = armFreq;
        this.cryptand$excFreq = excFreq;
        this.cryptand$power = power;
        this.cryptand$current = current;
        this.cryptand$voltage = voltage;
        this.cryptand$rotorFreq = rotorFreq;
        this.cryptand$acExcited = acExcited;
        this.cryptand$mismatched = mismatched;
    }

    /**
     * buildCircuit 后注册：换向器端子节点 → GeneratorCoupling（网络级频率源）。
     * 换向器（发电/电动模式）输出频率 = 转子转速 |ω|/2π。
     */
    @Inject(method = "buildCircuit", at = @At("TAIL"))
    private void cryptand$registerGenerator(CallbackInfo ci) {
        try {
            Object self = this;
            Object source = reflectField(self, "source");
            if (!(source instanceof GeneratorCoupling gc)) return;
            Object eb = reflectField(self, "electricBehaviour");
            if (!(eb instanceof ElectricBehaviour beh)) return;
            for (int t = 0; t < 2; t++) {
                OwnedFloatingNode node = beh.getTerminal(t);
                if (node != null) MultimeterDebug.GEN_SOURCE_NODES.put(node, gc);
            }
        } catch (Throwable t) {
            // 永不崩溃
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$acFrequencyHeat(CallbackInfo ci) {
        try {
            Object self = this;
            Object levelObj = reflectField(self, "level");
            if (!(levelObj instanceof Level level) || level.isClientSide) return;
            Object posObj = reflectField(self, "worldPosition");
            if (!(posObj instanceof BlockPos pos)) return;

            // 本机励磁：本地查询附近励磁绕组实例（数据在绕组实例字段）
            double excFreq = 0;
            boolean acExc = false;
            WindingStateHolder winding = MultimeterDebug.nearbyWinding(level, pos);
            if (winding != null) {
                excFreq = winding.cryptand$getFreq();
                acExc = winding.cryptand$isAc();
            }

            Object source = reflectField(self, "source");
            if (!(source instanceof GeneratorCoupling gc)) {
                cryptand$updateState(0, excFreq, 0, 0, 0, 0, acExc, false);
                return;
            }

            // 网络频率（网络级，禁止 BFS）
            double freq = 0;
            Object network = reflectField(gc, "network");
            if (network instanceof ElectricalNetwork en) {
                freq = MultimeterDebug.getNetworkFrequencyHz(en);
            }

            double threshold = ConfigLoad.MOTOR_MAX_DRIVE_FREQUENCY_HZ.get();
            boolean armAc = freq >= threshold;
            boolean mismatched = armAc != acExc;
            float power = ((CommutatorBlockEntity) (Object) this).getPower();
            float current = ((CommutatorBlockEntity) (Object) this).getCurrent(); // 电枢电流
            double voltage = gc.getVoltage(); // 加在换向器（电枢）上的电压
            double rotorFreq = MultimeterDebug.generatorFrequencyHz(gc); // 实际转子转速频率（堵转=0）

            // 状态存本实例（不走全局注册表）
            cryptand$updateState(freq, excFreq, power, current, voltage, rotorFreq, acExc, mismatched);

            // 电枢/电机发热（施加到【换向器】thermalBehaviour）：
            //   电枢铜耗 I²R（R=0.5Ω；堵转电流最大→最热）
            //   风阻/机械损耗 ∝ω²
            //   转差损耗 ∝(f_arm-f_exc)²（失步→热）
            //   不匹配 → 输入功率全转为热（停转无机械输出，能量守恒）
            // 励磁线圈侧不再承担这些（否则周围未连接的线圈被误加热）。
            double heat = 0;
            if (armAc && acExc) {
                heat = current * current * 0.5;
                heat += 0.003 * rotorFreq * rotorFreq;
                double dF = Math.abs(freq - excFreq);
                heat += 0.05 * dF * dF;
            } else if (armAc != acExc) {
                heat = Math.max(0, power);
            }
            if (heat > 0) {
                Object thermal = reflectField(self, "thermalBehaviour");
                if (thermal instanceof org.patryk3211.powergrid.electricity.base.ThermalBehaviour tb) {
                    tb.applyTickPower(heat);
                }
            }

            // 定期（每 5 tick）把真实参数发给附近玩家 → 客户端据此动态发声
            if (level instanceof ServerLevel serverLevel && serverLevel.getGameTime() % 5 == 0) {
                MotorSoundPayload payload = new MotorSoundPayload(pos, freq, excFreq, current,
                        power, voltage, rotorFreq, acExc, mismatched);
                for (net.minecraft.server.level.ServerPlayer sp :
                        serverLevel.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                                new net.minecraft.world.phys.AABB(pos).inflate(64.0))) {
                    PacketDistributor.sendToPlayer(sp, payload);
                }
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
