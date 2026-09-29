/**
 * ===== 核心 Mixin 拦截注册表（2026-09-06，per-modId 拦截器模型） =====
 *
 * 统一管理 Cryptand 自身 mixin 的【核心级注入约束】，供全部 mixin plugin
 * （CryptandMixinPlugin / SableMixinPlugin / SableCompatMixinPlugin /
 * CryptandSableGateMixinPlugin / SableDebugMixinPlugin ...）在 shouldApplyMixin
 * 判定链顶部查询：
 *
 * <pre>
 *   plugin.shouldApplyMixin(...) {
 *       if (MixinGateRegistry.isDenied(mixinClassName))  return false; // 黑名单：硬禁
 *       if (MixinGateRegistry.isAllowed(mixinClassName)) return true;  // 白名单：硬放行
 *       ...plugin 自身条件（core 总闸 / enableSableSupport / ...）...
 *   }
 * </pre>
 *
 * <p><b>per-modId 拦截器模型</b>：
 * <ul>
 *   <li>名单按【modId → mixin 全限定名集合】分组——一个 modId 即一个"拦截器"；</li>
 *   <li>core <b>默认注册本 modId（"cryptand"）拦截器</b>：无参 registerXxx/isXxx 均落在
 *       cryptand 组（供 cryptand 各子包与全部 plugin 使用）；</li>
 *   <li>其它 mod / 其它体系可显式注册自己的拦截器组并按其 modId 注册/判定
 *       （registerDeny(modId, fqcn) / isDenied(modId, fqcn)），实现"根据 modid 进行
 *       白名单与黑名单拦截"。</li>
 * </ul>
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li><b>黑名单 registerDeny</b>——任何 plugin 条件之前拒绝注入。用途：核心级停用某
 *       mixin（无需改 mixins.json，可多子包共同约束）；</li>
 *   <li><b>白名单 registerAllow</b>——任何 plugin 条件之前放行注入。用途：子包声明
 *       "该 mixin 与本 mod 生命周期强绑定、不受其它子包门控影响"；例：官方 sable 联动层
 *       mixin 在核心总闸（enableCryptandSableCore=false 拦截 cryptandsable 相关注入）下
 *       仍应注入 → 子包初始化时 registerAllow 放行。</li>
 * </ul>
 *
 * <p><b>注册时机</b>：子包初始化（mod 构造 / 静态初始化）调用 registerXxx。
 * ⚠ 注意 mixin apply 可能早于 mod 构造（target 类加载即判定）：早期应用的 mixin
 * 判定时表可能尚空 → 回退 plugin 自身条件；注册表生效窗口 = 注册后 ~ 清理前
 * （主要覆盖构造期之后惰性加载的 target 类）。
 *
 * <p><b>清理</b>：MC 加载完成（commonSetup 后）由核心调用 {@link #clear()}——
 * 名单是一次性启动期约束，清空防无用内存驻留（此后新应用的 mixin 回退 plugin 自身条件）。
 */
package com.hdf.cryptand.neoforge.core.registry;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 核心 Mixin 拦截注册表（per-modId 拦截器；纯静态；线程安全；无 MC 依赖，类加载期可用）。 */
public final class MixinGateRegistry {

    private MixinGateRegistry() {
    }

    /** 默认拦截器 modId = 本 mod（cryptand）——core 默认带本 modid 的拦截器。 */
    public static final String DEFAULT_MOD_ID = "cryptand";

    /** 白名单：modId → mixin FQCN 集（plugin 判定链顶部放行，硬放行，先于 plugin 自身条件）。 */
    private static final Map<String, Set<String>> ALLOWED = new ConcurrentHashMap<>();

    /** 黑名单：modId → mixin FQCN 集（plugin 判定链顶部拒绝，硬禁，最高优先）。 */
    private static final Map<String, Set<String>> DENIED = new ConcurrentHashMap<>();

    private static Set<String> setOf(final Map<String, Set<String>> map, final String modId) {
        return map.computeIfAbsent(modId, k -> new HashSet<>());
    }

    // ===== 注册（默认 cryptand 拦截器组） =====

    /** 白名单注册（默认 cryptand 组；mixin 全限定类名；幂等）。 */
    public static void registerAllow(final String mixinFqcn) {
        registerAllow(DEFAULT_MOD_ID, mixinFqcn);
    }

