/**
 * ===== 子包注册表（neoforge 平台绑定 · 2026-08-30 用户架构） =====
 *
 * 通用「类 mod」轻量加载能力已下沉 common：
 * {@link com.hdf.cryptand.core.module.ModuleRegistry}&lt;B,L&gt;（依赖表 / 拓扑排序 /
 * 循环依赖检测 / 传递禁用 / Mixin 配置收集）+ {@link com.hdf.cryptand.core.module.ModuleEntry}&lt;B,L&gt;。
 * 本类仅做 neoforge 平台绑定（B=IEventBus，L=ServerLevel），保持原静态 API，
 * 供子包入口（SubpackageEntry）与核心调度（CryptandNeoForge）使用。
 *
 * <pre>
 *   SubpackageRegistry.register(id, cond, enabled, deps, init, tick, setup, cmd, mixins)
 *   SubpackageRegistry.initAll(bus)   // 内部先 resolveDependencies（拓扑+环检测+传递禁用）
 * </pre>
 */
package com.hdf.cryptand.neoforge.core.module;

import com.hdf.cryptand.core.module.ModuleRegistry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 子包注册表（委托 common ModuleRegistry&lt;IEventBus, ServerLevel&gt;）。 */
public final class SubpackageRegistry {

    private SubpackageRegistry() {
    }

    /** common 通用模块加载器实例（平台绑定：总线=IEventBus，世界=ServerLevel）。 */
    private static final ModuleRegistry<IEventBus, ServerLevel> REG = new ModuleRegistry<>();

    static {
        // 报警走 mod 日志（循环依赖/依赖缺失/未启用 → 明确报错）
        try {
            REG.setWarner(msg -> com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER
                    .error("[Subpackage] {}", msg));
        } catch (final Throwable ignored) {
        }
    }

    /** 底层通用加载器（诊断/扩展用）。 */
    public static ModuleRegistry<IEventBus, ServerLevel> registry() {
        return REG;
    }

    // ===== 注册 =====

    /** 完整注册（含依赖表与 Mixin 配置——类 mod 声明）。 */
    public static synchronized void register(final String id, final String conditionDesc,
                                             final BooleanSupplier enabled,
                                             final java.util.List<String> dependencies,
                                             final Consumer<IEventBus> init,
                                             final Consumer<ServerLevel> tick,
                                             final Runnable commonSetup,
                                             final Runnable commands,
                                             final java.util.List<String> mixinConfigs) {
        REG.register(id, conditionDesc, enabled, dependencies, init, tick, commonSetup, commands,
                mixinConfigs);
    }

    /** 兼容注册（无依赖表/Mixin 配置）。 */
    public static synchronized void register(final String id, final String conditionDesc,
                                             final BooleanSupplier enabled,
                                             final Consumer<IEventBus> init,
                                             final Consumer<ServerLevel> tick,
                                             final Runnable commonSetup,
                                             final Runnable commands) {
        register(id, conditionDesc, enabled, java.util.List.of(), init, tick, commonSetup, commands,
                java.util.List.of());
    }

    // ===== 查询 =====

    public static boolean has(final String id) {
        return REG.has(id);
    }

    /** 当前是否加载（已注册 && 未失败（依赖/循环）&& 启用条件为真）。 */
    public static boolean isEnabled(final String id) {
        return REG.isEnabled(id);
    }

    /** 是否因依赖缺失/未启用/循环依赖而失败。 */
    public static boolean isFailed(final String id) {
        return REG.isFailed(id);
    }

    /** 已注册子包 id 集合（注册顺序）。 */
    public static java.util.List<String> ids() {
        return java.util.List.copyOf(REG.ids());
    }

    /** 拓扑加载序（诊断）。 */
    public static java.util.List<String> loadOrder() {
        return REG.loadOrder();
    }

    // ===== 核心调度入口（CryptandNeoForge 调用） =====

