/**
 * ===== PowerGrid 子包：拓扑诊断 + 电路导出命令（2026-09-07 子包隔离迁移） =====
 *
 * 原 CryptandNeoForge 的 graph/convert/export/schematic 命令迁入本类并连同
 * core/export 三个导出类（现 powergrid/export）一并归本子包：导出对象 =
 * powergrid 自管网络/原版网络 → 删除 powergrid 目录 → 命令与导出服务整体消失，
 * core 零编译引用。
 *
 * 注册：PowergridCommands.register()（经 PowergridEntry.commands 钩子）
 * 挂 /cryptand 根：graph / convert / export / schematic。
 */
package com.hdf.cryptand.neoforge.powergrid;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import net.minecraft.commands.CommandSourceStack;

/** PowerGrid 子包：拓扑诊断 + 电路导出命令。 */
public final class PowergridExportCommands {

    private PowergridExportCommands() {
    }

    /** 挂载本子包全部 /cryptand 子命令（PowergridCommands.register 调用）。 */
    public static void register() {
        CryptandRegistries.registerSubcommand("graph", graphCommand());
        CryptandRegistries.registerSubcommand("convert", convertCommand());
        CryptandRegistries.registerSubcommand("export", exportCommand());
        CryptandRegistries.registerSubcommand("schematic", schematicCommand());
    }

    // ==================== graph（自管导线拓扑诊断） ====================

