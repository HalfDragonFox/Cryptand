/**
 * ===== 电机/发电机动态声音（真实物理） =====
 *
 * 声音分两层，全部随真实运行状态动态变化：
 *  1. 机械旋转声（原版 RotorSoundInstance / ModdedSoundEvents.GENERATOR）：
 *     由原版 RotorBehaviour.tickAudio 创建并播放，其 tick 内
 *     音量 ∝ 转速、音调 ∝ 转速（见 RotorSoundInstanceMixin 更精细映射）。
 *  2. 电磁嗡鸣（ModdedSoundEvents.TRANSFORMER_HUM，本 Mixin 管理）：
 *      - 激活条件：网络存在交流波形（电频率 f > 0，客户端 BFS 沿导线查询）
 *      - 音调 ∝ 电频率 f（50Hz → pitch 1.0，60Hz 更高；不同波形声音不同）
 *      - 音量 ∝ 转差 |f-f_rotor|/f（堵转/重载 → 电流大 → 嗡鸣响）
 *      - 直流（f = 0）→ 无嗡鸣（直流电机确实安静）
 *
 * 之前版本在 tickAudio() HEAD 直接取消（完全静音）。
 * 现在改为 TAIL 注入不取消：保留原版机械声，并叠加随波形变化的电磁嗡鸣。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.render;

import com.hdf.cryptand.neoforge.core.sound.MotorSoundState;
import com.hdf.cryptand.neoforge.powergrid.sound.GeneratorHumModel;
import com.hdf.cryptand.neoforge.powergrid.sound.MotorHumModel;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.sound.SoundModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RotorBehaviour.class, remap = false)
public abstract class RotorSoundMixin {

    /** 电磁嗡鸣声音模型（OpenAL 合成，客户端）；null = 未播放 */
    @Unique private SoundModel cryptand$hum;

    /**
     * tickAudio（仅客户端）TAIL：管理电磁嗡鸣。
     * 不取消原版 → 原版机械声照常播放（RotorSoundInstance 随转速动态）。
     */
    @Inject(method = "tickAudio", at = @At("TAIL"))
    private void cryptand$dynamicHum(CallbackInfo ci) {
        try {
            RotorBehaviour self = (RotorBehaviour) (Object) this;
            if (!self.isController()) {
                cryptand$stopHum();
                return;
            }
            Level level = self.getWorld();
            if (level == null || !level.isClientSide) return;
            BlockPos pos = self.getPos();

            // 玩家太远就停（听不到，省开销）
            // ⚠ 服务端兼容（2026-08-30 runServer 崩溃修复）：mixin 方法体不能直接
            // 引用 net.minecraft.client.Minecraft（服务端无此类 → 附加阶段
            // ClassNotFound → powergrid mod 加载失败）。改反射，仅客户端执行。
            Object localPlayer = null;
            try {
                Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
                Object mc = mcCls.getMethod("getInstance").invoke(null);
                try {
                    localPlayer = mc.getClass().getMethod("getPlayer").invoke(mc);
                } catch (Throwable ignored) {
                    try {
                        java.lang.reflect.Field f = mc.getClass().getDeclaredField("player");
                        f.setAccessible(true);
                        localPlayer = f.get(mc);
                    } catch (Throwable ignored2) {
                    }
                }
            } catch (Throwable ignored) {
            }
            if (localPlayer == null
                    || ((net.minecraft.world.entity.Entity) localPlayer)
                    .distanceToSqr(pos.getCenter()) > 48.0 * 48.0) {
                cryptand$stopHum();
                return;
            }

            // 清理过期声音参数条目（电机声音参数归本处；变压器声音参数
            // 由 ClientSoundTicker 统一清理——不依赖电机存在）
            MotorSoundState.tick();

            // 电频率 / 转子频率 / 电流：
            // 优先用服务端同步的真实参数（声音包）；包未到（首次/加载）用客户端 BFS 兜底。
            double f;
            double fRotor;
            float current;
            MotorSoundState.Entry e = MotorSoundState.get(pos);
            if (e != null) {
                // 发电机：网络无 AC 源（armFreq=0）但转子在转 → 用转子频率（输出频率）
                f = (e.armFreq() > 0) ? e.armFreq() : e.rotorFreq();
                fRotor = e.rotorFreq();
                current = e.current();
            } else {
                // 兜底：只认本机实际连接的导线（不跨变压器，旁边未连接的通电线缆不渗入）
                f = MultimeterDebug.getBlockNetworkFrequencyHz(level, pos);
                fRotor = Math.abs(self.getAngularVelocity()) / (2 * Math.PI);
                current = 0;
            }

            // 直流 / 无波形 → 无嗡鸣
            if (f <= 0) {
                cryptand$stopHum();
                return;
            }

            // 转差（堵转/重载 → 电流大）+ 真实电流 → 音量。
            // 转速 < 5 RPM（5/60 Hz）即判定为堵转 → 转差 = 1（堵转电流最大）。
            double slip = (fRotor >= 5.0 / 60.0) ? Math.min(1.0, Math.abs(f - fRotor) / f) : 1.0;
            // 声音大小与功率（或整体电流）有关（统一规则）：
            // 优先用服务端同步的真实功率；无功率（客户端 BFS 兜底）用电流
            float volume = e != null
                    ? SoundModel.volumeFromPower(Math.abs(e.power()))
                    : SoundModel.volumeFromCurrent(Math.abs(current));
            // 音调随电频率（统一规则：声音频率与输入电频率有关）
            float pitch = SoundModel.pitchFromFrequency(f);

            if (cryptand$hum == null) {
                // OpenAL 合成声音模型（2026-08-12 用户要求：客户端合成，服务器仅发数据）
                java.util.function.BooleanSupplier alive = () -> cryptand$humAlive(self);
                cryptand$hum = cryptand$isGenerating(self)
                        ? new GeneratorHumModel(alive)
                        : new MotorHumModel(alive);
            }
            if (cryptand$hum.failed) {
                cryptand$stopHum();
                return;
            }
            cryptand$hum.setPosition(pos.getCenter().x, pos.getCenter().y, pos.getCenter().z);
            cryptand$hum.update(volume, pitch);
        } catch (Throwable t) {
            // 永不崩溃
        }
    }

    /**
     * 嗡鸣实例自检：行为是否仍然有效。
     * 即使 tickAudio 因方块移除/区块卸载不再执行，声音实例自身 tick 也会调用此检查，
     * 失效即自停（修复"断电/拆机后还嗡嗡响"）。
     */
    @Unique
    private static boolean cryptand$humAlive(RotorBehaviour self) {
        try {
            if (!self.isController()) return false;
            Object be = cryptand$reflect(self, "blockEntity");
            if (be instanceof net.minecraft.world.level.block.entity.BlockEntity b && b.isRemoved()) {
                return false;
            }
            return self.getWorld() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Unique
    private static Object cryptand$reflect(Object target, String name) {
        if (target == null) return null;
        java.lang.reflect.Field f = null;
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

    @Unique
    private void cryptand$stopHum() {
        try {
            if (cryptand$hum != null) {
                cryptand$hum.close();
                cryptand$hum = null;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 发电机模式：转子被外部机械驱动（forceSupplier 非 null）→ 用发电机音色 */
    @Unique
    private static boolean cryptand$isGenerating(RotorBehaviour self) {
        try {
            return cryptand$reflect(self, "forceSupplier") != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