    /** 构造期：依赖解析（拓扑 + 循环检测 + 传递禁用）→ enabled 子包 init。 */
    public static void initAll(final IEventBus bus) {
        try {
            REG.resolveDependencies();
        } catch (final Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                    "[Subpackage] 依赖解析失败: {}", t.toString());
        }
        REG.initAll(bus);
    }

    /** 服务端 tick：enabled 子包的 tick。 */
    public static void tickAll(final ServerLevel level) {
        REG.tickAll(level);
    }

    /** FMLCommonSetup：enabled 子包的 commonSetup 钩子。 */
    public static void commonSetupAll() {
        REG.commonSetupAll();
    }

    /** 命令挂载（ModConfigEvent 后）：enabled 子包的 commands 钩子。 */
    public static void commandsAll() {
        REG.commandsAll();
    }

    // ===== Mixin 配置（类 mod：子包主类声明） =====

    /** 可加载的 Mixin 配置（enabled 且未失败子包声明——统一加载函数）。 */
    public static java.util.List<String> loadableMixinConfigs() {
        return REG.loadableMixinConfigs();
    }

    /** 全部声明（id → mixin 配置；诊断）。 */
    public static java.util.Map<String, java.util.List<String>> declaredMixinConfigs() {
        return REG.declaredMixinConfigs();
    }

    // ===== mixin 注入决策记录（供 core 注册端 debug 汇总打印） =====

    /** mixin 决策表：FQCN → 是否注入（各 mixin plugin shouldApplyMixin 出口记录）。 */
    private static final java.util.Map<String, Boolean> MIXIN_DECISIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** mixin plugin 出口调用：记录某 mixin 的注入判定结果。 */
    public static void recordMixin(final String mixinFqcn, final boolean applied) {
        if (mixinFqcn != null) {
            MIXIN_DECISIONS.put(mixinFqcn, applied);
        }
    }

    // ===== 注册端诊断打印（common.toml: debugSubpackageDump=true 时） =====

    /** 一次性打印标志（config 多个 Loading 事件只打一次）。 */
    private static volatile boolean dumped = false;

    /**
     * ★ core 注册端诊断：打印已注册子包列表（含启用状态/依赖/失败原因）与 mixin
     * 注入决策表。由核心在 ModConfigEvent（config 已加载）后调用（可多次）；
     * 仅当 {@code common.toml: debugSubpackageDump=true} 时输出一次（默认关闭）。
     */
    public static void debugDumpOnce() {
        try {
            if (!com.hdf.cryptand.neoforge.core.config.ConfigCore.DEBUG_SUBPACKAGE_DUMP.get()) {
                return; // 关闭：不打印（dumped 不置位——后续 Loading 仍可触发）
            }
        } catch (final Throwable t) {
            return;
        }
        if (dumped) return;
        dumped = true;
        final org.apache.logging.log4j.Logger log =
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER;
        final java.util.List<String> order = loadOrder();
        log.info("[Subpackage] ===== 子包注册诊断 ({} subpackages) =====", ids().size());
        for (final String id : ids()) {
            final ModuleRegistry.Entry<IEventBus, ServerLevel> e = REG.entry(id);
            log.info("[Subpackage]   id={} enabled={} failed={} deps={} | cond: {}",
                    id, isEnabled(id) ? "Y" : "N", isFailed(id) ? "Y" : "-",
                    e != null ? e.dependencies : "-",
                    e != null && e.conditionDesc != null ? e.conditionDesc : "(none)");
        }
        log.info("[Subpackage] ===== 拓扑加载序 =====");
        log.info("[Subpackage]   {}", String.join(" -> ", order));
        log.info("[Subpackage] ===== mixin 注入决策 ({} recorded) =====", MIXIN_DECISIONS.size());
        MIXIN_DECISIONS.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(en -> log.info("[Subpackage]   mixin {} -> {}",
                        en.getKey(), en.getValue() ? "INJECTED" : "SKIPPED"));
        log.info("[Subpackage] ===== mixin 注册面（mods.toml 静态声明） =====");
        try (java.io.InputStream is = SubpackageRegistry.class.getClassLoader()
                .getResourceAsStream("META-INF/neoforge.mods.toml")) {
            if (is != null) {
                final String toml = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                final java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("config\\s*=\\s*\"([^\"]+)\"").matcher(toml);
                while (m.find()) {
                    log.info("[Subpackage]   mixin-config {}", m.group(1));
                }
            }
        } catch (final Throwable ignored) {
        }
    }
}
