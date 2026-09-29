/**
 * ===== 力学可视化内置注册（框架内置，2026-09-14） =====
 *
 * <p>分三步（都是幂等的）：
 * <ol>
 *   <li>{@link #init()}：注册内置力源（官方力组桥接 + 自算重力）。服务端/客户端都调用。</li>
 *   <li>{@link #ensureStyles()}：客户端准备样式 —— 先读配置颜色覆盖，再注册内置默认配色。
 *       <b>颜色在注册时一次性决定，之后固定不变</b>（同组同色铁律）。</li>
 * </ol>
 *
 * <p>默认配色（KSP 风格）：重力黄 / 气动粉 / 浮力蓝 / 升力青 / 推进紫 / 气球橙 /
 * 磁力红 / 质心白 / 合力绿；可由 {@code sableForceDisplayColorOverrides} 覆盖。
 */
package com.hdf.cryptand.neoforge.sable.force.impl;

import com.hdf.cryptand.neoforge.sable.force.api.CryptandForceDisplay;
import com.hdf.cryptand.neoforge.sable.force.api.ForceStyle;

public final class ForceDisplayBootstrap {

    private static volatile boolean initialized = false;
    private static volatile boolean stylesReady = false;

    private ForceDisplayBootstrap() {
    }

    /** 注册内置【力源】（服务端与客户端各自调用；幂等）。 */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        ForceReflect.ensure();

        // 顺序由 priority 决定（重力 10 → 官方力组 100）
        CryptandForceDisplay.registerSource(new GravityForceSource());
        CryptandForceDisplay.registerSource(new SableForceGroupsSource());
    }

    /**
     * 客户端：准备【样式/颜色】（幂等）。
     *
     * <p>顺序很重要：**先**登记配置颜色覆盖，**再**注册内置默认配色 ——
     * 这样"配置覆盖 > 内置默认"在【颜色决定的那一刻】就已生效，符合
     * "颜色在注册时决定、之后固定"的语义。
     */
    public static synchronized void ensureStyles() {
        init();
        if (stylesReady) return;
        stylesReady = true;

        loadColorOverrides();
        registerDefaultStyles();
    }

    /** 兼容旧调用点（等价于 {@link #ensureStyles()}）。 */
    public static void applyConfigOverrides() {
        ensureStyles();
    }

    // ===== 内部 =====

    /** 读配置的颜色覆盖表（{@code "力id=0xRRGGBB"}）；解析失败静默跳过。 */
    private static void loadColorOverrides() {
        try {
            final java.util.List<? extends String> list =
                    com.hdf.cryptand.neoforge.sable.config.ConfigSable
                            .SABLE_FORCE_DISPLAY_COLOR_OVERRIDES.get();
            if (list == null || list.isEmpty()) return;

            for (final String raw : list) {
                if (raw == null) continue;
                final int eq = raw.indexOf('=');
                if (eq <= 0) continue;

                final net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.tryParse(raw.substring(0, eq).trim());
                if (id == null) continue;

                final String hex = raw.substring(eq + 1).trim()
                        .replace("0x", "").replace("0X", "").replace("#", "");
                final int color = (int) (Long.parseLong(hex, 16) & 0xFFFFFFL);

                CryptandForceDisplay.registerColorOverride(id, color);
            }
        } catch (final Throwable ignored) {
            // 配置格式错误 → 保留内置默认配色
        }
    }

    /** 注册内置默认配色（首次生效；已被覆盖表登记过的 id 会用覆盖色）。 */
    private static void registerDefaultStyles() {
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.GRAVITY, ForceStyle.of(0xFFD24A, 0.24f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.DRAG, ForceStyle.of(0xFF7BB6, 0.20f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.LEVITATION, ForceStyle.of(0x4FA8FF, 0.22f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.LIFT, ForceStyle.of(0x7FD8E8, 0.22f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.PROPULSION, ForceStyle.of(0xB07BFF, 0.20f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.BALLOON_LIFT, ForceStyle.of(0xFF8A3D, 0.22f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.MAGNETIC_FORCE, ForceStyle.of(0xE05343, 0.22f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.CENTER_OF_MASS, ForceStyle.of(0xFFFFFF, 0.16f, 1.0f));
        CryptandForceDisplay.registerStyle(CryptandForceDisplay.NET_FORCE, ForceStyle.of(0x66FF88, 0.18f, 1.0f));
    }
}
