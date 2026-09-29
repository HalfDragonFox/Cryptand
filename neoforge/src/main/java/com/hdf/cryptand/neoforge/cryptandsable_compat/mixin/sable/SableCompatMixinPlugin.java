/**
 * ===== 原版 Sable 兼容 Mixin 门控（2026-09-01） =====
 *
 * 控制 {@code cryptand.sable.sable.mixins.json}（compat 子包）的注入：
 *   - {@code enableSableCompat=true}（config/cryptand/cryptand-sable.toml，默认 true）
 *     注入官方 mixinterface（EntityMovementExtension / SubLevelContainerHolder 等）
 *     并把调用转接到 CryptandSable API —— 官方 sable 惰性加载时其他 mod 强转不崩。
 *   - false → 全部跳过（仅确定运行环境无其他 mod 强转官方接口时使用）。
 *
 * ⚠ 类加载阶段必须绕过 ConfigLoad（ModConfigSpec 尚未 build），直接读 TOML
 *   （与 SableMixinPlugin.readConfigBool 同模式）。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable_compat.config.ConfigCryptandSableCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import com.hdf.cryptand.neoforge.core.module.SubpackageRegistry;

public final class SableCompatMixinPlugin implements IMixinConfigPlugin {

    private static final String CONFIG_DIR = "config/cryptand/";

    private static volatile Boolean compatEnabled;

    /** ★ 2026-09-06 core 总闸缓存（enableCryptandSableCore；类加载期 TOML 直读）。 */
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
        // ★ 2026-09-06 【核心门控注册表】黑名单硬禁 / 白名单硬放行（先于本 plugin 条件）
        if (MixinGateRegistry.isDenied(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        if (MixinGateRegistry.isAllowed(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, true);
            return true;
        }
        final String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        // ★ 2026-09-06 【官方原版探测】classpath 存在官方 sable mixin config（sable*.mixins.json）
        //   ⇔ 官方以【原版】加载（其官方 mixin 会注入官方接口实现）。
        //   - 官方原版在场：本 compat 层【接口注入类】一律跳过（官方 mixin 已注入同一接口/
        //     方法——重复注入冲突/行为分裂）；同时规避类加载期 spec 未加载导致 core=false
        //     仍注入的时序漏洞（core 关 + 官方原版 = 模式 C：官方接口由官方自己注入）。
        //   - patch/无官方形态（官方 mixin config 不在）：按 core && compat 门控注入（转接）。
        //   ★ 完全关闭语义：全程静默（不打印任何注入判定行）。
        final boolean officialMixinsPresent = isOfficialMixinsPresent();
        final boolean interfaceInjector = isInterfaceInjector(shortName);
        if (officialMixinsPresent && interfaceInjector) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        // 行为转接类（redirect/转接，不与官方接口注入冲突）在官方原版下仍需 core 门控：
        // core=false（模式 C）→ 全部跳过（CryptandSable 不接管，转接目标不存在）。
        // ⚠ 注入方法体已另加运行期 core 门控（apply 早于 config 加载的兜底——注入但零行为）。
        SubpackageRegistry.recordMixin(mixinClassName, isCoreEnabled() && isCompatEnabled());
        return isCoreEnabled() && isCompatEnabled();
    }

    /** 本包内与官方 mixin 同 target 同接口的"接口注入类"（官方原版下跳过，防重复注入）。 */
    private static boolean isInterfaceInjector(final String shortName) {
        return "EntityMovementCompatMixin".equals(shortName)
                || "ServerLevelContainerMixin".equals(shortName)
                || "ClientLevelContainerMixin".equals(shortName)
                || "BlockStateMixin".equals(shortName)
                // ★ 2026-09-06 官方原版自带 clip_overwrite.ClientLevelMixin（pose supplier 注入）
                //   → Cryptand 版重复注入冲突（sable$pushPoseSupplier 等 overwrite conflict）→ 跳过
                || "ClientLevelMixin".equals(shortName);
    }

    /** classpath 是否存在官方 sable mixin config（官方原版标志；patch 版已剔除该资源）。 */
    private static volatile Boolean officialMixinsCache;

    private static boolean isOfficialMixinsPresent() {
        if (officialMixinsCache != null) return officialMixinsCache;
        boolean found = false;
        try {
            ClassLoader cl = SableCompatMixinPlugin.class.getClassLoader();
            found = cl.getResource("sable.mixins.json") != null
                    || cl.getResource("sable-neoforge.mixins.json") != null;
        } catch (Throwable ignored) {
        }
        officialMixinsCache = found;
        return found;
    }

    /** ★ 2026-09-07 缓存时序修正：spec 未加载 → 默认 true 不写缓存（config 后以真实值判定）。 */
    private static boolean isCoreEnabled() {
        if (ConfigCryptandSable.SPEC.isLoaded()) {
            if (coreEnabled == null) {
                coreEnabled = ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get();
            }
            return coreEnabled;
        }
        return true;
    }

    /** ★ 2026-09-07 同（enableSableCompat；spec 未加载 → 默认 true 不写缓存）。 */
    private static boolean isCompatEnabled() {
        if (ConfigCryptandSableCompat.SPEC.isLoaded()) {
            if (compatEnabled == null) {
                compatEnabled = ConfigCryptandSableCompat.ENABLE_SABLE_COMPAT.get();
            }
            return compatEnabled;
        }
        return true;
    }
}