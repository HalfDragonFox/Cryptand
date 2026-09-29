/**
 * ===== Cryptand 配置 + 子命令注册器（2026-09-06，用户模块化） =====
 *
 * 【用户需求】"config 配置文件能否提供配置接口，然后比如 Core 提供 config 接口，
 * 允许子包带 config 文件夹和定义子配置，初始化时注册到 core 核心部分的 config
 * 注册器即可；然后指令也行，core 核心提供子指令注册，比如 sable 包就是 /cryptand
 * 基础上 /cryptand sable，cryptand sable 是 /cryptand csable"。
 *
 * 本类 = Core 的【单一注册闸口】：
 * <ul>
 *   <li>{@link #registerConfig(String, String, ModConfigSpec)} —— 子包注册自己的
 *       TOML 配置（配置文件放 config/cryptand/ 下，域 id 唯一）；</li>
 *   <li>{@link #registerSubcommand(String, LiteralArgumentBuilder)} —— 子包注册
 *       /cryptand 根上的子命令（如 sable / csable / eda ...）；</li>
 *   <li>{@link #registerModule(CryptandModule)} —— 模块自注册（config + subcommand
 *       一步到位，由模块自己的 shouldLoad 门控）；</li>
 * </ul>
 * <p>初始化时序：模组构造（CryptandNeoForge 构造器）→ 各模块静态区/注册方法
 * 调用本注册器登记 → {@link #consumeConfigs()}/{@link #consumeCommands()} 分别
 * 在 modContainer.registerConfig（构造期）与 RegisterCommandsEvent（事件期）消费。
 *
 * <p>【配置】：每个子配置 = 一个 TOML 文件（config/cryptand/&lt;file&gt;），子包只需
 * 提供 {@link CryptandConfigSpec}（域 id + 文件名 + ModConfigSpec）；core 统一在
 * {@link #consumeConfigs()} 时注册到 ModContainer（模组构造期全量注册，缺注册的
 * spec 的 ConfigValue.get() 在 tick 抛异常——必须全量）。
 *
 * <p>【子命令】：/cryptand 根由 core 注册一次；各子包 {@link CryptandSubCommand}
 * 提供 LiteralArgumentBuilder 子节点，core 在 RegisterCommandsEvent 时统一挂到
 * `/cryptand` 根（用户：/cryptand sable、/cryptand csable 等）。
 */
