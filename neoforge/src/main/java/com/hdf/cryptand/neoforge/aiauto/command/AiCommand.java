package com.hdf.cryptand.neoforge.aiauto.command;

import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.AiTarget;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * ===== /cryptand aiauto —— AI 自动化命令（纯客户端）=====
 *
 * <pre>
 *   /cryptand aiauto                      列出全部自动化目标与开关状态
 *   /cryptand aiauto &lt;目标&gt;                列出该目标可自动化的对象
 *   /cryptand aiauto &lt;目标&gt; dump [对象]    导出文本描述（如 LDLib2 布局树）
 *   /cryptand aiauto &lt;目标&gt; shot [对象]    截图（AI 可直接读 PNG）
 *   /cryptand aiauto &lt;目标&gt; open &lt;对象&gt;    程序化打开（无需人工操作）
 *   /cryptand aiauto &lt;目标&gt; capture &lt;对象&gt; 全自动：打开→等稳定→截图→还原
 *   /cryptand aiauto &lt;目标&gt; debug          打开该目标自带的调试器
 *   /cryptand aiauto mcp [status]         真 MCP 服务器状态与接入地址（Streamable HTTP）
 *   /cryptand aiauto mcp start|stop|restart
 *   /cryptand aiauto mcp tools            列出已注册的 MCP 工具
 * </pre>
 *
 * <p>目标短名由各 mod 的自动化模块提供，当前有：{@code ldlib}。</p>
 */
public final class AiCommand {

