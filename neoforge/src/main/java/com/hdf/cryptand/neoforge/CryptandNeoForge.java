package com.hdf.cryptand.neoforge;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.adapter.ServerMeasurementSystem;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.powergrid.creative.CreativeSources;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Cryptand.MOD_ID)
public class CryptandNeoForge {
    public static final Logger WAF_LOGGER = LogManager.getLogger(Cryptand.MOD_ID);

    public CryptandNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // Config 按主题注册（config/cryptand/*.toml，2026-08-22 per-mod 拆分）。
        // ⚠ 必须全量注册 ALL_SPECS——未注册 spec 的 ConfigValue.get() 在 tick 抛
        // "Cannot get config value before config is loaded"（AC 源/每 tick 读配置）。
        net.neoforged.neoforge.common.ModConfigSpec[] cfgSpecs = ConfigLoad.ALL_SPECS;
        String[] cfgFiles = ConfigLoad.ALL_SPEC_FILES;
        for (int i = 0; i < cfgSpecs.length; i++) {
            modContainer.registerConfig(ModConfig.Type.COMMON, cfgSpecs[i],
                    cfgFiles[i]);
        }
        WAF_LOGGER.info("[Config] registered {} cryptand config specs", cfgSpecs.length);

        CreativeSources.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        // 服务端 tick：驱动万用表测量系统（对同一网络的请求合并求解后回发）
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post ev) -> {
            MinecraftServer srv = ev.getServer();
            if (srv == null) return;
            for (ServerLevel lvl : srv.getAllLevels()) {
                // 兜底（2026-08-14 重进丢失）：SQLite 若未由 LevelEvent.Load 打开
                // （事件时序/注册问题）→ 首个服务端 tick 补开，保证导线存档可用
                com.hdf.cryptand.neoforge.powergrid.adapter.CryptandSqlite.ensureOpen(lvl);
                ServerMeasurementSystem.tick(lvl);
            }
        });

        // 命令 /cryptand schematic：导出电路原理图（Markdown + Mermaid）
        //   /cryptand schematic          → 导出全部电路到 circuit_schematic.md
        //   /cryptand schematic here     → 导出【玩家面向方块】所在电路网络
        //   /cryptand schematic <x y z>  → 导出【指定坐标】所在电路网络
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent ev) -> {
            ev.getDispatcher().register(
                    net.minecraft.commands.Commands.literal("cryptand")
                            .then(net.minecraft.commands.Commands.literal("schematic")
                                    .requires(s -> s.hasPermission(2))
                                    // /cryptand schematic → 全部
                                    .executes(ctx -> {
                                        try {
                                            net.minecraft.server.level.ServerPlayer player =
                                                    ctx.getSource().getPlayerOrException();
                                            String md = com.hdf.cryptand.neoforge.core.export
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
                                                    return cryptand$exportSelected(
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
                                                    return cryptand$exportSelected(
                                                            player.serverLevel(), target,
                                                            ctx.getSource());
                                                } catch (Exception ex) {
                                                    ctx.getSource().sendFailure(
                                                            net.minecraft.network.chat.Component
                                                                    .literal("选定网络导出失败：" + ex));
                                                    return 0;
                                                }
                                            })))
                            .then(cryptand$libraryCommand())
                            .then(cryptand$transformerCommand())
                            .then(cryptand$deviceCommand())
                            // /cryptand export → 导出全部自管网络为 test 电路图 JSON
                            // （2026-08-17：/cryptand export 全部；export here 当前网络；
                            //  export <x y z> 指定坐标网络）
                            // 与 graph 同级，都挂在 /cryptand 根下
                            // 异步执行：走 CircuitExportService（分配器工作线程），
                            // 默认保存到 <gamedir>/cryptand/circuits/
                            .then(net.minecraft.commands.Commands.literal("export")
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
                                        com.hdf.cryptand.neoforge.core.export
                                                .CircuitExportService.exportAllAsync(player);
                                        ctx.getSource().sendSuccess(
                                                () -> net.minecraft.network.chat.Component
                                                        .literal("电路图导出任务已提交（异步，保存到 "
                                                                + com.hdf.cryptand.neoforge.core.export
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
                                                    return cryptand$exportCircuitJson(
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
                                                    return cryptand$exportCircuitJson(
                                                            ctx.getSource(), target);
                                                } catch (Exception ex) {
                                                    ctx.getSource().sendFailure(
                                                            net.minecraft.network.chat.Component
                                                                    .literal("选定网络导出失败：" + ex));
                                                    return 0;
                                                }
                                            })))
                            // /cryptand graph → 自管导线拓扑诊断（2026-08-13 阶段1）
                            .then(net.minecraft.commands.Commands.literal("graph")
                                    .requires(s -> s.hasPermission(2))
                                    .executes(ctx -> {
                                        try {
                                            var g = com.hdf.cryptand.neoforge.powergrid.adapter
                                                    .WireNetworkManager.get();
                                            java.lang.StringBuilder sb = new java.lang.StringBuilder();
                                            // 转换开关状态（2026-08-13）
                                            sb.append("[Convert] enabled=")
                                                    .append(com.hdf.cryptand.neoforge.powergrid.adapter
                                                            .PowerGridWireConverter.isEnabled())
                                                    .append(" (switch=")
                                                    .append(com.hdf.cryptand.neoforge.core.config
                                                            .ConfigLoad.ENABLE_POWERGRID_CONVERSION.get())
                                                    .append(" sim=")
                                                    .append(com.hdf.cryptand.neoforge.core.config
                                                            .ConfigLoad.ENABLE_CRYPTAND_SIMULATION.get())
                                                    .append(" solver=")
                                                    .append(com.hdf.cryptand.neoforge.core.config
                                                            .ConfigLoad.ENABLE_CRYPTAND_SOLVER.get())
                                                    .append(")\n");
                                            // 完整闭环模式（2026-08-13）：转换启用 +
                                            // 自管图非空 → 主 round 走 roundFromGraph
                                            sb.append("[Round] mode=")
                                                    .append(com.hdf.cryptand.neoforge.powergrid.adapter
                                                            .PowerGridWireConverter.isEnabled()
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
                                                        .append(com.hdf.cryptand.neoforge.powergrid.adapter
                                                                .MainThreadInteractionManager.enabled())
                                                        .append(" records=")
                                                        .append(com.hdf.cryptand.neoforge.powergrid.adapter
                                                                .MainThreadInteractionManager.get()
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
                                                    com.hdf.cryptand.neoforge.powergrid.adapter
                                                            .PhasorNetworkContext pc =
                                                            com.hdf.cryptand.neoforge.powergrid.adapter
                                                                    .PhasorNetworkBuilder
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
                                    }))
                            // /cryptand convert → 手动触发一次原版导线转换
                            // （2026-08-13 转换类验证；正常由每 tick 自动调用；
                            //  与 graph 同级，都挂在 /cryptand 根下）
                            .then(net.minecraft.commands.Commands.literal("convert")
                                    .requires(s -> s.hasPermission(2))
                                    .executes(ctx -> {
                                        try {
                                            java.util.List<org.patryk3211.powergrid.electricity.sim
                                                    .special.TransmissionLine> wires =
                                                    com.hdf.cryptand.neoforge.powergrid.adapter
                                                            .PhasorWriteback.WORLD_WIRES;
                                            com.hdf.cryptand.neoforge.powergrid.adapter
                                                    .PowerGridWireConverter.convertWires(wires);
                                            var g = com.hdf.cryptand.neoforge.powergrid.adapter
                                                    .WireNetworkManager.get();
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
                                    })));
        });

        // 服务端停止：停 CryptandTopologyManager 的异步调度线程（若有）+ 停内核仿真接口
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent ev) -> {
                    com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get().stop();
                    com.hdf.cryptand.neoforge.simserver.SimHttpServer.get().stop();
                    // 仿真核心随服务端停止（核心与 MC 解耦，可独立运行/重启）
                    com.hdf.cryptand.circuitsimulation.core.SimulationCore.get().stop();
                });
        // 服务端启动：开启本地内核仿真 HTTP 接口（供 test/ 前端调用内核仿真）
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStartedEvent ev) -> {
                    try {
                        // 启动仿真核心（分配器线程 A + 异步交互管理类线程 B + 网络操作
                        // 记录表；MC 与独立运行共用同一核心，进一步拆分核心与 MC 绑定）
                        com.hdf.cryptand.circuitsimulation.core.SimulationCore.get().start();
                    } catch (Throwable ignored) {
                    }
                    try {
                        com.hdf.cryptand.neoforge.simserver.SimHttpServer.get().start();
                    } catch (Throwable ignored) {
                    }
                });
        // 玩家加入：强制同步一次自管导线图（绕过 version 去重——lastSyncedVer
        // 是全局的，第二个玩家加入时图可能未变，普通去重会跳过 → 新玩家看不到
        // 任何线；强制发送保证新客户端拿到全量图）
        // ⚠ 2026-08-15 卡世界修复：不能在本事件里【同步】发——此时登录流程
        // （placeNewPlayer）尚未完成，sendEdges → getExactPosition →
        // getBlockState → ServerChunkCache.getChunk(...).join() 会在 Server
        // thread 上自锁（区块加载任务等主线程 → 主线程等区块）→ 玩家加入卡世界。
        // 改为排到下一 tick 再发（登录完成、区块正常加载中）。
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent ev) -> {
                    try {
                        if (ev.getEntity().level() instanceof ServerLevel sl) {
                            // 排到下一 tick 发（登录完成后区块正常加载）；传当前
                            // ServerLevel 计算端子位置（2026-08-17 修复：无参版本
                            // 依赖 CRYPTAND_LAST_LEVEL，玩家加入瞬间可能未设置 → 导线
                            // 渲染在模型中心）
                            sl.getServer().execute(
                                    () -> com.hdf.cryptand.neoforge.powergrid.adapter
                                            .PowerGridWireConverter.syncGraphToClientsForce(sl));
                        }
                    } catch (Throwable ignored) {
                    }
                });
        // ===== 元件放置即建网（2026-08-15 用户要求）=====
        // 每个实际元件（含接线端子块）放下 → 其全部声明端子作为【同一单点网络】
        // 加入自管图（端子间无边不导电）：未接线即可被 /cryptand schematic 等
        // 识别；接线时 addEdge 正常 merge。世界重载后的设备点恢复走 SavedData
        // 持久化（WireSavedData.devices，2026-08-20 治本，替代 ChunkLoad 补丁）。
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent ev) -> {
                    try {
                        if (ev.getLevel() == null || ev.getLevel().isClientSide()) return;
                        if (!com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                                .isEnabled()) return;
                        // ⚠ getEntity() 在 EntityPlaceEvent 返回 Entity；按位置取
                        // 刚放置的方块实体（放置事件时 BE 已创建）
                        net.minecraft.world.level.block.entity.BlockEntity be =
                                ev.getLevel().getBlockEntity(ev.getPos());
                        // 2026-08-20 修复"电机不建模"：ConstantSpeedMotor/ElectricMotor
                        // 是 IElectricEntity（非 ElectricBlockEntity 子类，但有电气端子
                        // buildCircuit）→ 必须同样 addDevice 入自管图，否则自管图无电机
                        // 点 → 不建模 → 无 EMF 反馈 → 电流固定/假功率爆炸。
                        if (!(be instanceof org.patryk3211.powergrid.electricity.base
                                .ElectricBlockEntity)
                                && !(be instanceof org.patryk3211.powergrid.electricity.base
                                .IElectricEntity)) return;
                        com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                .addDevice(be.getBlockPos(),
                                        com.hdf.cryptand.neoforge.powergrid.adapter
                                                .PhasorNetworkBuilder.declaredTerminalCount(be));
                    } catch (Throwable ignored) {
                    }
                });
        // SQLite 存储生命周期（2026-08-13 用户架构：本 mod 提供 SQLite 存储接口，
        // 适用于其他 mod；写走单写线程异步队列，与求解线程池并行不阻塞）：
        //   世界加载 → 打开 <world>/cryptand/data.sqlite + 建 KV/NBT 表
        //   世界卸载 → flush 落盘 + 关连接
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Load ev) -> {
                    try {
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[Sqlite] LevelEvent.Load level={}",
                                ev.getLevel().getClass().getSimpleName());
                        if (ev.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                            // 2026-08-14 修复“退出再进卡在加载世界”：SQLite/导线
                            // 只在主世界 open/load。此前每维度都 open（内部先 close
                            // 旧连接再重开）——integrated server 下 the_end 第二次
                            // close 的 conn.close() 无超时可永久卡死 Server thread，
                            // 世界加载卡住。
                            if (sl.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                                com.hdf.cryptand.neoforge.powergrid.adapter.CryptandSqlite.open(sl);
                                // 仿真电路文件夹（2026-08-15）：建
                                // <world>/cryptand/simulation_circuit + 打开网表库 +
                                // 恢复 + 重建稳定 64 位 id 映射
                                com.hdf.cryptand.neoforge.powergrid.persistence
                                        .SimulationCircuitFolder.onWorldLoad(sl);
                                // 自管导线拓扑（2026-08-13 阶段1）：新世界 → 清空重建
                                com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                        .onWorldLoad(sl);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                });
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Unload ev) -> {
                    try {
                        if (ev.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                            // 与 Load 一致：只主世界处理（避免每维度重复 close）
                            if (sl.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
                            // ⚠ 顺序：先异步保存自管导线拓扑（close 前排队），再
                            // close（flush 落盘）——反序会因 store 置 null 导致
                            // 导线存档被跳过 + close 的 flush 忙等无写任务可等
                            com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                    .onWorldUnload();
                            // 仿真电路文件夹：采集最终快照落盘 + 关网表库
                            // （内部 flush 等所有排队写完成）
                            com.hdf.cryptand.neoforge.powergrid.persistence
                                    .SimulationCircuitFolder.onWorldUnload();
                            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandSqlite.close();
                            // 端子注册表清理（2026-08-13 完全接管端子）：世界卸载
                            // → 位置端子全部失效（跨世界残留会污染下一个世界）。
                            com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry.clear();
                        }
                    } catch (Throwable ignored) {
                    }
                });
        // 存档网络表缓存（2026-08-13 用户架构：彻底取消 NBT——NBT 会导致 NBT
        // 过大等问题）。世界保存时采集【未加载区】虚拟设备参数 → DeviceCacheTable
        // （SQLite 强类型列存储，无 NBT）→ 跨会话持久化；进世界时 worldSynchronize
        // 从 SQLite 恢复（未加载区设备参数立即可用，强化世界加载）。
        // ⚠ 只缓存未加载区（虚拟）设备——已加载区进世界后重建自动重新生成，
        // 不冗余占用数据库。
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Save ev) -> {
                    try {
                        if (ev.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCacheTable cache =
                                    com.hdf.cryptand.neoforge.powergrid.adapter.CryptandSqlite.deviceCache();
                            if (cache != null) cache.saveAsync(sl);
                            // 仿真电路网表快照保存（2026-08-15：网络/组装器/导线/
                            // 渲染器四表单事务异步写）
                            com.hdf.cryptand.neoforge.powergrid.persistence
                                    .SimulationCircuitFolder.onWorldSave(sl);
                            // 自管导线拓扑保存（2026-08-14 原版 SavedData：随世界
                            // 存档自动落盘，无需 SQLite）
                            com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                    .onWorldSave(sl);
                        }
                    } catch (Throwable ignored) {
                    }
                });
        if (FMLEnvironment.dist == Dist.CLIENT) {
//            modEventBus.addListener(ClientEvents::onClientSetup);
        }
    }

    /** /cryptand transformer：变压器参数模型命令树（param/info/reset）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>
            cryptand$transformerCommand() {
        return net.minecraft.commands.Commands.literal("transformer")
                .requires(s -> s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("param")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .then(net.minecraft.commands.Commands.argument("params",
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .greedyString())
                                        .executes(ctx -> cryptand$transformerParam(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates
                                                        .BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "params"))))))
                .then(net.minecraft.commands.Commands.literal("info")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> cryptand$transformerInfo(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(net.minecraft.commands.Commands.literal("reset")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> cryptand$transformerReset(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));
    }

    /** /cryptand transformer param <pos> <k=v,...>：设置参数并调用基类接口 applyModel。 */
    private static int cryptand$transformerParam(net.minecraft.commands.CommandSourceStack src,
                                                 net.minecraft.core.BlockPos pos, String paramsText) {
        if (!com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "设备参数模型已禁用（enableDeviceParameterModels=false）"));
            return 0;
        }
        net.minecraft.world.level.Level level = src.getLevel();
        if (level == null) return 0;
        if (!(level.getBlockEntity(pos)
                instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity)) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "该位置不是变压器: " + pos.toShortString()));
            return 0;
        }
        com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParameters tp =
                com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParamStore.get(pos);
        if (tp == null) tp = com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParameters.defaults();
        for (String part : paramsText.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            double v;
            try {
                v = Double.parseDouble(part.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            switch (k) {
                case "maxCoupling": case "coupling":      tp.maxCoupling = v; break;
                case "maxSelfInductance": case "maxL":     tp.maxSelfInductance = v; break;
                case "primaryResistance": case "rCp":      tp.primaryResistance = v; break;
                case "secondaryResistance": case "rCs":    tp.secondaryResistance = v; break;
                case "coreLossMultiplier": case "coreLoss": tp.coreLossMultiplier = (float) v; break;
                case "heatDissipation": case "heat":       tp.heatDissipation = (float) v; break;
                case "heatCapacity": case "heatCap":       tp.heatCapacity = (float) v; break;
                case "ambientK": case "ambient":           tp.ambientK = (float) v; break;
                case "maxTempK": case "maxTemp":           tp.maxTempK = (float) v; break;
                case "coreAl":                              tp.coreAl = (float) v; break;
                case "maxTurns": case "turns":             tp.maxTurns = (int) v; break;
                case "primaryWireRPerMeter": case "wireP": tp.primaryWireRPerMeter = (float) v; break;
                case "secondaryWireRPerMeter": case "wireS": tp.secondaryWireRPerMeter = (float) v; break;
                default:
                    src.sendFailure(net.minecraft.network.chat.Component.literal("未知参数: " + k));
                    return 0;
            }
        }
        // 基类接口：模型套用后更新魔法数字（自感上限/铜阻按铁心+导线换算）
        tp.applyModel();
        com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParamStore.put(pos, tp);
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get().markTopologyChanged();
        } catch (Throwable ignored) {
        }
        final com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParameters fTp = tp;
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已更新 " + pos.toShortString() + " " + fTp.describe()), true);
        return 1;
    }

    /** /cryptand transformer info <pos>：显示当前参数。 */
    private static int cryptand$transformerInfo(net.minecraft.commands.CommandSourceStack src,
                                                net.minecraft.core.BlockPos pos) {
        if (!com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "设备参数模型已禁用（enableDeviceParameterModels=false）"));
            return 0;
        }
        com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParameters tp =
                com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParamStore.get(pos);
        if (tp == null) {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    pos.toShortString() + " 未自定义参数（使用默认魔法数字）"), false);
        } else {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    pos.toShortString() + " " + tp.describe()), false);
        }
        return 1;
    }

    /** /cryptand transformer reset <pos>：清除参数，回默认魔法数字。 */
    private static int cryptand$transformerReset(net.minecraft.commands.CommandSourceStack src,
                                                 net.minecraft.core.BlockPos pos) {
        if (!com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "设备参数模型已禁用（enableDeviceParameterModels=false）"));
            return 0;
        }
        com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParamStore.remove(pos);
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get().markTopologyChanged();
        } catch (Throwable ignored) {
        }
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                pos.toShortString() + " 已重置为默认魔法数字"), true);
        return 1;
    }

    /** /cryptand device：通用设备参数模型命令树（param/info/reset）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>
            cryptand$deviceCommand() {
        return net.minecraft.commands.Commands.literal("device")
                .requires(s -> s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("param")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .then(net.minecraft.commands.Commands.argument("params",
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .greedyString())
                                        .executes(ctx -> cryptand$deviceParam(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates
                                                        .BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "params"))))))
                .then(net.minecraft.commands.Commands.literal("info")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> cryptand$deviceInfo(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(net.minecraft.commands.Commands.literal("reset")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> cryptand$deviceReset(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));
    }

    /** /cryptand device param <pos> <k=v,...>：按设备类型设置参数并调 applyModel。 */
    private static int cryptand$deviceParam(net.minecraft.commands.CommandSourceStack src,
                                            net.minecraft.core.BlockPos pos, String paramsText) {
        if (!com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "设备参数模型已禁用（enableDeviceParameterModels=false）"));
            return 0;
        }
        net.minecraft.world.level.Level level = src.getLevel();
        if (level == null) return 0;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "该位置没有方块: " + pos.toShortString()));
            return 0;
        }
        com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameters p =
                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameterStore.get(pos);
        if (p == null) {
            p = cryptand$createDeviceParams(be);
            if (p == null) {
                src.sendFailure(net.minecraft.network.chat.Component.literal(
                        "该设备不支持参数模型: " + be.getClass().getSimpleName()));
                return 0;
            }
        }
        for (String part : paramsText.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            double v;
            try {
                v = Double.parseDouble(part.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (!cryptand$setDeviceField(p, k, v)) {
                src.sendFailure(net.minecraft.network.chat.Component.literal("未知参数: " + k));
                return 0;
            }
        }
        p.applyModel(); // 基类接口：模型套用后更新魔法数字
        com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameterStore.put(pos, p);
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get().markTopologyChanged();
        } catch (Throwable ignored) {
        }
        final com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameters fP = p;
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已更新 " + pos.toShortString() + " " + fP.describe()), true);
        return 1;
    }

    /** /cryptand device info <pos>：显示设备参数。 */
    private static int cryptand$deviceInfo(net.minecraft.commands.CommandSourceStack src,
                                           net.minecraft.core.BlockPos pos) {
        if (!com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "设备参数模型已禁用（enableDeviceParameterModels=false）"));
            return 0;
        }
        com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameters p =
                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameterStore.get(pos);
        if (p == null) {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    pos.toShortString() + " 未自定义参数（使用默认魔法数字）"), false);
        } else {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    pos.toShortString() + " " + p.describe()), false);
        }
        return 1;
    }

    /** /cryptand device reset <pos>：清除参数，回默认魔法数字。 */
    private static int cryptand$deviceReset(net.minecraft.commands.CommandSourceStack src,
                                            net.minecraft.core.BlockPos pos) {
        if (!com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "设备参数模型已禁用（enableDeviceParameterModels=false）"));
            return 0;
        }
        com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameterStore.remove(pos);
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get().markTopologyChanged();
        } catch (Throwable ignored) {
        }
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                pos.toShortString() + " 已重置为默认魔法数字"), true);
        return 1;
    }

    /** 按设备类型创建默认参数模型。 */
    private static com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameters cryptand$createDeviceParams(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        if (be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
            return com.hdf.cryptand.neoforge.powergrid.adapter.TransformerParameters.defaults();
        }
        String cls = be.getClass().getSimpleName();
        if (cls.contains("Generator") || cls.contains("Motor")
                || cls.contains("Electromagnet") || cls.contains("Fan")
                || cls.contains("Commutator") || cls.contains("Winding")) {
            return new com.hdf.cryptand.neoforge.powergrid.adapter.MotorParameters();
        }
        return null;
    }

    /** 反射按字段名设置参数（double/int/float）。 */
    private static boolean cryptand$setDeviceField(com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParameters p,
                                                   String key, double v) {
        try {
            for (Class<?> c = p.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(key);
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == double.class) f.setDouble(p, v);
                    else if (t == int.class) f.setInt(p, (int) v);
                    else if (t == float.class) f.setFloat(p, (float) v);
                    else return false;
                    return true;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        var subs = com.hdf.cryptand.neoforge.core.library.ComponentLibrary.get().all();
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
        var models = com.hdf.cryptand.neoforge.core.library.ComponentLibrary.get().allModels();
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
                com.hdf.cryptand.neoforge.core.library.ComponentLibrary.get().get(name);
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
                com.hdf.cryptand.neoforge.core.library.ComponentLibrary.get().getModel(name);
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
        int n = com.hdf.cryptand.neoforge.core.library.ComponentLibrary.reload().size();
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "元件库已重新加载，共 " + n + " 条目"), true);
        return 1;
    }

    /** /cryptand library set <pos> <name> [k=v,...]：设置可编程元件方块。 */
    private static int cryptand$librarySet(net.minecraft.commands.CommandSourceStack src,
                                           net.minecraft.core.BlockPos pos, String name,
                                           String paramsText) {
        net.minecraft.world.level.Level level = src.getLevel();
        if (level == null) return 0;
        if (!(level.getBlockEntity(pos)
                instanceof com.hdf.cryptand.neoforge.powergrid.creative.ProgrammableComponentBlockEntity pcbe)) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "该位置不是可编程元件方块: " + pos.toShortString()));
            return 0;
        }
        if (com.hdf.cryptand.neoforge.core.library.ComponentLibrary.get().get(name) == null) {
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
        pcbe.setLibraryAndResolve(name, params);
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已设置 " + pos.toShortString() + " → " + name
                        + "，端子数=" + pcbe.getPortCount()), true);
        return 1;
    }

    /** 导出指定方块所在电路网络为 test 电路图 JSON（/cryptand export here|<pos>，异步）。
     *  通过 CircuitExportService 提交到分配器工作线程，默认保存到
     *  <gamedir>/cryptand/circuits/，完成后主线程通知玩家。 */
    private static int cryptand$exportCircuitJson(
            net.minecraft.commands.CommandSourceStack src,
            net.minecraft.core.BlockPos target) {
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception ex) {
            player = null;
        }
        com.hdf.cryptand.neoforge.core.export.CircuitExportService
                .exportAtAsync(target, player);
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "电路图导出任务已提交（异步，保存到 "
                        + com.hdf.cryptand.neoforge.core.export
                                .CircuitExportService.circuitsDir()
                        + "）"), false);
        return 1;
    }

    /** 导出指定方块所在电路网络（/cryptand schematic here|<pos>）。 */
    private static int cryptand$exportSelected(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos target,
            net.minecraft.commands.CommandSourceStack src) {
        try {
            String md = com.hdf.cryptand.neoforge.core.export.CircuitSchematicExporter
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

    private void commonSetup(final FMLCommonSetupEvent event) {
        WAF_LOGGER.info("Cryptand Common Setup Start");
        // 求解精度：配置选择 float（默认）/double。写入 Solvers 工厂全局开关
        try {
            com.hdf.cryptand.circuitsimulation.solver.Solvers.floatEnabled =
                    com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_FLOAT_SOLVER.get();
            WAF_LOGGER.info("Solver precision: {}",
                    com.hdf.cryptand.circuitsimulation.solver.Solvers.floatEnabled ? "float" : "double");
        } catch (Throwable t) {
            WAF_LOGGER.warn("Solver precision init failed: {}", t.toString());
        }
        // Create: Aeronautics 联动初始化（未装 → 自动跳过，不启用任何联动）
        try {
            com.hdf.cryptand.neoforge.aeronautics.AeronauticsCompat.init();
        } catch (Throwable t) {
            WAF_LOGGER.warn("Aeronautics compat init failed: {}", t.toString());
        }
        WAF_LOGGER.info("Cryptand Common End");
    }
}