package com.hdf.cryptand.neoforge.core.registry;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CryptandRegistries {

    private CryptandRegistries() {
    }

    // =====================================================================
    //  配置注册（子包 TOML 配置：域 id → 文件 + spec）
    // =====================================================================

    /** 配置域 id（唯一）→ 注册信息。 */
    private static final Map<String, RegisteredConfig> CONFIGS = new ConcurrentHashMap<>();

    /** 配置注册记录。 */
    public record RegisteredConfig(String domain, String fileName, ModConfigSpec spec) {
    }

    /**
     * 注册一个子配置（子包初始化时调用）。
     *
     * @param domain   配置域 id（唯一；如 "sable" / "cryptandsable" / "powergrid"）
     * @param fileName 配置文件相对路径（config 目录内、config/cryptand/ 下；如 "sable.toml" 或
     *                 "cryptand/sable.toml"）
     * @param spec     子包构建的 ModConfigSpec（build 已完成；每个域一个）
     */
    public static void registerConfig(final String domain, final String fileName,
                                      final ModConfigSpec spec) {
        if (domain == null || spec == null) return;
        final String fn = fileName == null ? domain + ".toml" : fileName;
        // ★ 2026-09-06 修复：NeoForge registerConfig(Type, spec, fileName) 的 fileName 是
        //   【config 目录内相对路径】——内部会再拼 CONFIGDIR。此前拼成 "config/cryptand/..."
        //   全路径 → 实际读取 run/config/config/cryptand/*.toml（双 config 目录）→ 用户手改的
        //   config/cryptand/*.toml 全部不生效（spec 恒为默认值）。
        final String full = fn.startsWith("cryptand/")
                ? fn : "cryptand/" + fn;
        CONFIGS.computeIfAbsent(domain, k -> new RegisteredConfig(domain, full, spec));
    }

    /** 是否已注册某域。 */
    public static boolean hasConfig(final String domain) {
        return CONFIGS.containsKey(domain);
    }

    /** 当前全部已注册配置（注册顺序无关；只读快照）。 */
    public static java.util.List<RegisteredConfig> allConfigs() {
        return new java.util.ArrayList<>(CONFIGS.values());
    }

    /**
     * ★ 消费配置：模组构造期调用，把全部已注册配置注册到 ModContainer
     * （ModConfig.Type.COMMON）。调用后【不可再注册新配置】——子包必须在
     * 构造器/静态初始化阶段完成注册。
     */
    public static void consumeConfigs(final net.neoforged.fml.ModContainer modContainer) {
        for (final RegisteredConfig rc : allConfigs()) {
            try {
                modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                        rc.spec(), rc.fileName());
            } catch (final Throwable t) {
                // 重复注册忽略（同一域同 spec 已注册）
            }
        }
    }

    // =====================================================================
    //  子命令注册（/cryptand <sub>：子包挂子节点）
    // =====================================================================

    /** 子命令根节点（/cryptand 根的 .then(...) 子树）。 */
    private static final Map<String, com.mojang.brigadier.builder.LiteralArgumentBuilder<
            CommandSourceStack>> SUBCOMMANDS = new ConcurrentHashMap<>();

    /**
     * 注册一个 /cryptand 子命令（子包初始化时调用）。
     *
     * @param name    子命令名（如 "sable" / "csable" / "eda"）
     *                —— 传入字面量节点必须与该 name 一致（core 创建 literal(name)
     *                并挂 builder 内容；若传入的 builder 已是 literal(name)，直接挂树）
     * @param builder 子命令构建器（LiteralArgumentBuilder；其 .then(...) 子节点挂入）
     */
    public static void registerSubcommand(
            final String name,
            final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> builder) {
        if (name == null || builder == null) return;
        SUBCOMMANDS.put(name, builder);
    }

    /** 全部已注册子命令（快照）。 */
    public static java.util.List<com.mojang.brigadier.builder.LiteralArgumentBuilder<
            CommandSourceStack>> allSubcommands() {
        return new java.util.ArrayList<>(SUBCOMMANDS.values());
    }

    /**
     * ★ 把注册器内全部子命令【合并进已有根命令树】（存量兼容：主类既有子树保持，
     * 注册器子命令附加挂入）。RegisterCommandsEvent 内调用：先构建主类存量树到
     * 变量，再本方法合并，最后 dispatcher.register(root)。
     */
    public static void mergeSubcommandsInto(
            final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root) {
        if (root == null) return;
        for (final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> sub
                : allSubcommands()) {
            root.then(sub);
        }
    }

    /**
     * ★ 构建 /cryptand 根命令（RegisterCommandsEvent 时调用一次）：
     * 根节点 + 全部已注册子节点 + 【附加节点】（存量兼容：主类未迁移的子命令）。
     */
    @SafeVarargs
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            buildRootCommand(final com.mojang.brigadier.builder
                    .LiteralArgumentBuilder<CommandSourceStack>... extras) {
        final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("cryptand");
        for (final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> sub
                : allSubcommands()) {
            root.then(sub);
        }
        if (extras != null) {
            for (final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> extra
                    : extras) {
                if (extra != null) root.then(extra);
            }
        }
        return root;
    }

    // =====================================================================
    //  模块自注册（config + subcommand 一步；模块 shouldLoad 门控后调用）
    // =====================================================================

    /** 模块注册接口（子包主类实现；register 由自己门控/抛异常吞掉）。 */
    public interface CryptandModule {
        /** 模块名（日志/诊断）。 */
        String name();

        /** 模块是否应加载（shouldLoad 门控；false → core 跳过注册）。 */
        boolean shouldLoad();

        /** 注册本模块的 config（到 {@link #registerConfig}）+ 子命令（到 {@link #registerSubcommand}）。 */
        void register();
    }

    /** 注册一个模块（shouldLoad 门控；模块自行 register）。 */
    public static void registerModule(final CryptandModule module) {
        if (module == null || !module.shouldLoad()) return;
        try {
            module.register();
        } catch (final Throwable t) {
            // 模块注册失败不阻断主加载
        }
    }

    // =====================================================================
    //  使用示例（子包）
    // =====================================================================
    //  // 子包 config 类（如 cryptandsable）：
    //  public final class ConfigCryptandSable implements CryptandConfigSpec {
    //      public String domain() { return "cryptandsable"; }
    //      public String fileName() { return "cryptand-sable.toml"; }
    //      public ModConfigSpec spec() { return SPEC; }
    //  }
    //  // 子包初始化：
    //  CryptandRegistries.registerConfig(ConfigCryptandSable.DOMAIN,
    //          "cryptand-sable.toml", ConfigCryptandSable.SPEC);
    //  CryptandRegistries.registerSubcommand("sable", sableCommandTree());
}