    /** 批量白名单注册（默认 cryptand 组；幂等）。 */
    public static void registerAllow(final String... mixinFqcns) {
        if (mixinFqcns == null) return;
        for (final String f : mixinFqcns) {
            registerAllow(DEFAULT_MOD_ID, f);
        }
    }

    /** 黑名单注册（默认 cryptand 组；mixin 全限定类名；幂等）。 */
    public static void registerDeny(final String mixinFqcn) {
        registerDeny(DEFAULT_MOD_ID, mixinFqcn);
    }

    /** 批量黑名单注册（默认 cryptand 组；幂等）。 */
    public static void registerDeny(final String... mixinFqcns) {
        if (mixinFqcns == null) return;
        for (final String f : mixinFqcns) {
            registerDeny(DEFAULT_MOD_ID, f);
        }
    }

    // ===== 注册（按 modId 的拦截器组——支持其它体系按 modid 拦截） =====

    /** 白名单注册到指定 modId 拦截器组（mixin 全限定类名；幂等；组惰性创建）。 */
    public static void registerAllow(final String modId, final String mixinFqcn) {
        if (mixinFqcn == null || mixinFqcn.isBlank()) return;
        final String id = modId == null ? DEFAULT_MOD_ID : modId;
        synchronized (ALLOWED) {
            setOf(ALLOWED, id).add(mixinFqcn);
        }
    }

    /** 黑名单注册到指定 modId 拦截器组（mixin 全限定类名；幂等；组惰性创建）。 */
    public static void registerDeny(final String modId, final String mixinFqcn) {
        if (mixinFqcn == null || mixinFqcn.isBlank()) return;
        final String id = modId == null ? DEFAULT_MOD_ID : modId;
        synchronized (DENIED) {
            setOf(DENIED, id).add(mixinFqcn);
        }
    }

    // ===== 查询（mixin plugin shouldApplyMixin 判定链顶部调用；默认 cryptand 组） =====

    /** 是否黑名单（默认 cryptand 组；是 → plugin 应直接返回 false，任何条件之前）。 */
    public static boolean isDenied(final String mixinFqcn) {
        return isDenied(DEFAULT_MOD_ID, mixinFqcn);
    }

    /** 是否白名单（默认 cryptand 组；是 → plugin 应直接返回 true，任何条件之前）。 */
    public static boolean isAllowed(final String mixinFqcn) {
        return isAllowed(DEFAULT_MOD_ID, mixinFqcn);
    }

    /** 是否黑名单（指定 modId 拦截器组）。 */
    public static boolean isDenied(final String modId, final String mixinFqcn) {
        if (mixinFqcn == null) return false;
        final Set<String> set = DENIED.get(modId == null ? DEFAULT_MOD_ID : modId);
        return set != null && set.contains(mixinFqcn);
    }

    /** 是否白名单（指定 modId 拦截器组）。 */
    public static boolean isAllowed(final String modId, final String mixinFqcn) {
        if (mixinFqcn == null) return false;
        final Set<String> set = ALLOWED.get(modId == null ? DEFAULT_MOD_ID : modId);
        return set != null && set.contains(mixinFqcn);
    }

    // ===== 生命周期 =====

    /**
     * ★ 清理全部名单（MC 加载完成后由核心调用）：白/黑名单是一次性启动期约束，
     * 清空防止无用内存驻留（此后新应用的 mixin 回退 plugin 自身条件判定）。
     */
    public static void clear() {
        ALLOWED.clear();
        DENIED.clear();
    }

    /** 当前注册量（调试）。 */
    public static int size() {
        int n = 0;
        for (final Set<String> s : ALLOWED.values()) n += s.size();
        for (final Set<String> s : DENIED.values()) n += s.size();
        return n;
    }

    /** 只读快照（调试/断言）。 */
    public static Map<String, Set<String>> snapshotAllowed() {
        return snapshot(ALLOWED);
    }

    /** 只读快照（调试/断言）。 */
    public static Map<String, Set<String>> snapshotDenied() {
        return snapshot(DENIED);
    }

    private static Map<String, Set<String>> snapshot(final Map<String, Set<String>> src) {
        final java.util.HashMap<String, Set<String>> out = new java.util.HashMap<>();
        src.forEach((k, v) -> out.put(k, Collections.unmodifiableSet(new HashSet<>(v))));
        return Collections.unmodifiableMap(out);
    }
}
