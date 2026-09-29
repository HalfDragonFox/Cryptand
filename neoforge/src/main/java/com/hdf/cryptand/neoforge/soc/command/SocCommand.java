package com.hdf.cryptand.neoforge.soc.command;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import com.hdf.cryptand.neoforge.soc.SocResources;
import com.hdf.cryptand.neoforge.soc.compile.ServerCompileService;
import com.hdf.cryptand.toolchain.CCompileRequest;
import com.hdf.cryptand.toolchain.CCompileResult;
import com.hdf.cryptand.toolchain.CToolchainReport;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * ===== /cryptand soc 子命令（2026-09-15）=====
 *
 * <pre>
 *   /cryptand soc tools           打印服务端编译工具链（来源/路径/版本/缺失提示）
 *   /cryptand soc compile example 用内置 blinky.c + cryptand.ld 编译（验证链路）
 *   /cryptand soc status          编译服务状态（开关/核心数/队列/拒绝数）
 *   /cryptand soc tools（客户端）  打印客户端工具链与编译方案
 * </pre>
 *
 * <p>子包自治注册（{@link CryptandRegistries#registerSubcommand}）——删除 soc 子包即消失。
 * 工具链探测与编译均在<b>后台线程</b>（冷探测约 13s），结果回主线程打印。</p>
 */
public final class SocCommand {

    private static volatile CToolchainReport cachedReport;

    private SocCommand() {
    }

    /** 服务端子命令注册（由 SocEntry.init 调用） */
    public static void register() {
        CryptandRegistries.registerSubcommand("soc", Commands.literal("soc")
                .then(Commands.literal("tools")
                        .executes(SocCommand::printServerTools)
                        .then(Commands.literal("check").executes(SocCommand::printServerTools)))
                .then(Commands.literal("compile")
                        .then(Commands.literal("example").executes(SocCommand::compileExample)))
                .then(Commands.literal("status").executes(SocCommand::printStatus))
                // /cryptand soc disk …（用户定案："可以让指定 id 的软盘强制清空加载，空间不够就报错"）
                .then(SocDiskCommand.build()));
    }

    /**
     * 客户端命令注册（由 SocClientSetup 调用）。
     *
     * <pre>
     *   /cryptand soc tools check   检查客户端工具链（惰性探测，约 13s，后台线程 + 结果缓存）
     *   /cryptand soc tools ui      打开下载 UI（平台的工具安装入口）
     *   /cryptand soc tools         等价于 check（默认动作）
     *   /cryptand soc download      等价于 tools ui（兼容旧写法）
     * </pre>
     */
    public static void registerClient(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cryptand")
                .then(Commands.literal("soc")
                        .then(Commands.literal("tools")
                                // 默认动作 = check
                                .executes(ctx -> clientCheck(ctx))
                                .then(Commands.literal("check").executes(ctx -> clientCheck(ctx)))
                                .then(Commands.literal("ui").executes(ctx -> openDownloadUi())))
                        .then(Commands.literal("download").executes(ctx -> openDownloadUi()))
                        // UI 布局导出 / 调试器（给 AI 与人工调布局用）
                        .then(Commands.literal("ui")
                                .then(Commands.literal("dump").executes(SocCommand::clientUiDump))
                                .then(Commands.literal("debug").executes(SocCommand::clientUiDebug)))));
    }

    /**
     * /cryptand soc ui dump —— 把面板的**计算后布局**导出成文本，供 AI 直接阅读。
     *
     * <p>面板打开时也会自动导出（{@code SocUiInspector.autoDump}），本命令用于手动重导。</p>
     */
    private static int clientUiDump(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        try {
            final java.nio.file.Path dir = com.hdf.cryptand.neoforge.soc.ui.SocUiInspector.dumpAll();
            src.sendSuccess(() -> Component.literal("[SoC] 布局已导出到：" + dir), false);
            for (String name : com.hdf.cryptand.neoforge.soc.ui.SocUiInspector.names()) {
                src.sendSuccess(() -> Component.literal("  · latest-" + name + ".txt"), false);
            }
            src.sendSuccess(() -> Component.literal(
                    "[SoC] 把该目录给 AI 即可分析布局（含每元素 pos/size/text）"), false);
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[SoC] 导出失败：" + ex.getMessage()));
        }
        return 1;
    }

    /** /cryptand soc ui debug —— 打开 LDLib2 自带 UIDebugger（层级树/计算样式/盒模型高亮） */
    private static int clientUiDebug(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen screen) {
            screen.getModularUI().enableDebugger(true);
            src.sendSuccess(() -> Component.literal(
                    "[SoC] UIDebugger 已打开（层级树 / Inspector 计算样式 / LayoutPanel 盒模型高亮）"), false);
        } else {
            src.sendFailure(Component.literal(
                    "[SoC] 先打开一个 Cryptand 面板（如 /cryptand soc tools ui）再执行 debug"));
        }
        return 1;
    }

    /** 客户端：检查工具链（异步探测 + 打印将使用的工具与编译方案） */
    private static int clientCheck(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("[SoC] 正在检查客户端编译工具链（后台，首次约 10s，结果会缓存）…"), false);
        com.hdf.cryptand.neoforge.soc.compile.ClientToolchainService.probeAsync(true, () -> {
            com.hdf.cryptand.neoforge.soc.compile.ClientToolchainService.invalidateRouter();
            final java.util.List<String> lines =
                    com.hdf.cryptand.neoforge.soc.compile.ClientToolchainService.displayLines();
            src.getServer().execute(() -> {
                for (String line : lines) {
                    src.sendSuccess(() -> Component.literal(line), false);
                }
                src.sendSuccess(() -> Component.literal(
                        "[SoC] 若缺少工具：/cryptand soc tools ui 打开下载页，可一键下载本平台工具链"), false);
            });
        });
        return 1;
    }

    /** 客户端：打开下载 UI */
    private static int openDownloadUi() {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.hdf.cryptand.neoforge.soc.download.SocDownloadScreen(
                        new com.hdf.cryptand.neoforge.soc.download.SocDownloadUi()));
        return 1;
    }

    // ==================== 子命令实现 ====================

    private static int printServerTools(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("[SoC] 正在查找服务端编译工具链（后台）…"), false);
        ThreadDispatchers.get().submitGeneric(() -> {
            final CToolchainReport report;
            try {
                CToolchainReport r = cachedReport;
                if (r == null) {
                    r = ServerCompileService.locateToolchain();
                    cachedReport = r;
                }
                report = r;
            } catch (Throwable t) {
                final String msg = "[SoC] 工具链查找失败：" + t;
                src.getServer().execute(() -> src.sendFailure(Component.literal(msg)));
                return;
            }
            src.getServer().execute(() -> {
                for (String line : report.toDisplayLines()) {
                    src.sendSuccess(() -> Component.literal(line), false);
                }
                src.sendSuccess(() -> Component.literal("[SoC] 自带工具目录：" + ServerCompileService.toolDirHint()), false);
            });
        }, TaskMode.EXCLUSIVE);
        return 1;
    }

    private static int compileExample(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        final CCompileRequest request = SocResources.exampleRequest();
        if (request.source().isEmpty()) {
            src.sendFailure(Component.literal("[SoC] 内置示例固件缺失（assets/cryptand/soc/blinky.c）"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[SoC] 开始编译内置示例 blinky.c（后台）…"), false);
        ThreadDispatchers.get().submitGeneric(() -> {
            final long start = System.nanoTime();
            final CCompileResult result = ServerCompileService.compileDirect(request);
            final long elapsed = (System.nanoTime() - start) / 1_000_000;
            src.getServer().execute(() -> {
                if (result.ok()) {
                    src.sendSuccess(() -> Component.literal("[SoC] 编译成功：产物 " + result.binarySize()
                            + " 字节，耗时 " + elapsed + " ms"), false);
                    src.sendSuccess(() -> Component.literal("[SoC] 工具链：" + result.toolchain()), false);
                    src.sendSuccess(() -> Component.literal("[SoC] 前 8 字节：" + hex(result.binary())), false);
                } else {
                    src.sendFailure(Component.literal("[SoC] 编译失败：" + result.error()));
                    for (String line : result.diagnosticLines()) {
                        src.sendFailure(Component.literal("  " + line));
                    }
                }
            });
        }, TaskMode.EXCLUSIVE);
        return 1;
    }

    private static int printStatus(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal(ServerCompileService.describe()), false);
        final CToolchainReport report = cachedReport;
        src.sendSuccess(() -> Component.literal(report != null
                ? "[SoC] 工具链已缓存（" + report.tools().size() + " 项）"
                : "[SoC] 工具链尚未查找（用 /cryptand soc tools）"), false);
        return 1;
    }

    private static String hex(byte[] data) {
        if (data == null) {
            return "(无)";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, data.length); i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
