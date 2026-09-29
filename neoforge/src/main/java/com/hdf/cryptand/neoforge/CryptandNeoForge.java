package com.hdf.cryptand.neoforge;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.core.config.ConfigCore;
import com.hdf.cryptand.neoforge.core.library.ComponentLibrary;
import com.hdf.cryptand.neoforge.core.module.SubpackageRegistry;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.hdf.cryptand.neoforge.core.module.SubpackageLoader;

@Mod(Cryptand.MOD_ID)
public class CryptandNeoForge {
    public static final Logger WAF_LOGGER = LogManager.getLogger(Cryptand.MOD_ID);

    public CryptandNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // Config 按主题注册（config/cryptand/*.toml，2026-08-22 per-mod 拆分）。
        // ⚠ 必须全量注册 ALL_SPECS——未注册 spec 的 ConfigValue.get() 在 tick 抛
        // "Cannot get config value before config is loaded"（AC 源/每 tick 读配置）。
        // ★ 2026-09-07 【子包隔离】core 只注册自身配置（core/debug/circuit 域）；各子包
        //   config 由 SubpackageLoader 反射加载其 SubpackageEntry.registerConfigs() 注册
        //   （子包被删除 → entry 缺失 → 自动跳过，core 零编译引用子包类）。
        ConfigCore.register();
        ConfigCircuit.register();
        // ★ 2026-09-07 【子包隔离加载】反射加载全部存在的子包入口（entry 内部 registerConfigs
        //   + 注册进 SubpackageRegistry；缺失子包自动跳过）。必须早于 consumeConfigs。
        SubpackageLoader.loadAll();
        CryptandRegistries.consumeConfigs(modContainer);
        WAF_LOGGER.info("[Config] registered {} cryptand config specs",
                CryptandRegistries.allConfigs().size());

        // ⚠ 2026-08-30 子包内容全部转移（用户：主类凡子包内容全部转移到子包）
        // ——主类不再引用任何子包类（cryptandsable 网络包 / powergrid 客户端渲染 /
        // railway 客户端渲染均已移入各子包 Entry.init / Module.register）。
        //
        // ⚠ 2026-08-30 子包内容初始化（构造期——必须早于所有注册事件）：
        // RegisterRenderers / RegisterEvent 等 MOD 总线事件在 ModConfigEvent 之前，
        // 若把 initAll 延迟到 ModConfigEvent → 方块/BE 未注册 → 客户端渲染注册
        // 访问 DeferredHolder 抛 "Trying to access unbound value"（启动崩溃，已修）。
        // 注意：构造期各子包 config spec 尚未加载 → enabled() 回退 true（默认启用）；
        // 配置 false 的"内容不加载"由各子包 enabled() 的配置预读保证（见各
        // Module.shouldLoad 的 preload 分支）。
        SubpackageRegistry.initAll(modEventBus);

