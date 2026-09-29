package com.hdf.cryptand.neoforge.cryptandsable.core.backend;


/**
 * Rapier3D 类反射引用（Rapier3DClass）。
 *
 * <p>官方 sable 是 runtimeOnly 依赖（compile classpath 无）→ 后端必须用
 * Class.forName 反射获取 {@code com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.CryptandRapierNative}。
 * 只在首次调用时解析一次（惰性缓存）。
 */
public final class Rapier3DClass {
    private static volatile Class<?> klass;

    private Rapier3DClass() {
    }

    /** 惰性解析 Rapier3D 类；失败返回 null。 */
    public static Class<?> klass() {
        if (klass == null) {
            synchronized (Rapier3DClass.class) {
                if (klass == null) {
                    try {
                        klass = Class.forName(
                                "CryptandRapierNative");
                    } catch (Throwable t) {
                        klass = null;
                    }
                }
            }
        }
        return klass;
    }
}