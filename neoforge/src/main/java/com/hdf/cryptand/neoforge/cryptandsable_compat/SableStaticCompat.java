package com.hdf.cryptand.neoforge.cryptandsable_compat;


import com.hdf.cryptand.neoforge.CryptandNeoForge;

/**
 * 官方 sable 静态注册的兼容入口（SableStaticCompat）—— 属于 cryptandsable_compat。
 *
 * <p>职责：部分下游 mod（aeronautics / sable-schematic）在【惰性加载】官方
 * {@code dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyTypes}
 * 时，若其静态注册未在 NewRegistryEvent 之前执行会触发 IllegalStateException。
 * 官方 jar 已作为纯类库加载（类签名在、不初始化）；本入口在 mod 构造期用【反射】
 * 触发该静态注册，保证下游惰性加载安全。失败仅警告（官方类不在 → 无影响）。
 */
public final class SableStaticCompat {

    private static boolean attempted = false;

    private SableStaticCompat() {
    }

    /** 反射触发官方 PhysicsBlockPropertyTypes.register()（幂等；失败仅警告）。 */
    public static void triggerOfficialBlockPropertyRegistration() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            final Class<?> clazz = Class.forName(
                    "dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyTypes");
            final java.lang.reflect.Method m = clazz.getDeclaredMethod("register");
            m.setAccessible(true);
            m.invoke(null);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] official PhysicsBlockPropertyTypes register skipped: {}", t);
        }
    }
}