        // ★ 2026-09-06 【模块化子命令注册 · 子包自治】子包命令钩子经框架在 ModConfigEvent
        //   （任一域 Loading，config 已加载）回调执行：enabled 子包才挂命令。
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent ev) -> {
            try {
                SubpackageRegistry.commandsAll();
            } catch (final Throwable ignored) {
            }
        });

        modEventBus.addListener(this::commonSetup);

        // ★ 2026-09-06 【Mixin 拦截注册表生命周期】MC 加载完成（FMLLoadCompleteEvent）后清理
        //   白名单/黑名单——名单是一次性启动期约束，清空防无用内存驻留（见 MixinGateRegistry）。
        //   子包可在构造期/静态初始化期注册（registerAllow/registerDeny，默认 cryptand 拦截器组）。
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent ev) -> {
            MixinGateRegistry.clear();
        });

        // 服务端 tick：模块 tick 分发（经子包框架：各子包自持 enabled 门控）。
        // SQLite 兜底打开已迁 powergrid 子包（PowergridGameEvents）；测量系统 tick
        // 由 PowergridModule.tick 经框架分发（2026-09-07 去主类重复直调）。
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post ev) -> {
            MinecraftServer srv = ev.getServer();
            if (srv == null) return;
            for (ServerLevel lvl : srv.getAllLevels()) {
                SubpackageRegistry.tickAll(lvl);
            }
        });

        // 命令 /cryptand schematic：导出电路原理图（Markdown + Mermaid）
        //   /cryptand schematic          → 导出全部电路到 circuit_schematic.md
        //   /cryptand schematic here     → 导出【玩家面向方块】所在电路网络
        //   /cryptand schematic <x y z>  → 导出【指定坐标】所在电路网络
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent ev) -> {
            // ★ 2026-09-06 【模块化子命令注册】根 /cryptand 构建为变量；主类存量子命令
            //   （schematic/export/graph/convert/sable/...）原样挂入；注册器登记的
            //   子包子命令（CryptandRegistries 登记，如 /cryptand sable、/cryptand csable）
            //   经 mergeSubcommandsInto 合并附加——子包无需改主类即可挂命令树。
            var cryptandRoot = net.minecraft.commands.Commands.literal("cryptand")
                            .then(cryptand$libraryCommand());
                            // ★ 2026-09-07 【子包隔离迁移】schematic/export/graph/convert/
                            //   transformer/device 命令已迁 powergrid 子包（PowergridCommands/
                            //   PowergridExportCommands 经 CryptandRegistries 挂根；删除 powergrid
                            //   目录 → 命令整体消失，core 零编译引用）。library 为 core 命令。
            // ★ 2026-09-06 【模块化子命令注册】注册器子命令（子包登记）合并进根树
            CryptandRegistries
                    .mergeSubcommandsInto(cryptandRoot);
            ev.getDispatcher().register(cryptandRoot);
        });

        // 服务端停止：停内核仿真接口（核心与 MC 解耦，可独立运行/重启）。
        // CryptandTopologyManager 停止已迁 powergrid 子包（PowergridGameEvents）；
        // SimHttpServer 停止已迁 simserver 子包（SimserverEntry）。
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent ev) -> {
                    com.hdf.cryptand.circuitsimulation.core.SimulationCore.get().stop();
                });
        // 服务端启动：启动仿真核心（分配器线程 A + 异步交互管理类线程 B + 网络操作
        // 记录表；MC 与独立运行共用同一核心，进一步拆分核心与 MC 绑定）。
        // SimHttpServer 启动（本地内核仿真 HTTP 接口，供 test/ 前端调用内核仿真）
        // 已迁 simserver 子包（SimserverEntry）。
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStartedEvent ev) -> {
                    try {
                        // ⚠ 2026-08-30 核心引擎总闸（用户"关闭所有支持"）：仿真接管与
                        // 求解器替换皆关 → 不启动内核（零线程/零每 tick 开销）。
                        // 用 core 的 ConfigLoad 预读（零子包编译依赖）。
                        final boolean sim =
                                com.hdf.cryptand.neoforge.core.config.ConfigLoad
                                        .preloadBoolean("circuit", "enableCryptandSimulation", true)
                                || com.hdf.cryptand.neoforge.core.config.ConfigLoad
                                        .preloadBoolean("circuit", "enableCryptandSolver", true);
                        if (sim) {
                            com.hdf.cryptand.circuitsimulation.core.SimulationCore.get().start();
                        } else {
                            WAF_LOGGER.info("[Core] 核心引擎未启动（enableCryptandSimulation/Solver 均为 false）");
                        }
                    } catch (Throwable ignored) {
                    }
                });
        // ★ 2026-09-07 【子包隔离迁移】以下 GAME 监听已整体迁出主类 → powergrid 子包
        //   PowergridGameEvents（@EventBusSubscriber 自持注册；删除 powergrid 目录 → 类
        //   消失 → 监听自动消失，core 零引用）：
        //   - PlayerLoggedInEvent（加入强制同步自管导线图）
        //   - BlockEvent.EntityPlaceEvent（元件放置即建网）
        //   - LevelEvent.Load/Unload/Save（SQLite + 仿真电路文件夹 + 自管导线拓扑 +
        //     端子注册表生命周期）
        // ⚠ 2026-08-30 主类子包内容全部转移（用户：主类凡子包内容全部转移到子包）：
        // 客户端渲染注册（powergrid 单相异步电机 / railway 铁路）已移入各自子包
        // Module.register（子包内判定 Dist.CLIENT）——主类不再引用子包客户端类，
        // 且 enabled=false 时随子包一并跳过注册。
    }

    /** /cryptand library：元件库管理命令树（list/info/reload/set）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>
            cryptand$libraryCommand() {
        return net.minecraft.commands.Commands.literal("library")
                .requires(s -> s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("list")
                        .executes(ctx -> cryptand$libraryList(ctx.getSource())))
                .then(net.minecraft.commands.Commands.literal("info")
                        .then(net.minecraft.commands.Commands.argument("name",
                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> cryptand$libraryInfo(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "name")))))
                .then(net.minecraft.commands.Commands.literal("reload")
                        .executes(ctx -> cryptand$libraryReload(ctx.getSource())))
                .then(net.minecraft.commands.Commands.literal("set")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .then(net.minecraft.commands.Commands.argument("name",
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .word())
                                        .executes(ctx -> cryptand$librarySet(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates
                                                        .BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "name"),
                                                ""))
                                        .then(net.minecraft.commands.Commands.argument("params",
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .greedyString())
                                                .executes(ctx -> cryptand$librarySet(ctx.getSource(),
                                                        net.minecraft.commands.arguments.coordinates
                                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        com.mojang.brigadier.arguments.StringArgumentType
                                                                .getString(ctx, "name"),
                                                        com.mojang.brigadier.arguments.StringArgumentType
                                                                .getString(ctx, "params")))))));
    }

    /** /cryptand library list：列出全部库条目。 */
    private static int cryptand$libraryList(net.minecraft.commands.CommandSourceStack src) {
        var subs = ComponentLibrary.get().all();
        if (subs.isEmpty()) {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "元件库为空（config/cryptand/library/ 下无 .lib/.spice/.cir 文件）"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("元件库条目（" + subs.size() + "）：");
        for (com.hdf.cryptand.circuitsimulation.lib.SpiceSubcircuit sub : subs) {
            sb.append("\n").append(sub.name).append(" 端口=").append(sub.portCount())
                    .append(" 元件=").append(sub.elements.size());
        }
        var models = ComponentLibrary.get().allModels();
        if (!models.isEmpty()) {
            sb.append("\n模型（.model，内部实现可替换）：");
            for (com.hdf.cryptand.circuitsimulation.lib.SpiceModel m : models) {
                sb.append("\n  ").append(m.name).append(" [").append(m.type)
                        .append("] ").append(m.params);
            }
        }
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(sb.toString()), false);
        return 1;
    }

    /** /cryptand library info <name>：显示条目详情。 */
    private static int cryptand$libraryInfo(net.minecraft.commands.CommandSourceStack src, String name) {
        com.hdf.cryptand.circuitsimulation.lib.SpiceSubcircuit sub =
                ComponentLibrary.get().get(name);
        if (sub != null) {
            StringBuilder sb = new StringBuilder("条目 " + sub.name + "\n端口: " + sub.pins);
            if (!sub.defaultParams.isEmpty()) {
                sb.append("\n默认参数: ").append(sub.defaultParams);
            }
            for (com.hdf.cryptand.circuitsimulation.lib.SpiceElement el : sub.elements) {
                sb.append("\n  ").append(el.describe());
            }
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(sb.toString()), false);
            return 1;
        }
        com.hdf.cryptand.circuitsimulation.lib.SpiceModel model =
                ComponentLibrary.get().getModel(name);
        if (model != null) {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "模型 " + model.name + " [" + model.type + "]\n参数: " + model.params), false);
            return 1;
        }
        src.sendFailure(net.minecraft.network.chat.Component.literal("库中无条目/模型: " + name));
        return 0;
    }

    /** /cryptand library reload：重新加载元件库。 */
    private static int cryptand$libraryReload(net.minecraft.commands.CommandSourceStack src) {
        int n = ComponentLibrary.reload().size();
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "元件库已重新加载，共 " + n + " 条目"), true);
        return 1;
    }

    /**
     * /cryptand library set <pos> <name> [k=v,...]：把网表条目设置到【支持元件库的
     * 目标方块】（经 core.api 服务注册表 CryptandServices 查 CryptandLibraryTarget
     * 实现——powergrid 子包的可编程元件方块注册实现；无实现 → 提示不支持。
     * ★ 2026-09-07 core API 化：core 不再 import powergrid 方块类）。
     */
    private static int cryptand$librarySet(net.minecraft.commands.CommandSourceStack src,
                                           net.minecraft.core.BlockPos pos, String name,
                                           String paramsText) {
        net.minecraft.world.level.Level level = src.getLevel();
        if (level == null) return 0;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "该位置没有方块: " + pos.toShortString()));
            return 0;
        }
        final com.hdf.cryptand.circuitsimulation.lib.SpiceSubcircuit sub =
                ComponentLibrary.get().get(name);
        if (sub == null) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("库中无条目: " + name));
            return 0;
        }
        java.util.Map<String, Double> params = new java.util.HashMap<>();
        if (paramsText != null && !paramsText.isEmpty()) {
            for (String part : paramsText.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    try {
                        params.put(part.substring(0, eq).trim(),
                                Double.parseDouble(part.substring(eq + 1).trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        for (com.hdf.cryptand.neoforge.core.api.CryptandLibraryTarget t
                : com.hdf.cryptand.neoforge.core.api.CryptandServices.all(
                        com.hdf.cryptand.neoforge.core.api.CryptandLibraryTarget.class)) {
            try {
                if (t.accepts(be)) {
                    String desc = t.apply(be, sub, params);
                    if (desc != null && desc.startsWith("!")) {
                        src.sendFailure(net.minecraft.network.chat.Component.literal(
                                desc.substring(1)));
                        return 0;
                    }
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                            "已设置 " + pos.toShortString() + " → " + name
                                    + (desc == null || desc.isEmpty() ? "" : "，" + desc)), true);
                    return 1;
                }
            } catch (Throwable ex) {
                src.sendFailure(net.minecraft.network.chat.Component.literal(
                        "设置失败: " + ex));
                return 0;
            }
        }
        src.sendFailure(net.minecraft.network.chat.Component.literal(
                "该位置没有支持元件库的目标方块: " + pos.toShortString()
                        + "（" + be.getClass().getSimpleName() + "）"));
        return 0;
    }

    /**
     * 安装 CryptandSable 物理核心（原空壳 {@code CryptandSableMod#CryptandSableMod(IEventBus)}
     * 逻辑迁移：空壳 @Mod("sable") 已删除，官方 sable 以惰性 mod 加载但被 ASM 禁用初始化）。
     *
     * <p>职责：{@code enableSableSupport=true} 时挂载 CryptandSable 核心生命周期
     * （Level load/unload + ServerTick 心跳）。官方 block property 静态注册在
     * {@link CryptandNeoForge#CryptandNeoForge(IEventBus, ModContainer)} 构造期触发
     * （早于 NewRegistryEvent）。
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        WAF_LOGGER.info("Cryptand Common Setup Start");
        // 求解后端：配置直接写名称（double / float / 未来扩展类型）
        try {
            boolean ok = com.hdf.cryptand.circuitsimulation.solver.Solvers
                    .setBackend(ConfigCircuit.SOLVER_BACKEND.get());
            WAF_LOGGER.info("Solver backend: {}",
                    com.hdf.cryptand.circuitsimulation.solver.Solvers.backend);
            if (!ok) {
                WAF_LOGGER.warn("Unknown solverBackend='{}' -> fallback double",
                        ConfigCircuit.SOLVER_BACKEND.get());
            }
        } catch (Throwable t) {
            WAF_LOGGER.warn("Solver backend init failed: {}", t.toString());
        }
        // ★ 2026-09-08 硬件 I/O 子包加载（懒加载生命周期）：
        //   核心配置 → common GameInputConfig（common 零依赖平台配置，经注入桥接）；
        //   平台只负责【按配置加载子包】：GameInputProvider.load() 注册 SDL2 后端
        //   （SDL2 库首次使用才加载）、SerialPortProvider.load() 就绪串口子包——
        //   使用前需加载方可正常初始化使用；未使用时零资源占用（可 unload 释放）。
        com.hdf.cryptand.gameinput.GameInputConfig.configure(
                com.hdf.cryptand.neoforge.gameinput.config.ConfigGameInput
                        .ENABLE_GAME_INPUT.get());
        com.hdf.cryptand.gameinput.GameInputProvider.load();
        com.hdf.cryptand.serial.SerialPortProvider.load();
        WAF_LOGGER.info("Hardware I/O: gameInput enabled={}, serial loaded={}",
                com.hdf.cryptand.gameinput.GameInputConfig.isInputEnabled(),
                com.hdf.cryptand.serial.SerialPortProvider.isLoaded());
        // ⚠ CryptandSable 物理核心安装：经子包框架 commonSetup 钩子分发（cryptandsable
        //    子包 enabled = enableSableSupport && enableCryptandSableCore，false → 静默跳过）。
        SubpackageRegistry.commonSetupAll();
        // ★ 2026-09-07 注册端诊断（commonSetup 后：全部 config 已加载 → enabled 状态准确；
        //   debug.toml: debugSubpackageDump=true 才打印一次）
        SubpackageRegistry.debugDumpOnce();
        WAF_LOGGER.info("Cryptand Common End");
    }
}