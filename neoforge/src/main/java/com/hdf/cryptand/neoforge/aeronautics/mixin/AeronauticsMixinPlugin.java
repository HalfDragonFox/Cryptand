/**
 * ===== Aeronautics（飞艇/轮子物理）Mixin 子管理（2026-08-28） =====
 *
 * 门控链：
 *   1) create_aeronautics/offroad/sable mod 已安装（AeronauticsCompat.isLoaded）
 *      —— 未装 → 目标类不存在，跳过安全
 *   2) 配置 ENABLE_AERO_WHEEL_STRESS_FRICTION（config/cryptand/aeronautics.toml）
 *      —— false → 恢复 Offroad 原版静态 stressImpact
 *
 * ⚠ 类加载阶段 FMLLoader.getLoadingModList()（PREPARE 早于 FML 初始化）。
 */

package com.hdf.cryptand.neoforge.aeronautics.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

public final class AeronauticsMixinPlugin implements IMixinConfigPlugin {

    /** TOML 配置目录（相对运行目录）：config/cryptand/ */
    private static final String CONFIG_DIR = "config/cryptand/";

    /** 是否启用轮子摩擦应力物理模型（aeronautics.toml）。类加载阶段读取，缓存结果。 */
    private static volatile Boolean wheelStressFriction;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            boolean aero = com.hdf.cryptand.neoforge.aeronautics
                    .AeronauticsCompat.isLoaded();
            boolean cfg = isWheelStressFriction();
            boolean apply = aero && cfg;
            String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            System.out.println("[Cryptand] AeronauticsMixin " + shortName
                    + " → " + (apply ? "INJECTED" : "SKIPPED")
                    + " (aero=" + aero + ", cfg=" + cfg + ")");
            return apply;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从 config/cryptand/aeronautics.toml 直接读取 enableAeroWheelStressFriction
     * （轮子摩擦应力 Mixin 注入开关；false 跳过 → 恢复 Offroad 原版静态 impact）。
     * ⚠ 类加载阶段 ModConfigSpec 尚未 build → 必须绕过 ConfigLoad 手动读 TOML
     * （与 CryptandMixinPlugin.readConfigBool 同模式）。
     */
    private static boolean isWheelStressFriction() {
        if (wheelStressFriction != null) return wheelStressFriction;
        wheelStressFriction = readConfigBool(CONFIG_DIR + "aeronautics.toml",
                "enableAeroWheelStressFriction", true);
        return wheelStressFriction;
    }

    /** 通用：从指定 TOML 读取布尔配置，失败时回退默认值 */
    private static boolean readConfigBool(String path, String key, boolean defaultValue) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(path);
            if (!p.toFile().exists()) {
                System.out.println("[Cryptand] Config file not found, using default "
                        + key + "=" + defaultValue);
                return defaultValue;
            }
            try (com.electronwill.nightconfig.core.file.FileConfig config =
                         com.electronwill.nightconfig.core.file.FileConfig.of(p)) {
                config.load();
                boolean val = config.getOrElse(key, defaultValue);
                System.out.println("[Cryptand] Config " + key + "=" + val);
                return val;
            }
        } catch (Exception e) {
            System.out.println("[Cryptand] Failed to read config " + key
                    + ": " + e.getMessage() + " — using default: " + defaultValue);
            return defaultValue;
        }
    }
}
