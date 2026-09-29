/**
 * ===== 发电机离合器生成转速上限放开 =====
 *
 * 原版 GeneratorClutchBlockEntity.lazyTick()（MOTOR 模式，服务端）：
 *   int speed = (int) rotorBehaviour.getAngularVelocity();   // 转子角速度 (rad/s)
 *   if (speed > 256) speed = 256;    // ← 硬编码 clamp ±256！
 *   if (speed < -256) speed = -256;
 *   generatedSpeed = speed; updateGeneratedRotation();
 *
 * 导致换向器电机通过离合器输出到 Create 网络的转速被锁死在 ±256 RPM
 * （转速表最大 256），不随配置 generatorMotorMaxRpm 变化。
 *
 * 本 Mixin 在 lazyTick TAIL 用配置上限（generatorMotorMaxRpm，默认 16384）
 * 重新计算并覆盖 generatedSpeed + 通知 Create 网络。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.clutch.GeneratorClutchBlockEntity",
       remap = false)
public abstract class GeneratorClutchSpeedMixin {

    @Inject(method = "lazyTick", at = @At("TAIL"))
    private void cryptand$applyMaxRpm(CallbackInfo ci) {
        try {
            Object self = this;
            // 仅 MOTOR 模式（生成转速 = 转子角速度）；GENERATOR 模式不在此 clamp 路径
            Object mode = reflectField(self, "mode");
            if (mode == null) return;
            Object modeVal = mode.getClass().getMethod("get").invoke(mode);
            if (modeVal == null || !"MOTOR".equals(modeVal.toString())) return;

            Object levelObj = reflectField(self, "level");
            if (!(levelObj instanceof Level level) || level.isClientSide) return;

            Object rotorBeh = reflectField(self, "rotorBehaviour");
            if (!(rotorBeh instanceof RotorBehaviour rb)) return;

            // 转子角速度 (rad/s)，按配置上限 clamp（原版硬编码 ±256）
            int speed = (int) rb.getAngularVelocity();
            int max = ConfigPowerGrid.GENERATOR_MOTOR_MAX_RPM.get();
            speed = Math.max(-max, Math.min(max, speed));

            Object curObj = reflectField(self, "generatedSpeed");
            int cur = (curObj instanceof Number n) ? n.intValue() : 0;
            if (speed != cur) {
                reflectSetInt(self, "generatedSpeed", speed);
                // 通知 Create 网络转速变化
                Method m = null;
                Class<?> c = self.getClass();
                while (c != null && m == null) {
                    try {
                        m = c.getDeclaredMethod("updateGeneratedRotation");
                    } catch (NoSuchMethodException e) {
                        c = c.getSuperclass();
                    }
                }
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(self);
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

    @Unique
    private static void reflectSetInt(Object target, String name, int value) {
        if (target == null) return;
        Field f = null;
        Class<?> c = target.getClass();
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) return;
        try {
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (Throwable ignored) {
        }
    }
}
