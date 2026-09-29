package com.hdf.cryptand.neoforge.truescreen;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== java 侧查询真彩屏的桥（2026-09-29）=====
 *
 * <p>背景：移植件全在 scala 源集，`src/main/java` 编译期看不到它们（见 {@link TrueScreenRegistrar}
 * 的类注释）。此前 `SocPairingData` 直接 `instanceof` scala 的 Screen 类 ⇒ 破坏了这条纪律，
 * 导致 `compileJava` 与 `compileScala` 互相等待（Java ↔ Scala 循环依赖）。</p>
 *
 * <p>修法：判定统一走本桥；由 {@code SocContent} 在 ServiceLoader 装配时 {@link #install} 注入。</p>
 */
public final class TrueScreenBridge {

    private static volatile TrueScreenRegistrar registrar;

    private TrueScreenBridge() {
    }

    /** 注入 SPI 实现（SocContent 装配时调用，一次）。 */
    public static void install(TrueScreenRegistrar r) {
        if (r != null) {
            registrar = r;
        }
    }

    /** 未装配时返回 false（屏不存在 ⇒ 不是真彩屏）。 */
    public static boolean isTrueScreen(BlockEntity be) {
        final TrueScreenRegistrar r = registrar;
        return r != null && be != null && r.isTrueScreen(be);
    }

    public static boolean isTrueScreenOrigin(BlockEntity be) {
        final TrueScreenRegistrar r = registrar;
        return r != null && be != null && r.isTrueScreenOrigin(be);
    }
}
