/**
 * ===== sable（航空学物理亚层）Mixin 子管理（2026-08-24） =====
 *
 * 仅安装 sable（物理化核心）mod 时注入（目标类不存在时跳过安全）。
 * ⚠ 类加载阶段 FMLLoader.getLoadingModList()（PREPARE 早于 FML 初始化）。
 */

package com.hdf.cryptand.neoforge.sable.mixin;

import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import com.hdf.cryptand.neoforge.core.module.SubpackageRegistry;

public final class SableMixinPlugin implements IMixinConfigPlugin {

    /** TOML 配置目录（相对运行目录）：config/cryptand/ */
    private static final String CONFIG_DIR = "config/cryptand/";

    /** 是否启用 Sable 联动（config/cryptand/sable.toml）。类加载阶段读取，缓存结果。 */
    private static volatile Boolean sableSupport;

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
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // ★ 2026-09-06 【核心门控注册表】黑名单硬禁 / 白名单硬放行（先于一切条件）
        if (MixinGateRegistry.isDenied(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        if (MixinGateRegistry.isAllowed(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, true);
            return true;
        }
        boolean apply = false;
        try {
            boolean sableInstalled = net.neoforged.fml.loading.FMLLoader
                    .getLoadingModList().getModFileById("sable") != null;
            if (mixinClassName.contains("SableRapierNativeF64RedirectMixin")) {
                // ★ 2026-09-08 f64 引擎劫持：装官方 sable 即注入（不做任何运行时行为，
                //   实际劫持由方法体 enableSableRapier64 spec 门控——与 enableSableSupport
                //   解耦：用户可能只想优化官方引擎而关闭老联动面）
                apply = sableInstalled;
            } else {
                // 门控链（★ 2026-09-06 三轮修正）：Sable mod 已安装 && enableSableSupport。
                //   老 sable 联动面（坐标迁移/端点回迁等）= Cryptand × 官方 sable 的联动层，
                //   不属于 cryptandsable 子包 → 【不受 enableCryptandSableCore 总闸控制】——
                //   模式 C（core 关 + 官方原版运行）下联动应正常工作。
                boolean cfg = isSableSupport();
                apply = sableInstalled && cfg;
            }
        } catch (Throwable ignored) {
        }
        SubpackageRegistry.recordMixin(mixinClassName, apply);
        return apply;
    }

    /**
     * 官方模式读取 enableSableSupport（Sable 联动开关；false 跳过 → 不注入物理化
     * 坐标迁移 Mixin）。
     * ★ 2026-09-07 缓存时序修正：spec 未加载（类加载期早于 config）→ 返回默认 true
     * 【不写缓存】——config 加载后的判定以真实 spec 值为准（否则 config=false 会因
     * 早期默认被缓存而仍注入）。
     */
    private static boolean isSableSupport() {
        final ConfigSable cfg = ConfigSable.INSTANCE;
        if (cfg.spec().isLoaded()) {
            if (sableSupport == null) {
                // ⚠ 2026-08-30 开关归 sable 子包（sable.toml）；plugin 在类加载期运行
            // （早于 config 加载）→ 用 ConfigLoad 预读（core lib——无子包编译依赖）
            sableSupport = com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .preloadBoolean("sable", "enableSableSupport", true);
            }
            return sableSupport;
        }
        return true;
    }
}
