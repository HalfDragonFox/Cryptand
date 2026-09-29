package com.hdf.cryptand.core.module;

/**
 * ===== 通用模块入口（common · 2026-08-30 用户定稿） =====
 *
 * <b>目的：为 mod 内提供多模块的「类 mod」轻量加载</b>——同一 mod 内多个功能模块
 * （powergrid / railway / cee / sable / simulator / inc ...）像独立 mod 一样：
 * <ul>
 *   <li>各自有主类（实现本接口 + {@code INSTANCE} 常量 + 平台注解标注）；</li>
 *   <li>声明【启用条件】（enabled）、【依赖表】（dependencies）、【Mixin 配置】；</li>
 *   <li>由 {@link ModuleRegistry} 统一发现/排序/调度（生命周期钩子）。</li>
 * </ul>
 * 与本接口无关 MC：泛型 {@code <B>} = 平台总线类型（NeoForge IEventBus / Fabric
 * 事件总线 / 独立仿真器 null），{@code <L>} = 平台世界类型（ServerLevel / null）。
 *
 * @param <B> 平台总线类型（init 钩子参数）
 * @param <L> 平台世界类型（tick 钩子参数）
 */
public interface ModuleEntry<B, L> {

    /** 构造期最前：注册本模块配置（spec）。 */
    default void registerConfigs() {
    }

    /** 启用条件（每次调度时求值；false → 内容完全不初始化）。 */
    default boolean enabled() {
        return true;
    }

    /** 启用条件的人类可读说明（诊断打印）。 */
    default String conditionDesc() {
        return "(always)";
    }

    /**
     * 依赖的模块 id 列表（类 mod 依赖表）。加载语义：
     * 被依赖者先加载（拓扑排序）；任一依赖未启用/失败 → 本模块也不加载（传递）；
     * 出现循环依赖 → 整个循环链全部不加载并报错。
     */
    default java.util.List<String> dependencies() {
        return java.util.List.of();
    }

    /** 构造期内容初始化（方块/物品/事件/payload 注册）。 */
    default void init(B bus) {
    }

    /** 服务端 tick。 */
    default void tick(L level) {
    }

    /** CommonSetup 钩子。 */
    default void commonSetup() {
    }

    /** 命令挂载钩子（配置加载后执行）。 */
    default void commands() {
    }

    /** 本模块声明的 Mixin 配置（资源名；由加载器统一收集/判定）。 */
    default java.util.List<String> mixinConfigs() {
        return java.util.List.of();
    }
}
