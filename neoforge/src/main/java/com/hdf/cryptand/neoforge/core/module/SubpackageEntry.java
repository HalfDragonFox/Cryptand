/**
 * ===== 子包隔离入口（SubpackageEntry，2026-09-07） =====
 *
 * 子包（powergrid / railway / cee / create / aeronautics / cryptandsable /
 * cryptandsable_compat / sable ...）的【统一生命周期接口】——core 不编译期引用任何
 * 子包类：core 仅持子包入口类的【全限定名字符串表】（SubpackageLoader），运行时反射
 * 加载；实现类位于各自子包内（随子包删除而消失）：
 *
 * <pre>
 *   删除某子包源码目录 → 其 Entry 类不存在 → loader 的 Class.forName 失败 → catch 跳过
 *   → 其余子包与 core 照常编译运行（子包间耦合另按模块边界文档化）。
 * </pre>
 *
 * 约定：实现类需提供 {@code public static final <X> INSTANCE}（loader 反射读取）；
 * 生命周期钩子按 {@link SubpackageLoader} 调度时序调用。
 */
package com.hdf.cryptand.neoforge.core.module;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/** 子包隔离入口（默认全空；子包实现所需钩子）。 */
public interface SubpackageEntry
        extends com.hdf.cryptand.core.module.ModuleEntry<IEventBus, ServerLevel> {
    // ⚠ 2026-08-30 用户架构（mod 内多模块的"类 mod"轻量加载）：
    // 通用加载能力已下沉 common（com.hdf.cryptand.core.module.ModuleEntry<B,L>
    // + ModuleRegistry<B,L>——依赖表/拓扑排序/循环依赖检测/传递禁用/Mixin 配置收集）。
    // 本接口仅是 neoforge 平台绑定（B=IEventBus，L=ServerLevel）——14 个子包实现
    // 保持原方法签名不变。

    /** 构造期最前：注册本子包 config spec（core 在 consumeConfigs 前调用本阶段）。 */
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

    /** 构造期内容初始化（方块/事件/payload 等）。 */
    default void init(IEventBus bus) {
    }

    /** 服务端 tick。 */
    default void tick(ServerLevel level) {
    }

    /** FMLCommonSetup 钩子。 */
    default void commonSetup() {
    }

    /** 命令挂载钩子（config 已加载后执行）。 */
    default void commands() {
    }

    /**
     * ⚠ 2026-08-30 子包依赖表（用户：类似 mcmod 的加载——可配置依赖 id 表）：
     * 返回本子包依赖的其它子包 id 列表（默认空 = 无依赖）。加载语义：
     * <ul>
     *   <li>被依赖子包【先加载】（拓扑排序）；</li>
     *   <li>任一依赖【未启用】（enabled=false / 未加载 / 自身失败）→ 本子包也不加载
     *       （传递禁用）；</li>
     *   <li>出现【循环依赖】→ 整个循环链上的子包全部不加载并报错。</li>
     * </ul>
     * 需要跨子包开关时（如 Sable 集成），依赖方声明 {@code "sable"} 并在运行时
     * 用 {@link SubpackageRegistry#isEnabled(String)} 查询——不直接引用对方配置类
     * （保持子包可删除）。
     */
    default java.util.List<String> dependencies() {
        return java.util.List.of();
    }

    /**
     * ⚠ 2026-08-30 子包主类声明本子包的 Mixin 配置（用户：子包全部采用注册方式，
     * 类似独立 mod 加载（非真独立）——参考 mixin：子包主类注册，通过一个函数
     * 加载并返回可加载的 mixin 包）。
     * <p>返回本子包的 mixin 配置资源名（如 "cryptand.powergrid.mixins.json"）；
     * 统一由 {@link SubpackageLoader#loadableMixinConfigs()} 在加载时收集 +
     * 按 enabled 判定，返回【可加载的 mixin 配置列表】（供 mixin 框架/诊断）。
     * 默认空（子包无 mixin）。
     */
    default java.util.List<String> mixinConfigs() {
        return java.util.List.of();
    }
}
