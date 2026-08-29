/**
 * ===== 修复：RotorBehaviour.applyTickForce 跨线程力丢失 =====
 *
 * 问题：异步化后，后台求解线程调用 GeneratorCoupling.postUpperSolve() →
 * rotor.applyTickForce(F)（totalForce += F），而主线程 RotorBehaviour.tick()
 * 会 totalForce = 0 清零。两者竞争时力被清零丢失 → 扭矩不生效 → 转速持续衰减。
 *
 * 修复：覆写 applyTickForce，直接把力应用到控制器的 angularVelocity，
 * 绕过 totalForce 的跨线程清零竞争。力 = F / 20 / inertia（与原版 tick 语义一致）。
 * 每 50ms 后台求解施加一次力 ≈ 每 tick（20Hz）施加，频率一致。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.multithreading;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour",
       remap = false)
public abstract class RotorBehaviourForceMixin {

    @Inject(method = "applyTickForce", at = @At("HEAD"), cancellable = true)
    private void cryptand$directApplyForce(float force, CallbackInfo ci) {
        try {
            Object self = this;
            // 获取控制器（RototBehaviour.getControllerOrThis()）
            Method gcot = self.getClass().getMethod("getControllerOrThis");
            Object controller = gcot.invoke(self);
            if (controller == null) { ci.cancel(); return; }

            float inertia = getFloat(controller, "inertia");
            if (inertia <= 0) { ci.cancel(); return; }

            if (Math.abs(force) > 0.001f) {
                // 直接把力应用到角速度（与原版 totalForce/20/inertia 语义一致）
                float av = getFloat(controller, "angularVelocity");
                setFloat(controller, "angularVelocity", av + force / 20f / inertia);
            }
        } catch (Throwable t) {
            // 反射失败 → 走原方法（totalForce 路径）作为兜底
            return;
        }
        ci.cancel();
    }

    @Unique
    private static float getFloat(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.getFloat(target);
    }

    @Unique
    private static void setFloat(Object target, String name, float value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    @Unique
    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
