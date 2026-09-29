/**
 * ===== CryptandSable 总闸 Mixin Plugin（2026-09-06） =====
 *
 * 控制依赖 CryptandSable 核心存在性的 mixin 注入面：
 *   - {@code cryptand.sable.core.mixins.json}（CryptandSableCoreMixin —— 取消官方物理）
 *   - {@code cryptand.sable.simulated.mixins.json}（PhysicsAssemblerCompatMixin —— 转接）
 *   - 其它无独立子开关、只要核心在就应注入的 sable 面 json（未来新增可复用本 plugin）
 *
 * 门控：enableCryptandSableCore=true（config/cryptand/cryptand-sable.toml，默认 true）
 *   → 正常注入；false → 全部跳过 —— 与用户 2026-09-06 要求一致：
 *   "关闭 enableCryptandSableCore 则是不加载 CryptandSable 子包相关内容"。
 *
 * ⚠ 类加载阶段 ModConfigSpec 尚未 build → 经 ConfigCryptandSable.bool 走 TOML 直读兜底
 *   （与 SableCompatMixinPlugin / SableDebugMixinPlugin 同模式）。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin;

import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import com.hdf.cryptand.neoforge.core.module.SubpackageRegistry;

public final class CryptandSableGateMixinPlugin implements IMixinConfigPlugin {

    /** core 总闸缓存（enableCryptandSableCore；类加载期读取一次）。 */
    private static volatile Boolean coreEnabled;

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
        // ★ 2026-09-06 【核心门控注册表】黑名单硬禁 / 白名单硬放行（先于 core 总闸）
        if (MixinGateRegistry.isDenied(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        if (MixinGateRegistry.isAllowed(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, true);
            return true;
        }
        // 静默门控（★ 2026-09-06 完全关闭语义：不打印任何注入判定行）
        SubpackageRegistry.recordMixin(mixinClassName, isCoreEnabled());
        return isCoreEnabled();
    }

    private static boolean isCoreEnabled() {
        // ★ 2026-09-07 缓存时序修正：spec 未加载（类加载期）→ 返回默认 true【不写缓存】，
        //   config 加载后的判定以真实 spec 值为准（否则 core=false 会因早期默认缓存仍注入）
        if (ConfigCryptandSable.SPEC.isLoaded()) {
            if (coreEnabled == null) {
                coreEnabled = ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get();
            }
            return coreEnabled;
        }
        return true;
    }
}
