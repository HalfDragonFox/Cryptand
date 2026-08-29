/**
 * ===== 机械旋转声更精细的转速映射 =====
 *
 * 原版 RotorSoundInstance.tick 的映射：
 *   volume = clamp(|ω|/128, 0, 1)
 *   pitch  = clamp(|ω|/(maxSpeed/2), 0.5, 2)
 * 其中 maxSpeed = RotorBehaviour.getMaxRotationSpeed()（本 mod 已改为 16384 RPM），
 * 正常转速（几百 rad/s）下 pitch 恒为下限 0.5 → 音调几乎不随转速变化。
 *
 * 本 Mixin 在 tick TAIL 覆盖为对低速更敏感的映射：
 *   volume = clamp(|ω|/96,  0, 1)
 *   pitch  = clamp(0.5 + |ω|/1024, 0.5, 2)
 * （256 rad/s → pitch 0.75，1280 rad/s → pitch 1.75）
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import net.minecraft.util.Mth;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.rotor.RotorSoundInstance",
       remap = false)
public abstract class RotorSoundInstanceMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$betterMechanicalMapping(CallbackInfo ci) {
        try {
            Object behaviour = cryptand$reflect(this, "behaviour");
            if (!(behaviour instanceof RotorBehaviour rb)) return;
            float speed = Math.abs(rb.getAngularVelocity());
            // 机械声：音量∝转速；音调∝转速（对低速敏感，比原版 speed/(max/2) 更动态）
            cryptand$setFloat(this, "volume", Mth.clamp(speed / 96f, 0f, 1f));
            cryptand$setFloat(this, "pitch", Mth.clamp(0.5f + speed / 1024f, 0.5f, 2f));
        } catch (Throwable ignored) {
            // 永不崩溃
        }
    }

    private static void cryptand$setFloat(Object target, String name, float value) {
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
            f.setFloat(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static Object cryptand$reflect(Object target, String name) {
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
