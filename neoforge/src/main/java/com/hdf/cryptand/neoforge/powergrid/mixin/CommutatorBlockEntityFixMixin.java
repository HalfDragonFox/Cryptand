/**
 * ===== 修复：CommutatorBlockEntity 自我代理 =====
 *
 * PowerGrid 的 tick() 在 updateBehaviour 重建时遍历段集合，若遍历到"自己"
 * （source 非 null）会把 proxyTarget 设为自己的位置，创建 ProxyElectricBehaviour
 * 代理到自己并清空 source，导致换向器失去电力（电动机不转）。
 *
 * 检测："精确的自我代理"——Proxy 的 getMainBehaviour() 目标块实体 == 自己。
 * 正常的多换向器代理（目标不是自己）不会被误判。
 *
 * 修复：强制 updateBehaviour=true，下一 tick 重建时 source 已是 null 不会再
 * 自我代理，会走 new ElectricBehaviour 分支重新 buildCircuit 恢复 source。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity",
       remap = false)
public abstract class CommutatorBlockEntityFixMixin {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$fixSelfProxy(CallbackInfo ci) {
        try {
            Object level = reflectField(this, "level");
            if (level == null) return;
            boolean client = (Boolean) level.getClass().getMethod("isClientSide").invoke(level);
            if (client) return;

            Object eb = reflectField(this, "electricBehaviour");
            if (eb == null || !"ProxyElectricBehaviour".equals(eb.getClass().getSimpleName()))
                return;

            // 检查 proxy 的 main behaviour 是否指向"自己"（自我代理）
            Object mainOpt = invokeMethod(eb, "getMainBehaviour");
            if (!(mainOpt instanceof java.util.Optional<?> opt) || opt.isEmpty())
                return;
            Object mainBE = reflectField(opt.get(), "blockEntity");
            if (mainBE != this)
                return; // 正常代理（指向其他换向器），不干预

            Object source = reflectField(this, "source");
            setField(this, "updateBehaviour", true);
            LOGGER.warn("[Cryptand] Self-proxy detected on commutator@{} (source={}), "
                    + "forcing rebuild", reflectField(this, "worldPosition"), source);
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
    private static void setField(Object target, String name, Object value) {
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
            f.set(target, value);
        } catch (Throwable t) {
            // ignore
        }
    }

    @Unique
    private static Object invokeMethod(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
