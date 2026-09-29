package com.hdf.cryptand.neoforge.dynamic.mixin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== mixin 核心的门控注册表（2026-09-29 用户定案）=====
 *
 * <p>规则：<b>只有显式注册过的 modid 才受我们管辖</b>，且注册后<b>默认走白名单</b>
 * （只有列入白名单的 mixin 才注入）—— 这样既不会干扰未选择的 mod，也能精确控制已注册的 mod。</p>
 *
 * <p>「向 mixin 核心申请注册」= 该 mod 的 mixins.json 采用我们的
 * {@link DynamicMixinPlugin}，并实现 {@link MixinGatePlugin} 声明 `mixinModId()` / `allowedMixins()`。</p>
 */
public final class MixinCoreRegistry {

    /** modId → 允许注入的 mixin 全限定类名集合（存在 key 即"已注册"）。 */
    private static final Map<String, Set<String>> ALLOWED = new ConcurrentHashMap<>();

    private MixinCoreRegistry() {
    }

    /** 注册一个 modid（注册后该 modid 的 mixin 默认白名单：不列白名单就不注入）。 */
    public static void registerModId(String modId) {
        if (modId != null && !modId.isBlank()) {
            ALLOWED.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet());
        }
    }

    /** 该 modid 是否已注册（未注册 ⇒ 我们完全不干预它的 mixin）。 */
    public static boolean isRegistered(String modId) {
        return modId != null && ALLOWED.containsKey(modId);
    }

    /** 把某个 mixin 加入白名单（隐含注册该 modid）。 */
    public static void allow(String modId, String mixinFqcn) {
        if (modId == null || mixinFqcn == null) {
            return;
        }
        registerModId(modId);
        ALLOWED.get(modId).add(mixinFqcn);
    }

    /** 该 mixin 是否在白名单里。 */
    public static boolean isAllowed(String modId, String mixinFqcn) {
        final Set<String> set = ALLOWED.get(modId);
        return set != null && set.contains(mixinFqcn);
    }

    /** 注销一个 modid（插件卸载时调用 ⇒ 之后回到"不干预"）。 */
    public static void unregisterModId(String modId) {
        if (modId != null) {
            ALLOWED.remove(modId);
        }
    }

    public static Set<String> registeredModIds() {
        return Set.copyOf(ALLOWED.keySet());
    }

    public static int allowedCount(String modId) {
        final Set<String> set = ALLOWED.get(modId);
        return set == null ? 0 : set.size();
    }
}