    /** /cryptand graph → 自管导线拓扑诊断（2026-08-13 阶段1）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            graphCommand() {
        return net.minecraft.commands.Commands.literal("graph")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    try {
                        var g = com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager.get();
                        java.lang.StringBuilder sb = new java.lang.StringBuilder();
                        // 转换开关状态（2026-08-13）
                        sb.append("[Convert] enabled=")
                                .append(com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter.isEnabled())
                                .append(" (switch=")
                                .append(com.hdf.cryptand.neoforge.powergrid.config
                                        .ConfigPowerGrid.ENABLE_POWERGRID_CONVERSION.get())
                                .append(" sim=")
                                .append(com.hdf.cryptand.neoforge.simulator.config
                                        .ConfigCircuit.ENABLE_CRYPTAND_SIMULATION.get())
                                .append(" solver=")
                                .append(com.hdf.cryptand.neoforge.simulator.config
                                        .ConfigCircuit.ENABLE_CRYPTAND_SOLVER.get())
                                .append(")\n");
                        // 完整闭环模式（2026-08-13）：转换启用 + 自管图非空 →
                        // 主 round 走 roundFromGraph
                        sb.append("[Round] mode=")
                                .append(com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter.isEnabled()
                                        && g.nodeCount() > 0
                                        ? "GRAPH(自管图驱动,不写原版节点)"
                                        : "LEGACY(原版网络驱动)")
                                .append('\n');
                        sb.append("[WireGraph] ").append(g).append('\n');
                        java.util.List<java.util.Set<
                                com.hdf.cryptand.circuitsimulation.netgraph.WirePoint>>
                                comps = g.components();
                        sb.append("components=").append(comps.size()).append('\n');
                        for (java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph
                                .WirePoint> comp : comps) {
                            sb.append("  comp(").append(comp.size()).append("): ");
                            int n = 0;
                            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p
                                    : comp) {
                                sb.append(p.key).append(' ');
                                if (++n >= 12) { sb.append("..."); break; }
                            }
                            sb.append('\n');
                        }
                        sb.append("edges=").append(g.edgeCount()).append('\n');
                        int en = 0;
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e
                                : g.edgeList()) {
                            sb.append("  ").append(e).append('\n');
                            if (++en >= 30) { sb.append("  ...\n"); break; }
                        }
                        // 网表相关操作类 + 虚拟线程诊断（2026-08-16）
                        try {
                            com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher td =
                                    com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers.get();
                            sb.append("[NetOp] enabled=")
                                    .append(com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager.enabled())
                                    .append(" records=")
                                    .append(com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager.get()
                                            .recordSize())
                                    .append(" threads=").append(td.maxThreads())
                                    .append(" maxVt=").append(td.maxVirtualThreads())
                                    .append(" activeVt=")
                                    .append(td.activeVirtualThreads())
                                    .append(" load=").append(td.totalLoad())
                                    .append('\n');
                        } catch (Throwable ignored) {
                        }
                        ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component
                                        .literal(sb.toString()), true);
                        // 组件构建验证（从图构建引擎网络）
                        if (!comps.isEmpty()) {
                            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph
                                    .WirePoint> first = comps.get(0);
                            if (!first.isEmpty()) {
                                com.hdf.cryptand.circuitsimulation.netgraph.WirePoint seed =
                                        first.iterator().next();
                                com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext pc =
                                        com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder
                                                .buildContextFromGraph(
                                                        ctx.getSource()
                                                                .getLevel(),
                                                        seed.key, 50.0);
                                ctx.getSource().sendSuccess(
                                        () -> net.minecraft.network.chat.Component
                                                .literal("[GraphBuild] nodes="
                                                        + pc.network.nodeCount()
                                                        + " elements="
                                                        + pc.network.elements().size()
                                                        + " terminals="
                                                        + pc.network.terminals().size()
                                                        + " pointMap="
                                                        + pc.pointToEngine.size()
                                                        + " nodeMap="
                                                        + pc.nodeToEngine.size()
                                                        + " tfModels="
                                                        + pc.transformerModels.size()
                                                        + " devThermals="
                                                        + pc.deviceThermals.size()),
                                        true);
                            }
                        }
                    } catch (Exception ex) {
                        ctx.getSource().sendFailure(
                                net.minecraft.network.chat.Component
                                        .literal("图诊断失败：" + ex));
                    }
                    return 1;
                });
    }

    // ==================== convert（手动导线转换） ====================

    /** /cryptand convert → 手动触发一次原版导线转换（2026-08-13 转换类验证；
     *  正常由每 tick 自动调用；与 graph 同级）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            convertCommand() {
        return net.minecraft.commands.Commands.literal("convert")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    try {
                        java.util.List<org.patryk3211.powergrid.electricity.sim
                                .special.TransmissionLine> wires =
                                com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline.WORLD_WIRES;
                        com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter.convertWires(wires);
                        var g = com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager.get();
                        ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component
                                        .literal("[Convert] worldWires="
                                                + (wires == null ? 0 : wires.size())
                                                + " → graph=" + g),
                                true);
                    } catch (Exception ex) {
                        ctx.getSource().sendFailure(
                                net.minecraft.network.chat.Component
                                        .literal("转换失败：" + ex));
                    }
                    return 1;
                });
    }

    // ==================== export（电路图 JSON 异步导出） ====================

    /** /cryptand export → 导出全部自管网络为 test 电路图 JSON（2026-08-17；
     *  异步：走 CircuitExportService 分配器工作线程，保存 <gamedir>/cryptand/circuits/）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            exportCommand() {
        return net.minecraft.commands.Commands.literal("export")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    net.minecraft.server.level.ServerLevel lv =
                            ctx.getSource().getLevel();
                    net.minecraft.server.level.ServerPlayer player;
                    try {
                        player = ctx.getSource().getPlayerOrException();
                    } catch (Exception ex) {
                        player = null;
                    }
                    com.hdf.cryptand.neoforge.powergrid.export
                            .CircuitExportService.exportAllAsync(player);
                    ctx.getSource().sendSuccess(
                            () -> net.minecraft.network.chat.Component
                                    .literal("电路图导出任务已提交（异步，保存到 "
                                            + com.hdf.cryptand.neoforge.powergrid.export
                                                    .CircuitExportService.circuitsDir()
                                            + "）"),
                            false);
                    return 1;
                })
                // /cryptand export here → 玩家看向/所在网络
                .then(net.minecraft.commands.Commands.literal("here")
                        .executes(ctx -> {
                            try {
                                net.minecraft.server.level.ServerPlayer player =
                                        ctx.getSource().getPlayerOrException();
                                net.minecraft.world.phys.HitResult hit =
                                        player.pick(20.0, 1.0F, false);
                                net.minecraft.core.BlockPos target =
                                        (hit instanceof net.minecraft.world.phys
                                                .BlockHitResult bhr)
                                                ? bhr.getBlockPos()
                                                : player.blockPosition();
                                return exportCircuitJson(
                                        ctx.getSource(), target);
                            } catch (Exception ex) {
                                ctx.getSource().sendFailure(
                                        net.minecraft.network.chat.Component
                                                .literal("选定网络导出失败：" + ex));
                                return 0;
                            }
                        }))
                // /cryptand export <x y z> → 指定坐标网络
                .then(net.minecraft.commands.Commands.argument("pos",
                        net.minecraft.commands.arguments.coordinates
                                .BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            try {
                                net.minecraft.core.BlockPos target =
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(
                                                        ctx, "pos");
                                return exportCircuitJson(
                                        ctx.getSource(), target);
                            } catch (Exception ex) {
                                ctx.getSource().sendFailure(
                                        net.minecraft.network.chat.Component
                                                .literal("选定网络导出失败：" + ex));
                                return 0;
                            }
                        }));
    }

    /** 导出指定方块所在电路网络为 test 电路图 JSON（异步；完成后主线程通知玩家）。 */
    private static int exportCircuitJson(
            CommandSourceStack src,
            net.minecraft.core.BlockPos target) {
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception ex) {
            player = null;
        }
        com.hdf.cryptand.neoforge.powergrid.export.CircuitExportService
                .exportAtAsync(target, player);
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "电路图导出任务已提交（异步，保存到 "
                        + com.hdf.cryptand.neoforge.powergrid.export
                                .CircuitExportService.circuitsDir()
                        + "）"), false);
        return 1;
    }

    // ==================== schematic（电路原理图 Markdown 导出） ====================

    /** /cryptand schematic → 导出全部电路原理图（Markdown + Mermaid）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            schematicCommand() {
        return net.minecraft.commands.Commands.literal("schematic")
                .requires(s -> s.hasPermission(2))
                // /cryptand schematic → 全部
                .executes(ctx -> {
                    try {
                        net.minecraft.server.level.ServerPlayer player =
                                ctx.getSource().getPlayerOrException();
                        String md = com.hdf.cryptand.neoforge.powergrid.export
                                .CircuitSchematicExporter.export(
                                        player.serverLevel());
                        java.nio.file.Path out = java.nio.file.Paths
                                .get("circuit_schematic.md").toAbsolutePath();
                        java.nio.file.Files.write(out,
                                md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        player.sendSystemMessage(net.minecraft.network.chat
                                .Component.literal("电路原理图已导出：" + out));
                        String preview = md.length() > 1500
                                ? md.substring(0, 1500) + "\n...（完整内容见文件）"
                                : md;
                        player.sendSystemMessage(net.minecraft.network.chat
                                .Component.literal(preview));
                    } catch (Exception ex) {
                        ctx.getSource().sendFailure(net.minecraft.network.chat
                                .Component.literal("电路原理图导出失败：" + ex));
                    }
                    return 1;
                })
                // /cryptand schematic here → 玩家面向方块所在网络
                .then(net.minecraft.commands.Commands.literal("here")
                        .executes(ctx -> {
                            try {
                                net.minecraft.server.level.ServerPlayer player =
                                        ctx.getSource().getPlayerOrException();
                                net.minecraft.world.phys.HitResult hit =
                                        player.pick(20.0, 1.0F, false);
                                net.minecraft.core.BlockPos target =
                                        (hit instanceof net.minecraft.world.phys.BlockHitResult bhr)
                                                ? bhr.getBlockPos()
                                                : player.blockPosition();
                                return exportSelected(
                                        player.serverLevel(), target,
                                        ctx.getSource());
                            } catch (Exception ex) {
                                ctx.getSource().sendFailure(
                                        net.minecraft.network.chat.Component
                                                .literal("选定网络导出失败：" + ex));
                                return 0;
                            }
                        }))
                // /cryptand schematic <x y z> → 指定坐标所在网络
                .then(net.minecraft.commands.Commands.argument("pos",
                        net.minecraft.commands.arguments.coordinates
                                .BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            try {
                                net.minecraft.server.level.ServerPlayer player =
                                        ctx.getSource().getPlayerOrException();
                                net.minecraft.core.BlockPos target =
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument
                                                .getLoadedBlockPos(ctx, "pos");
                                return exportSelected(
                                        player.serverLevel(), target,
                                        ctx.getSource());
                            } catch (Exception ex) {
                                ctx.getSource().sendFailure(
                                        net.minecraft.network.chat.Component
                                                .literal("选定网络导出失败：" + ex));
                                return 0;
                            }
                        }));
    }

    /** 导出指定方块所在电路网络（/cryptand schematic here|<pos>）。 */
    private static int exportSelected(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos target,
            CommandSourceStack src) {
        try {
            String md = com.hdf.cryptand.neoforge.powergrid.export.CircuitSchematicExporter
                    .exportNetworkAt(level, target);
            if (md == null) {
                src.sendFailure(net.minecraft.network.chat.Component
                        .literal("该位置没有电气网络（请面向已接线的设备或指定坐标）："
                                + target.toShortString()));
                return 0;
            }
            java.nio.file.Path out = java.nio.file.Paths.get(
                    "circuit_schematic_" + target.getX() + "_" + target.getY()
                            + "_" + target.getZ() + ".md")
                    .toAbsolutePath();
            java.nio.file.Files.write(out,
                    md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            src.sendSuccess(() -> net.minecraft.network.chat.Component
                    .literal("选定电路网络已导出：" + out), true);
            String preview = md.length() > 1500
                    ? md.substring(0, 1500) + "\n...（完整内容见文件）" : md;
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(preview), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(net.minecraft.network.chat.Component
                    .literal("选定网络导出失败：" + ex));
            return 0;
        }
    }
}
