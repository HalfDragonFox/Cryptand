package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.mc.McBuild;
import com.hdf.cryptand.neoforge.aiauto.mc.WorldOps;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 工具分类：**建造**（放置 / 填充 / 空心盒）+ 万能命令出口。
 *
 * <p><b>依赖类别</b>：建造类 = {@code world} —— 直接写服务端世界（{@link WorldOps}），
 * 不需要玩家存在、不需要 op/作弊；只有 {@code run_command} 标 {@code player}
 * （它本质是"替玩家敲命令"，必须有玩家连接）。</p>
 *
 * <p>多人服务器下无法直写世界（没有内置服务端），此时建造类自动回退到玩家命令通道。</p>
 */
public final class AiCommandTools {

    private AiCommandTools() {
    }

    public static void registerAll() {
        // 万能通道：原版能做的都能做 —— 但它是"替玩家敲命令"，必须有玩家
        // 万能通道：原版能做的都能做。⚠ 在**集成服务端**执行并**回传输出**（用户 2026-09-26：
        //   "run_command 回传命令输出，不能只回已执行，否则命令类验证无法诊断"）——
        //   所以它必须有集成服务端（单人）；多人直连时明确报错，不假装成功。
        AiToolServer.register("run_command",
                "在集成服务端执行任意原版命令并**回传输出**（单人；多人直连拿不到回显）",
                AiToolServer.schema("command", "string（不含前导斜杠）"),
                "player",
                args -> {
                    final String cmd = AiToolServer.str(args, "command", "");
                    if (cmd.isBlank()) {
                        return AiToolServer.ok("ERR 命令为空");
                    }
                    final String out = AiToolServer.commandOutput(cmd);
                    return AiToolServer.ok(out == null
                            ? "ERR 没有集成服务端（多人直连）或没有玩家 ⇒ 无法回传命令输出"
                            : cmd + "\n" + out);
                });

        // 退出前存档（用户 2026-09-27 明令："每次退出最好增加退出存档，防止未保存"）。
        //   ⚠ 为什么不直接用 run_command 敲 /save-all：那个命令要求权限等级 4，而这个世界里
        //   玩家的权限不够 ⇒ 命令树里"看不见"它，回的是"未知或不完整的命令"（真机实测）。
        //   所以这里直接调**服务器保存 API**：不经过命令/权限，AI 想存就一定能存。
        //   纪律：无人化在杀客户端进程之前**必须先调它** —— 直接 kill 会丢掉未写盘的世界改动
        //   （机器/盘的挂载状态、方块实体 NBT 等）。
        AiToolServer.register("save_world",
                "把集成服务端的世界写盘（玩家数据 + 所有维度区块 flush）—— 等价 /save-all 但不走命令权限。"
                        + "无人化**退出客户端之前必须先调它**：直接 kill 进程会丢未保存的世界改动。",
                AiToolServer.schema("confirm", "bool（必须为 true，避免误触）"),
                "session",
                args -> {
                    if (!AiToolServer.bool(args, "confirm", false)) {
                        return AiToolServer.ok("ERR 需要 {\"confirm\":true} 才会存档");
                    }
                    final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    final net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return AiToolServer.ok("ERR 没有集成服务端（多人直连 / 还没进世界）");
                    }
                    final long started = System.currentTimeMillis();
                    server.getPlayerList().saveAll();
                    int levels = 0;
                    for (final net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                        lvl.save(null, true, false);   // flush=true：区块真的写盘，不只是标脏
                        levels++;
                    }
                    return AiToolServer.ok("已存档：玩家数据 + " + levels + " 个维度区块已 flush，耗时 "
                            + (System.currentTimeMillis() - started) + " ms");
                });
        // ==================== 客户端侧 GUI 交互（无人化验证 2026-09-27）====================
        //   为什么要这三个：OC 屏的"右键开窗"是**客户端**行为（`BlockState.useWithoutItem` → `showGui` →
        //   `setScreen`），只模拟服务端右键（`oc_use_item`）永远开不了窗；而"窗内打字"也只有把按键送进
        //   `Screen.keyPressed/charTyped` 才等价于真人敲键（否则只验证到 guest 侧的 oc_key 注入）。
        AiToolServer.register("ai_gui_use",
                "**客户端**空手右键（真人对空手右键的那条路）—— 用于打开 OC 屏的终端窗口这类客户端 GUI",
                AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                        "z", "int/string（支持 ~）"),
                "player",
                args -> {
                    final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) {
                        return AiToolServer.ok("ERR 还没进世界");
                    }
                    final net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
                            AiToolServer.num(args, "x", 0), AiToolServer.num(args, "y", 0),
                            AiToolServer.num(args, "z", 0));
                    final net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
                    final net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
                    final net.minecraft.world.InteractionResult r = state.useWithoutItem(mc.level, mc.player, hit);
                    return AiToolServer.ok("client-use ; block=" + state.getBlock() + " ; result=" + r
                            + " ; screen=" + (mc.screen == null ? "(none)" : mc.screen.getClass().getSimpleName()));
                });

        AiToolServer.register("ai_gui_key",
                "把一次按键送进**当前客户端界面**（`Screen.keyPressed` + `charTyped`）—— 与真人敲键同构，"
                        + "用于验证「窗内打字到 guest」这条链；char=0 表示只按键不输字符",
                AiToolServer.schema("key", "int GLFW 键码（回车=257、退格=259、字母=ASCII 大写）",
                        "char", "int（可选，要输入的字符码点，默认 0）"),
                "player",
                args -> {
                    final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    final net.minecraft.client.gui.screens.Screen screen = mc.screen;
                    if (screen == null) {
                        return AiToolServer.ok("ERR 当前没有打开的界面（先用 ai_gui_use 打开 OC 屏窗口）");
                    }
                    final int key = AiToolServer.num(args, "key", 0);
                    final int ch = AiToolServer.num(args, "char", 0);
                    screen.keyPressed(key, 0, 0);
                    if (ch > 0) {
                        screen.charTyped((char) ch, 0);
                    }
                    return AiToolServer.ok("client-key ; key=" + key + " char=" + ch
                            + " ; screen=" + screen.getClass().getSimpleName());
                });

        AiToolServer.register("ai_gui_close",
                "关闭当前客户端界面（把屏幕置空），用于收拾无人化测试现场",
                AiToolServer.schema(),
                "player",
                args -> {
                    final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    final String was = mc.screen == null ? "(none)" : mc.screen.getClass().getSimpleName();
                    mc.setScreen(null);
                    return AiToolServer.ok("client-close ; was=" + was);
                });

        AiToolServer.register("place_block",
                "放置单个方块（直写世界，不需要玩家；支持 blockstate）",
                AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                        "z", "int/string（支持 ~）",
                        "block", "string（简名 stone；带状态如 oak_stairs[facing=north]）"),
                args -> {
                    final String block = AiToolServer.str(args, "block", "stone");
                    if (WorldOps.available()) {
                        final BlockPos pos = WorldOps.resolve(AiToolServer.str(args, "x", "~"),
                                AiToolServer.str(args, "y", "~"), AiToolServer.str(args, "z", "~"));
                        final var state = WorldOps.parseState(block);
                        if (state != null && WorldOps.setBlock(pos, state)) {
                            return AiToolServer.ok("已放置 " + block + " @ " + pos.toShortString());
                        }
                    }
                    return AiToolServer.ok(AiToolServer.command("setblock "
                            + AiToolServer.str(args, "x", "~") + " " + AiToolServer.str(args, "y", "~")
                            + " " + AiToolServer.str(args, "z", "~") + " "
                            + McBuild.normalize(block))
                            ? "已放置（命令通道回退）" : "ERR 无法直写世界且没有玩家");
                });

        AiToolServer.register("fill_region",
                "填充长方体区域（直写世界，不需要玩家）",
                AiToolServer.schema("x1", "int/string（支持 ~）", "y1", "int/string（支持 ~）",
                        "z1", "int/string（支持 ~）", "x2", "int/string（支持 ~）",
                        "y2", "int/string（支持 ~）", "z2", "int/string（支持 ~）",
                        "block", "string（简名或带 blockstate）"),
                args -> {
                    final String block = AiToolServer.str(args, "block", "stone");
                    if (WorldOps.available()) {
                        final BlockPos a = WorldOps.resolve(AiToolServer.str(args, "x1", "~"),
                                AiToolServer.str(args, "y1", "~"), AiToolServer.str(args, "z1", "~"));
                        final BlockPos b = WorldOps.resolve(AiToolServer.str(args, "x2", "~"),
                                AiToolServer.str(args, "y2", "~"), AiToolServer.str(args, "z2", "~"));
                        final var state = WorldOps.parseState(block);
                        if (state != null) {
                            final int n = WorldOps.fill(a, b, state);
                            if (n >= 0) {
                                return AiToolServer.ok("已填充 " + n + " 格 " + block
                                        + "（" + a.toShortString() + " → " + b.toShortString() + "）");
                            }
                        }
                    }
                    final String cmd = "fill "
                            + AiToolServer.str(args, "x1", "~") + " " + AiToolServer.str(args, "y1", "~")
                            + " " + AiToolServer.str(args, "z1", "~") + " "
                            + AiToolServer.str(args, "x2", "~") + " " + AiToolServer.str(args, "y2", "~")
                            + " " + AiToolServer.str(args, "z2", "~") + " "
                            + McBuild.normalize(block);
                    return AiToolServer.ok(AiToolServer.command(cmd)
                            ? "已填充（命令通道回退）" : "ERR 无法直写世界且没有玩家");
                });

        // 批量导入：写好"方块 + 坐标"就能一次落地（蓝图式建造的主力工具）
        AiToolServer.register("place_batch",
                "批量放置方块：一次写入一整串 {x,y,z,block}（直写服务端世界，不需要玩家）",
                AiToolServer.schema(
                        "blocks", "两种写法都收："
                                + "[{\"x\":0,\"y\":-60,\"z\":0,\"block\":\"oak_planks\"}, …] "
                                + "或紧凑映射 {\"0,-60,0\":\"oak_planks\", …}；"
                                + "坐标为数字或 \"~\"/\"~n\" 字符串，block 支持 blockstate",
                        "batch", "int（每批方块数，默认 16384；写太多会卡主线程，调小更平滑）"),
                args -> {
                    final com.google.gson.JsonElement el = args.get("blocks");
                    if (el == null || el.isJsonNull()) {
                        return AiToolServer.ok("ERR 缺少 blocks");
                    }
                    final java.util.List<BlockPos> positions = new java.util.ArrayList<>();
                    final java.util.List<net.minecraft.world.level.block.state.BlockState> states =
                            new java.util.ArrayList<>();
                    final java.util.List<String> bad = new java.util.ArrayList<>();
                    try {
                        if (el.isJsonArray()) {
                            for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                                final JsonObject o = e.getAsJsonObject();
                                final BlockPos pos = WorldOps.resolve(
                                        o.has("x") ? o.get("x").getAsString() : "~",
                                        o.has("y") ? o.get("y").getAsString() : "~",
                                        o.has("z") ? o.get("z").getAsString() : "~");
                                final var state = WorldOps.parseState(
                                        o.has("block") ? o.get("block").getAsString() : "");
                                if (state == null) {
                                    bad.add(pos.toShortString());
                                    continue;
                                }
                                positions.add(pos);
                                states.add(state);
                            }
                        } else if (el.isJsonObject()) {
                            for (var entry : el.getAsJsonObject().entrySet()) {
                                final String[] xyz = entry.getKey().split(",");
                                if (xyz.length < 3) {
                                    bad.add(entry.getKey());
                                    continue;
                                }
                                final BlockPos pos = WorldOps.resolve(xyz[0], xyz[1], xyz[2]);
                                final var state = WorldOps.parseState(entry.getValue().getAsString());
                                if (state == null) {
                                    bad.add(entry.getKey());
                                    continue;
                                }
                                positions.add(pos);
                                states.add(state);
                            }
                        } else {
                            return AiToolServer.ok("ERR blocks 需为数组或 \"x,y,z\"→block 映射");
                        }
                    } catch (Throwable ex) {
                        return AiToolServer.ok("ERR 解析失败：" + ex);
                    }
                    if (positions.isEmpty()) {
                        return AiToolServer.ok("ERR 没有可用的方块条目");
                    }
                    final int batchSize = AiToolServer.num(args, "batch", WorldOps.DEFAULT_BATCH);
                    final int n = WorldOps.setBatch(positions, states, batchSize);
                    if (n < 0) {
                        return AiToolServer.ok("ERR 无法直写世界（需要单人游戏内置服务端）");
                    }
                    final int size = batchSize > 0 ? batchSize : WorldOps.DEFAULT_BATCH;
                    final JsonObject o = new JsonObject();
                    o.addProperty("message", "已排入 " + n + " 个方块（分 "
                            + ((n + size - 1) / size) + " 批，每批 " + size
                            + "，逐 tick 落地）" + (bad.isEmpty() ? "" : "；" + bad.size() + " 条被跳过"));
                    o.addProperty("placed", n);
                    o.addProperty("batches", (n + size - 1) / size);
                    o.addProperty("pendingBatches", WorldOps.pendingBatches());
                    o.addProperty("hint", "用 {\"wait\":{\"batches\":0}} 等全部落地后再扫描");
                    if (!bad.isEmpty()) {
                        final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                        bad.stream().limit(10).forEach(arr::add);
                        o.add("skipped", arr);
                    }
                    return o;
                });

        // 客户端命令通道（2026-09-29 新增）：`/cryptand aiauto ...` 是**客户端命令**（RegisterClientCommandsEvent），
        // 服务端 dispatcher 看不到它（真机实测 "help cryptand" 里没有 aiauto）⇒ 无人化验证需要这条入口。
        AiToolServer.register("player_command",
                "以玩家身份输入一条命令（走客户端命令表 ⇒ 能执行 /cryptand aiauto 这类客户端命令）",
                AiToolServer.schema("command", "string（不含前导斜杠）"),
                "player",
                args -> {
                    final String text = AiToolServer.str(args, "command", "").trim();
                    if (text.isEmpty()) {
                        return AiToolServer.ok("ERR 命令为空");
                    }
                    final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player == null || mc.getConnection() == null) {
                        return AiToolServer.ok("ERR 没有玩家/连接");
                    }
                    mc.execute(() -> {
                        try {
                            mc.player.connection.sendCommand(text.startsWith("/") ? text.substring(1) : text);
                        } catch (Throwable t) {
                            org.slf4j.LoggerFactory.getLogger("aiauto")
                                    .warn("[aiauto] player_command 失败：{}", t.toString());
                        }
                    });
                    return AiToolServer.ok("已以玩家身份提交命令：" + text);
                });

        AiToolServer.register("hollow_box",
                "空心盒（先实心再掏空；以玩家为锚，直写世界）",
                AiToolServer.schema("w", "int", "h", "int", "d", "int", "block", "string"),
                args -> {
                    final int w = AiToolServer.num(args, "w", 5);
                    final int h = AiToolServer.num(args, "h", 4);
                    final int d = AiToolServer.num(args, "d", 5);
                    final String block = AiToolServer.str(args, "block", "stone");
                    if (WorldOps.available()) {
                        final var state = WorldOps.parseState(block);
                        WorldOps.fill(WorldOps.resolve("~1", "~", "~1"),
                                WorldOps.resolve("~" + w, "~" + (h - 1), "~" + d), state);
                        if (w > 2 && d > 2 && h > 2) {
                            WorldOps.fill(WorldOps.resolve("~2", "~1", "~2"),
                                    WorldOps.resolve("~" + (w - 1), "~" + (h - 2), "~" + (d - 1)),
                                    WorldOps.parseState("air"));
                        }
                        return AiToolServer.ok("OK hollow " + w + "x" + h + "x" + d + " " + block);
                    }
                    return AiToolServer.ok(McBuild.build("hollow", McBuild.normalizeAll(List.of(
                            String.valueOf(w), String.valueOf(h), String.valueOf(d), block))));
                });
    }
}
