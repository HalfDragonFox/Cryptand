/**
 * ===== Sable 调试功能 Mixin 门控（2026-09-02） =====
 *
 * 控制 {@code cryptand.sable.debug.mixins.json} 的注入：
 *   - freezePhysics=true（config/cryptand/cryptand-sable.toml）→ 注入
 *     OfficialRapierEngineFreezeMixin（强制冻结 step，结构不动）
 *   - debugHighlight=true → 注入 WorldChunkUploaderHighlightMixin +
 *     SableServerBridgeHighlightMixin（高亮被收集的世界碰撞方块）
 *   - 均 false → 全部跳过（mixin 不应用 → 热路径零开销）
 *
 * ⚠ 类加载阶段 ModConfigSpec 尚未 build → 直接读 TOML（与 SableMixinPlugin 同模式）。
 * 每个 debug 开关独立：freeze 与 highlight 分开门控（各自 mixin 类独立判断）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.debug;

import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import com.hdf.cryptand.neoforge.core.module.SubpackageRegistry;

public final class SableDebugMixinPlugin implements IMixinConfigPlugin {

    private static final String CONFIG_DIR = "config/cryptand/";

    private static volatile Boolean freezePhys;
    private static volatile Boolean debHighlight;
    private static volatile Boolean debScopeBox;

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
        // ★ 2026-09-06 【核心门控注册表】黑名单硬禁 / 白名单硬放行（先于 core 总闸）
        if (MixinGateRegistry.isDenied(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        if (MixinGateRegistry.isAllowed(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, true);
            return true;
        }
        // ★ 2026-09-06 【core 总闸】核心关闭 → 调试注入面（freeze/highlight/scopeBox）全跳过
        //   （完全关闭语义：静默，不打印判定行）
        if (!isCoreEnabled()) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        // 按 mixin 类名分别门控
        final String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        final boolean apply;
        if (shortName.contains("Freeze")) {
            apply = isFreezePhysics();
        } else if (shortName.contains("Highlight")) {
            // ★ 2026-09-03 debugScopeBox 也依赖 Highlight mixin：黄框=被扫描方块需
            //   getCollisionShape 命中点注入采集（forceRescan + markHit 写共享缓存）。
            apply = isDebugHighlight() || isDebugScopeBox();
        } else if (shortName.contains("ClearScan")) {
            // ★ 2026-09-03 拆卸路径清空黄框缓存（与 Highlight 采集同开关）
            apply = isDebugHighlight() || isDebugScopeBox();
        } else {
            apply = false;
        }
        SubpackageRegistry.recordMixin(mixinClassName, apply);
        return apply;
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

    /** 官方模式读取布尔：spec 未加载 → 默认值且不写缓存（config 加载后以真实值判定）。 */
    private static boolean specBool(net.neoforged.neoforge.common.ModConfigSpec spec,
                                    net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value,
                                    boolean def) {
        return spec.isLoaded() ? value.get() : def;
    }

    private static boolean isFreezePhysics() {
        if (freezePhys != null) return freezePhys;
        final boolean v = specBool(ConfigCryptandSable.SPEC,
                ConfigCryptandSable.SABLE_FREEZE_PHYSICS, false);
        if (ConfigCryptandSable.SPEC.isLoaded()) {
            freezePhys = v;
        }
        return v;
    }

    private static boolean isDebugHighlight() {
        if (debHighlight != null) return debHighlight;
        final boolean v = specBool(ConfigCryptandSable.SPEC,
                ConfigCryptandSable.SABLE_DEBUG_HIGHLIGHT, false);
        if (ConfigCryptandSable.SPEC.isLoaded()) {
            debHighlight = v;
        }
        return v;
    }

    private static boolean isDebugScopeBox() {
        if (debScopeBox != null) return debScopeBox;
        final boolean v = specBool(ConfigCryptandSable.SPEC,
                ConfigCryptandSable.SABLE_DEBUG_SCOPE_BOX, false);
        if (ConfigCryptandSable.SPEC.isLoaded()) {
            debScopeBox = v;
        }
        return v;
    }
}
