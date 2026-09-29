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

import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class AeronauticsMixinPlugin implements IMixinConfigPlugin {

    /** TOML 配置目录（相对运行目录）：config/cryptand/ */
    private static final String CONFIG_DIR = "config/cryptand/";

    /** 是否启用轮子摩擦应力物理模型（aeronautics.toml）。类加载阶段读取，缓存结果。 */
    private static volatile Boolean wheelStressFriction;

    /** log4j 诊断（进 latest.log；2026-08-29 System.out 不进日志文件故改） */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Cryptand-AeroMixin");

    @Override
    public void onLoad(String mixinPackage) {
        // 探针：mixin config 被 NeoForge 读取即打印（定位是否已加载）
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

    /** 与"轮子应力开关"无关、只需装了 aeronautics 就生效的 mixin（2026-09-13 新增） */
    private static final Set<String> AERO_ONLY_MIXINS = Set.of(
            "ComparatorDirectionalOutputMixin"
    );

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            boolean aero = com.hdf.cryptand.neoforge.aeronautics
                    .AeronauticsCompat.isLoaded();
            if (!aero) {
                return false;
            }
            String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            // 比较器方向性输出：与 enableAeroWheelStressFriction 无关（否则关掉轮子应力会连带失效）
            if (AERO_ONLY_MIXINS.contains(shortName)) {
                return true;
            }
            return isWheelStressFriction();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * enableAeroWheelStressFriction（轮子摩擦应力 Mixin 注入开关；false 跳过 → 恢复
     * Offroad 原版静态 impact）。★ 2026-09-06 官方模式：aero 域 spec 已加载 → spec 值；
     * 类加载期未加载 → spec 默认 true（无手工 TOML）。
     */
    private static boolean isWheelStressFriction() {
        if (wheelStressFriction != null) return wheelStressFriction;
        wheelStressFriction = ConfigAero.SPEC.isLoaded()
                ? ConfigAero.ENABLE_AERO_WHEEL_STRESS_FRICTION.get()
                : true;
        return wheelStressFriction;
    }
}