    private AiCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cryptand")
                .then(Commands.literal("aiauto")
                        .executes(AiCommand::listTargets)
                        .then(Commands.literal("list").executes(AiCommand::listTargets))
                        .then(Commands.literal("status").executes(AiCommand::status))
                        .then(Commands.literal("prep").executes(AiCommand::prep))
                        .then(Commands.literal("log").executes(AiCommand::tailConsole))
                        .then(Commands.literal("tools").executes(AiCommand::listTools))
                        .then(Commands.literal("reload")
                                .executes(ctx -> reloadUi(ctx, null))
                                .then(Commands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> reloadUi(ctx,
                                                StringArgumentType.getString(ctx, "item")))))
                        .then(Commands.literal("dir").executes(AiCommand::openDir))
                        .then(Commands.literal("mcp")
                                .executes(AiCommand::mcpStatus)
                                .then(Commands.literal("status").executes(AiCommand::mcpStatus))
                                .then(Commands.literal("start").executes(AiCommand::mcpStart))
                                .then(Commands.literal("stop").executes(AiCommand::mcpStop))
                                .then(Commands.literal("restart").executes(AiCommand::mcpRestart))
                                .then(Commands.literal("tools").executes(AiCommand::mcpTools)))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> listItems(ctx, target(ctx)))
                                .then(Commands.literal("dump")
                                        .executes(ctx -> dump(ctx, target(ctx), null))
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .executes(ctx -> dump(ctx, target(ctx), item(ctx)))))
                                .then(Commands.literal("shot")
                                        .executes(ctx -> shot(ctx, target(ctx), null))
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .executes(ctx -> shot(ctx, target(ctx), item(ctx)))))
                                .then(Commands.literal("open")
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .executes(ctx -> open(ctx, target(ctx), item(ctx)))))
                                .then(Commands.literal("capture")
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .executes(ctx -> capture(ctx, target(ctx), item(ctx)))))
                                .then(Commands.literal("debug")
                                        .executes(ctx -> debug(ctx, target(ctx))))
                                .then(Commands.literal("plugin")
                                        .then(Commands.literal("load")
                                                .then(Commands.argument("jar", StringArgumentType.greedyString())
                                                        .executes(ctx -> pluginAdhocLoad(ctx,
                                                                StringArgumentType.getString(ctx, "jar")))))
                                        .then(Commands.literal("scan").executes(AiCommand::pluginScan))
                                        .then(Commands.literal("list").executes(AiCommand::pluginList))
                                        .then(Commands.literal("stats").executes(AiCommand::pluginStats))
                                        .then(Commands.literal("reload")
                                                .then(Commands.literal("all")
                                                        .executes(AiCommand::pluginReloadAll))
                                                .then(Commands.literal("core")
                                                        .then(Commands.argument("core",
                                                                        StringArgumentType.word())
                                                                .executes(ctx -> pluginReloadCore(ctx,
                                                                        StringArgumentType.getString(ctx, "core")))))
                                                .then(Commands.argument("id", StringArgumentType.word())
                                                        .executes(ctx -> pluginReload(ctx,
                                                                StringArgumentType.getString(ctx, "id")))))
                                        .then(Commands.literal("unload")
                                                .then(Commands.argument("id", StringArgumentType.word())
                                                        .executes(ctx -> pluginUnload(ctx,
                                                                StringArgumentType.getString(ctx, "id"))))))
                                .then(Commands.literal("act")
                                        .then(Commands.argument("action", StringArgumentType.word())
                                                .executes(ctx -> act(ctx, target(ctx),
                                                        StringArgumentType.getString(ctx, "action"), java.util.List.of()))
                                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                                        .executes(ctx -> act(ctx, target(ctx),
                                                                StringArgumentType.getString(ctx, "action"),
                                                                java.util.List.of(StringArgumentType
                                                                        .getString(ctx, "args").split(" "))))))))));
    }

    /** /cryptand aiauto status —— 打印并刷新 AI 会话状态（阶段/世界/玩家/屏幕） */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        com.hdf.cryptand.neoforge.aiauto.AiSession.event("status.query", "玩家查询");
        for (String line : com.hdf.cryptand.neoforge.aiauto.AiSession.statusText().split("\\n")) {
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }
        src.sendSuccess(() -> Component.literal("[aiauto] 状态文件：" 
                + com.hdf.cryptand.neoforge.aiauto.AiSession.statusFile()), false);
        src.sendSuccess(() -> Component.literal("[aiauto] 工具通道：写 "
                + com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.callsDir()
                + "/<id>.json，读 " + com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.resultsDir()
                + "/<id>.json（清单见 tools.json）"), false);
        return 1;
    }

    // ==================== MCP 服务器（真 MCP 通道）====================

    /** /cryptand aiauto mcp [status] —— 状态与接入地址 */
    private static int mcpStatus(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        for (String line : com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer
                .statusText().split("\\n")) {
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }
        src.sendSuccess(() -> Component.literal(
                "[aiauto] 客户端接入：把下面的 URL 配成 MCP server（Streamable HTTP）"),
                false);
        src.sendSuccess(() -> Component.literal("  "
                + com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.endpoint()), false);
        return 1;
    }

    /** /cryptand aiauto mcp start —— 按配置启动（配置关闭时提示改 aiauto.toml） */
    private static int mcpStart(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        if (!com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto.mcpEnabled()) {
            src.sendSuccess(() -> Component.literal(
                    "[aiauto] 配置里 mcpEnabled=false —— 请改 config/cryptand/aiauto.toml 后重试"),
                    false);
            return 0;
        }
        com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.syncFromConfig();
        src.sendSuccess(() -> Component.literal("[aiauto] MCP："
                + com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.endpoint()), false);
        return 1;
    }

    /** /cryptand aiauto mcp stop */
    private static int mcpStop(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.stop();
        src.sendSuccess(() -> Component.literal("[aiauto] MCP 服务器已停止（端口已释放）"), false);
        return 1;
    }

    /** /cryptand aiauto mcp restart —— 改端口/开关后不用重开游戏 */
    private static int mcpRestart(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.restart();
        src.sendSuccess(() -> Component.literal("[aiauto] MCP 已重启："
                + com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.endpoint()), false);
        return 1;
    }

    /** /cryptand aiauto mcp tools —— 列出已注册的 MCP 工具名 */
    private static int mcpTools(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        final var names = com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer
                .registry().names();
        src.sendSuccess(() -> Component.literal("[aiauto] MCP 工具 " + names.size() + " 个："), false);
        for (String name : names) {
            src.sendSuccess(() -> Component.literal("  · " + name), false);
        }
        return 1;
    }

    /** /cryptand aiauto prep —— 干净工作台（创造 + 白天 + 晴天 + 清怪） */
    private static int prep(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        final String result = com.hdf.cryptand.neoforge.aiauto.AiSession.execute("prep");
        src.sendSuccess(() -> Component.literal("[aiauto] " + result), false);
        return 1;
    }

    /** 打印 console.log 尾部（cmd 的屏显） */
    private static int tailConsole(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        try {
            final var log = com.hdf.cryptand.neoforge.aiauto.AiAutomation.outDir().resolve("console.log");
            if (!java.nio.file.Files.exists(log)) {
                src.sendSuccess(() -> Component.literal("[aiauto] 暂无控制台输出（" + log + "）"), false);
                return 1;
            }
            final var lines = java.nio.file.Files.readAllLines(log);
            final int from = Math.max(0, lines.size() - 12);
            for (int i = from; i < lines.size(); i++) {
                final String l = lines.get(i);
                src.sendSuccess(() -> Component.literal("  " + l), false);
            }
            src.sendSuccess(() -> Component.literal("[aiauto] 完整日志：" + log), false);
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[aiauto] 读取日志失败：" + ex.getMessage()));
        }
        return 1;
    }

    /**
     * /cryptand aiauto reload [界面] —— 热重载 UI：重载资源包 + 重开界面。
     *
     * <p><b>能做什么</b>：改过的 `.lss`（颜色/圆角/字号/按钮态）重载后立刻生效，不用重启游戏。<br>
     * <b>不能做什么</b>：Java 代码里的布局改动 —— 类已加载，必须重启客户端
     * （要真正免重启地改布局，得把 UI 定义迁到 XML）。</p>
     */
    private static int reloadUi(CommandContext<CommandSourceStack> ctx, String item) {
        final CommandSourceStack src = ctx.getSource();
        final var mc = net.minecraft.client.Minecraft.getInstance();
        mc.reloadResourcePacks();
        src.sendSuccess(() -> Component.literal("[aiauto] 已触发资源重载（.lss 主题生效）"), false);
        final var names = com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels.names();
        if (item != null) {
            // 可重载面板（工厂式登记）⇒ **原地换树**：不关屏、不闪（2026-09-29 定案）
            if (com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels.rebuild(item)) {
                src.sendSuccess(() -> Component.literal("[aiauto] 已就地重建界面 " + item + "（未关屏）"), false);
                return 1;
            }
            if (mc.player != null) {
                mc.player.closeContainer();
            }
            mc.setScreen(null);
            final boolean ok = com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels.open(item);
            src.sendSuccess(() -> Component.literal(ok
                    ? "[aiauto] 已重开界面 " + item
                    : "[aiauto] 重开失败（未登记该界面）"), false);
        } else {
            src.sendSuccess(() -> Component.literal("[aiauto] 已登记界面："
                    + (names.isEmpty() ? "（无）" : String.join(" / ", names))
                    + "；用法：/cryptand aiauto reload <界面>"), false);
        }
        src.sendSuccess(() -> Component.literal(
                "[aiauto] 注意：Java 构建的布局改动仍需重启客户端（.lss 样式改动免重启）"), false);
        return 1;
    }

    /** 列出可调用的工具（类 MCP：AI 读 tools.json 也能拿到同样信息） */
    private static int listTools(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.writeSchema();
        final var names = com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.toolNames();
        src.sendSuccess(() -> Component.literal("[aiauto] 可调用工具 " + names.size() + " 个："), false);
        src.sendSuccess(() -> Component.literal("  " + String.join(" / ", names)), false);
        src.sendSuccess(() -> Component.literal("[aiauto] 调用：写 "
                + com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.callsDir()
                + "/<id>.json，读 " + com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.resultsDir() + "/<id>.json"), false);
        src.sendSuccess(() -> Component.literal("[aiauto] 清单：" 
                + com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.schemaFile()), false);
        return 1;
    }

    /** 打印产物目录（AI 读的目录） */
    private static int openDir(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        final var dir = com.hdf.cryptand.neoforge.aiauto.AiAutomation.outDir();
        src.sendSuccess(() -> Component.literal("[aiauto] 产物目录：" + dir), false);
        src.sendSuccess(() -> Component.literal("  status.txt / events.log / console.log / inbox/ / done/ / pipeline/"), false);
        src.sendSuccess(() -> Component.literal("  运行 run example 可跑通示例流水线（首次自动生成 pipeline/example.cmd）"), false);
        return 1;
    }

    private static String target(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "target");
    }

    private static String item(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "item");
    }

    // ==================== 动作 ====================

    private static int listTargets(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("[aiauto] 自动化目标："
                + (AiAutomation.targetIds().isEmpty() ? "（无）" : String.join(" / ", AiAutomation.targetIds()))), false);
        for (AiTarget target : AiAutomation.targets()) {
            src.sendSuccess(() -> Component.literal("  · " + target.id() + " — " + target.displayName()
                    + (target.available() ? "" : "（当前不可用）")
                    + "  对象 " + target.items().size() + " 个"), false);
        }
        src.sendSuccess(() -> Component.literal("[aiauto] 产物目录：" + AiAutomation.outDir()), false);
        src.sendSuccess(() -> Component.literal("[aiauto] 开关：" + (AiAutomation.allowed()
                ? "已开启" : "关闭（在 config/cryptand/aiauto.toml 设 enableAiAutomation=true）")), false);
        return 1;
    }

    private static int listItems(CommandContext<CommandSourceStack> ctx, String targetId) {
        final CommandSourceStack src = ctx.getSource();
        final AiTarget target = AiAutomation.target(targetId);
        if (target == null) {
            src.sendFailure(Component.literal("[aiauto] 未知目标 " + targetId
                    + "；可用：" + String.join(" / ", AiAutomation.targetIds())));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[aiauto] " + target.displayName()), false);
        final var items = target.items();
        if (items.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  （暂无对象：先在游戏里打开一次界面）"), false);
        } else {
            for (String name : items) {
                src.sendSuccess(() -> Component.literal("  · " + name + " — " + target.describe(name)), false);
            }
        }
        return 1;
    }

    private static int dump(CommandContext<CommandSourceStack> ctx, String targetId, String item) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        final AiTarget target = AiAutomation.target(targetId);
        if (target == null) {
            src.sendFailure(Component.literal("[aiauto] 未知目标 " + targetId));
            return 0;
        }
        final var items = item != null ? java.util.List.of(item) : target.items();
        if (items.isEmpty()) {
            src.sendFailure(Component.literal("[aiauto] 没有可导出的对象（先打开一次界面）"));
            return 0;
        }
        for (String name : items) {
            final var path = target.dump(name);
            if (path != null) {
                src.sendSuccess(() -> Component.literal("  · " + name + " → " + path), false);
            } else {
                src.sendFailure(Component.literal("  · " + name + " 导出失败"));
            }
        }
        return 1;
    }

    private static int shot(CommandContext<CommandSourceStack> ctx, String targetId, String item) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        final AiTarget target = AiAutomation.target(targetId);
        if (target == null) {
            src.sendFailure(Component.literal("[aiauto] 未知目标 " + targetId));
            return 0;
        }
        final String name = item != null ? item : (target.items().isEmpty() ? "current"
                : target.items().get(target.items().size() - 1));
        if (target.shot(name)) {
            src.sendSuccess(() -> Component.literal("[aiauto] 截图已触发：" + targetId + " / " + name
                    + " → " + AiAutomation.artifact(targetId, name, "png")), false);
        } else {
            src.sendFailure(Component.literal("[aiauto] 截图条件不满足（界面需已在屏幕上）"));
        }
        return 1;
    }

    private static int open(CommandContext<CommandSourceStack> ctx, String targetId, String item) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        final AiTarget target = AiAutomation.target(targetId);
        if (target == null || !target.open(item)) {
            src.sendFailure(Component.literal("[aiauto] 打开失败：目标未注册该对象，或未开启程序化打开"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[aiauto] 已打开 " + targetId + " / " + item), false);
        return 1;
    }

    // ==================== 动态 UI 插件（真 Java 插件，2026-09-29） ====================

    // ==================== 动态资源框架（2026-09-29 新命令族） ====================
    // scan / list / stats / reload [<id>|all|core <coreId>] / unload <id>
    // 说明：加载来源是"mod 内嵌 + <GAMEDIR>/cryptand/dynamic 目录"，不再按任意 jar 路径加载。

    private static final String DYNAMIC = "[dynamic] ";

    /** 低侵入：动态框架只在客户端装配（服务端不加载客户端门面类）。 */
    /** 安全限制：涉及"加载/卸载他人的代码"的指令必须 OP（权限等级 2），防止意外。 */
    private static boolean requireOp(CommandContext<CommandSourceStack> ctx) {
        if (!ctx.getSource().hasPermission(2)) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "该指令需要 OP 权限（安全限制）"));
            return false;
        }
        return true;
    }

    private static boolean requireClient(CommandContext<CommandSourceStack> ctx) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "动态资源框架仅在客户端可用"));
            return false;
        }
        return true;
    }

    /** 临时加载任意 jar（<b>必须配置开启</b>：dynamic.toml → enableAdhocPluginLoad）。 */
    private static int pluginAdhocLoad(CommandContext<CommandSourceStack> ctx, String jarPath) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx) || !requireOp(ctx)) {
            return 0;
        }
        if (!com.hdf.cryptand.neoforge.dynamic.config.ConfigDynamic.adhocLoadEnabled()) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC
                    + "临时加载未开启（config/cryptand/dynamic.toml → enableAdhocPluginLoad=true）"));
            return 0;
        }
        try {
            com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.loadAdHoc(
                    java.nio.file.Path.of(jarPath.trim()));
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + "已临时加载 " + jarPath.trim()
                    + "（不写入 dynamic 目录；unload 请按其 id）"), false);
            return 1;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "临时加载失败：" + t.getMessage()));
            return 0;
        }
    }

    private static int pluginScan(CommandContext<CommandSourceStack> ctx) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx) || !requireOp(ctx)) {
            return 0;
        }
        try {
            final com.hdf.cryptand.dynamic.core.ScanReport r =
                    com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.scan();
            final java.util.List<String> msgs = r.messages();
            final int shown = Math.min(msgs.size(), 8);
            for (int i = 0; i < shown; i++) {
                final String line = msgs.get(i);
                ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + line), false);
            }
            if (msgs.size() > shown) {
                final int rest = msgs.size() - shown;
                ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + "…还有 " + rest + " 条（见日志）"), false);
            }
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + r.summary()), false);
            return r.ok() ? 1 : 0;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "扫描失败：" + t.getMessage()));
            return 0;
        }
    }

    private static int pluginList(CommandContext<CommandSourceStack> ctx) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx)) {
            return 0;
        }
        if (!com.hdf.cryptand.neoforge.dynamic.DynamicFrameworkHost.ready()) {
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + "框架未初始化（先执行 plugin scan）"), false);
            return 1;
        }
        final java.util.List<com.hdf.cryptand.dynamic.core.Entry> es =
                com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.entries();
        if (es.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + "没有已加载条目（用法：plugin scan）"), false);
            return 1;
        }
        for (final com.hdf.cryptand.dynamic.core.Entry e : es) {
            final String line = e.id() + "  core=" + e.coreId() + "  src=" + e.sourceKind()
                    + "  api=" + e.apiVersion() + "  " + e.origin();
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + line), false);
        }
        final String st = com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.stats();
        ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + st), false);
        return 1;
    }

    private static int pluginStats(CommandContext<CommandSourceStack> ctx) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx)) {
            return 0;
        }
        if (!com.hdf.cryptand.neoforge.dynamic.DynamicFrameworkHost.ready()) {
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + "框架未初始化（先执行 plugin scan）"), false);
            return 1;
        }
        final String st = com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.stats();
        ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + st), false);
        return 1;
    }

    private static int pluginReload(CommandContext<CommandSourceStack> ctx, String id) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx) || !requireOp(ctx)) {
            return 0;
        }
        try {
            final com.hdf.cryptand.dynamic.core.ReloadReport r =
                    com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.reload(id);
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + r.summary()), false);
            return r.ok() ? 1 : 0;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "热重载失败：" + t.getMessage()));
            return 0;
        }
    }

    private static int pluginReloadCore(CommandContext<CommandSourceStack> ctx, String coreId) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx) || !requireOp(ctx)) {
            return 0;
        }
        try {
            final com.hdf.cryptand.dynamic.core.ReloadReport r =
                    com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.reloadCore(coreId);
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + "core=" + coreId + " " + r.summary()), false);
            return r.ok() ? 1 : 0;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "按核心重载失败：" + t.getMessage()));
            return 0;
        }
    }

    private static int pluginReloadAll(CommandContext<CommandSourceStack> ctx) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx) || !requireOp(ctx)) {
            return 0;
        }
        try {
            final com.hdf.cryptand.dynamic.core.ReloadReport r =
                    com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.reloadAll();
            ctx.getSource().sendSuccess(() -> Component.literal(DYNAMIC + r.summary()), false);
            return r.ok() ? 1 : 0;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "全量重载失败：" + t.getMessage()));
            return 0;
        }
    }

    private static int pluginUnload(CommandContext<CommandSourceStack> ctx, String id) {
        if (!checkEnabled(ctx.getSource()) || !requireClient(ctx) || !requireOp(ctx)) {
            return 0;
        }
        try {
            final boolean ok = com.hdf.cryptand.neoforge.dynamic.ClientDynamicHost.unload(id);
            ctx.getSource().sendSuccess(() -> Component.literal(ok
                    ? DYNAMIC + "已卸载 " + id + "（含依赖它的条目）"
                    : DYNAMIC + "没有已加载的 " + id), false);
            return ok ? 1 : 0;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal(DYNAMIC + "卸载失败：" + t.getMessage()));
            return 0;
        }
    }

    private static int capture(CommandContext<CommandSourceStack> ctx, String targetId, String item) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        if (AiAutomation.startCapture(targetId, item, true)) {
            src.sendSuccess(() -> Component.literal("[aiauto] 流水线已启动：" + targetId + " / " + item
                    + "（打开→等稳定→截图→还原）→ " + AiAutomation.artifact(targetId, item, "png")), false);
        } else {
            src.sendFailure(Component.literal("[aiauto] 无法启动：对象未登记，或 aiauto.toml 未开启"
                    + "（enableAiAutomation / allowAutoOpen）"));
        }
        return 1;
    }

    private static int debug(CommandContext<CommandSourceStack> ctx, String targetId) {
        final CommandSourceStack src = ctx.getSource();
        final AiTarget target = AiAutomation.target(targetId);
        if (target == null || !target.debug()) {
            src.sendFailure(Component.literal("[aiauto] 调试器不可用（需要界面已在屏幕上）"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[aiauto] 调试器已打开：" + targetId), false);
        return 1;
    }

    /** 执行游戏操作（如 {@code act give minecraft:stone 64}、{@code act raw time set day}） */
    private static int act(CommandContext<CommandSourceStack> ctx, String targetId,
                           String action, java.util.List<String> args) {
        final CommandSourceStack src = ctx.getSource();
        if (!checkEnabled(src)) {
            return 0;
        }
        final AiTarget target = AiAutomation.target(targetId);
        if (target == null || !target.act(action, args)) {
            src.sendFailure(Component.literal("[aiauto] 动作不被支持或缺少参数：" + action));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[aiauto] " + targetId + " 已执行动作 " + action
                + (args.isEmpty() ? "" : " " + String.join(" ", args))), false);
        return 1;
    }

    private static boolean checkEnabled(CommandSourceStack src) {
        if (!AiAutomation.allowed()) {
            src.sendFailure(Component.literal(
                    "[aiauto] AI 自动化未开启：请在 config/cryptand/aiauto.toml 设 enableAiAutomation=true"));
            return false;
        }
        return true;
    }
}