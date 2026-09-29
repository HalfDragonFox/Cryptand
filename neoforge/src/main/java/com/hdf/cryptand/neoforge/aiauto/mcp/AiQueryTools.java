package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.mc.McReports;
import com.hdf.cryptand.neoforge.aiauto.mc.WorldOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * 工具分类：**只读查询**（状态快照 / 注册表 / 配方 / 方块 / 实体）。
 *
 * <p><b>依赖类别</b>：方块与区域查询（{@code block_at} / {@code scan_region}）走
 * {@link WorldOps} 直读<b>服务端世界</b> —— 不需要玩家、不受客户端视距限制，
 * 而且与建造写入同一个服务端队列，"先写后读"顺序一致。其余注册表类查询不依赖世界。</p>
 */
public final class AiQueryTools {

    /** 区域扫描体积上限（64³）：任务在服务端线程跑，防止一次扫描过大卡帧 */
    private static final long MAX_SCAN = 262144L;

    private AiQueryTools() {
    }

    public static void registerAll() {
        // 每类状态一个独立工具（便于单独调用与组合）
        for (String kind : McReports.KINDS) {
            AiToolServer.register("state_" + kind, "查询 " + kind + " 状态（文本块）",
                    AiToolServer.schema(), args -> {
                        final JsonObject o = new JsonObject();
                        o.addProperty("kind", kind);
                        o.addProperty("text", McReports.build(kind));
                        return o;
                    });
        }

        // 已加载的 mod（结构化：modid / 名称 / 版本）—— 与 state_mods 的文本报告互补
        AiToolServer.register("list_mods", "列已加载的 mod（modid / 名称 / 版本，结构化）",
                AiToolServer.schema("filter", "string（modid 或名称子串，可空）",
                        "limit", "int（默认 200）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    try {
                        final String f = AiToolServer.str(args, "filter", "").toLowerCase(Locale.ROOT);
                        final JsonArray arr = new JsonArray();
                        net.neoforged.fml.ModList.get().getMods().stream()
                                .filter(m -> f.isEmpty()
                                        || m.getModId().toLowerCase(Locale.ROOT).contains(f)
                                        || String.valueOf(m.getDisplayName())
                                                .toLowerCase(Locale.ROOT).contains(f))
                                .sorted(java.util.Comparator.comparing(
                                        (net.neoforged.neoforgespi.language.IModInfo m) -> m.getModId()))
                                .limit(AiToolServer.num(args, "limit", 200))
                                .forEach(m -> {
                                    final JsonObject j = new JsonObject();
                                    j.addProperty("id", m.getModId());
                                    j.addProperty("name", String.valueOf(m.getDisplayName()));
                                    j.addProperty("version", String.valueOf(m.getVersion()));
                                    arr.add(j);
                                });
                        o.addProperty("returned", arr.size());
                        o.add("mods", arr);
                    } catch (Throwable ex) {
                        o.addProperty("error", String.valueOf(ex));
                    }
                    return o;
                });

        AiToolServer.register("list_registry", "列任意注册表条目（结构化，可按 modid 过滤）",
                AiToolServer.schema("registry", "string（如 minecraft:item）",
                        "filter", "string（子串，可空）",
                        "mod", "string（modid/命名空间，如 create；可空）",
                        "limit", "int（默认 100）"),
                args -> entries(AiToolServer.str(args, "registry", "minecraft:item"),
                        AiToolServer.str(args, "filter", ""), AiToolServer.str(args, "mod", ""),
                        AiToolServer.num(args, "limit", 100)));

        AiToolServer.register("list_blocks", "列方块（结构化，可按 modid 取某 mod 的全部方块）",
                AiToolServer.schema("filter", "string（子串，可空）",
                        "mod", "string（modid/命名空间，如 create；可空）",
                        "limit", "int（默认 100）"),
                args -> entries("minecraft:block", AiToolServer.str(args, "filter", ""),
                        AiToolServer.str(args, "mod", ""), AiToolServer.num(args, "limit", 100)));

        AiToolServer.register("list_items", "列物品（结构化，可按 modid 过滤）",
                AiToolServer.schema("filter", "string（子串，可空）",
                        "mod", "string（modid/命名空间，如 create；可空）",
                        "limit", "int（默认 100）"),
                args -> entries("minecraft:item", AiToolServer.str(args, "filter", ""),
                        AiToolServer.str(args, "mod", ""), AiToolServer.num(args, "limit", 100)));

        AiToolServer.register("list_entities", "列附近实体（结构化；读客户端世界，需要玩家）",
                AiToolServer.schema("radius", "double（默认 64）", "filter", "string（可空）"),
                "player",
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) {
                        o.addProperty("error", "无世界/玩家");
                        return o;
                    }
                    final double r = AiToolServer.dbl(args, "radius", 64);
                    final String f = AiToolServer.str(args, "filter", "");
                    final JsonArray arr = new JsonArray();
                    mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(r)).stream()
                            .filter(e -> f.isEmpty() || BuiltInRegistries.ENTITY_TYPE
                                    .getKey(e.getType()).toString().contains(f))
                            .limit(200)
                            .forEach(e -> {
                                final JsonObject j = new JsonObject();
                                j.addProperty("type",
                                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                                j.addProperty("x", e.getX());
                                j.addProperty("y", e.getY());
                                j.addProperty("z", e.getZ());
                                arr.add(j);
                            });
                    o.addProperty("radius", r);
                    o.addProperty("count", arr.size());
                    o.add("entities", arr);
                    return o;
                });

        AiToolServer.register("block_at", "查某坐标的方块（直读服务端世界，不需要玩家；支持 ~）",
                AiToolServer.schema("x", "int/string", "y", "int/string", "z", "int/string"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final var pos = WorldOps.resolve(AiToolServer.str(args, "x", "~"),
                            AiToolServer.str(args, "y", "~"), AiToolServer.str(args, "z", "~"));
                    final var state = WorldOps.query(level -> level.getBlockState(pos), null);
                    if (state == null) {
                        o.addProperty("error", "无法读取世界（需要单人游戏内置服务端）");
                        return o;
                    }
                    o.addProperty("pos", pos.toShortString());
                    o.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    o.addProperty("state", state.toString());
                    o.addProperty("light",
                            WorldOps.query(level -> level.getMaxLocalRawBrightness(pos), -1));
                    return o;
                });

        // 区域扫描：一次"看清"一片区域（建造验收 / 地形勘察 / 找方块），替代逐格 block_at
        // 扫描 → 默认导出 AI 易读文本文件（汇总 + 调色板 + ASCII 分层图 + 明细）。
        // 比截图可靠：纯数据、精确、不受渲染管线影响 —— 这是 AI"看清建筑/地形"的主力通道。
        AiToolServer.register("scan_region",
                "扫描矩形区域（直读服务端世界）：汇总 + ASCII 分层图 + 明细，并导出 latest-scan-<name>.txt",
                AiToolServer.schema("x1", "int/string（支持 ~）", "y1", "int/string（支持 ~）",
                        "z1", "int/string（支持 ~）", "x2", "int/string（支持 ~）",
                        "y2", "int/string（支持 ~）", "z2", "int/string（支持 ~）",
                        "name", "string（导出文件名，默认 region → latest-scan-<name>.txt）",
                        "filter", "string（方块 id 子串，可空）",
                        "detail", "bool（默认 true：导出非空气明细）",
                        "map", "bool（默认 true：导出 ASCII 分层图）",
                        "limit", "int（明细上限，默认 300）"),
                AiQueryTools::scanRegion);

        AiToolServer.register("list_recipes", "列配方（总数 + 取样）",
                AiToolServer.schema("limit", "int（默认 50）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) {
                        o.addProperty("error", "无世界");
                        return o;
                    }
                    final var manager = mc.level.getRecipeManager();
                    o.addProperty("total", manager.getRecipeIds().count());
                    final JsonArray sample = new JsonArray();
                    manager.getRecipeIds().map(Object::toString).sorted()
                            .limit(AiToolServer.num(args, "limit", 50)).forEach(sample::add);
                    o.add("sample", sample);
                    return o;
                });
    }

    // ==================== 区域扫描 ====================

    /** 扫一片区域：汇总（谁最多）+ 明细（在哪）+ 可选分层图（长什么样） */
    private static JsonObject scanRegion(JsonObject args) {
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        try {
            final var a = WorldOps.resolve(AiToolServer.str(args, "x1", "~"),
                    AiToolServer.str(args, "y1", "~"), AiToolServer.str(args, "z1", "~"));
            final var b = WorldOps.resolve(AiToolServer.str(args, "x2", "~"),
                    AiToolServer.str(args, "y2", "~"), AiToolServer.str(args, "z2", "~"));
            minX = Math.min(a.getX(), b.getX());
            maxX = Math.max(a.getX(), b.getX());
            minY = Math.min(a.getY(), b.getY());
            maxY = Math.max(a.getY(), b.getY());
            minZ = Math.min(a.getZ(), b.getZ());
            maxZ = Math.max(a.getZ(), b.getZ());
        } catch (Throwable ex) {
            return error("坐标解析失败：" + ex);
        }
        final long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_SCAN) {
            return error("区域过大：" + volume + " 格 > 上限 " + MAX_SCAN + "，请缩小范围");
        }
        final JsonObject result = WorldOps.query(
                level -> scanOn(level, args, minX, minY, minZ, maxX, maxY, maxZ), null);
        if (result == null) {
            return error("无法读取世界（需要单人游戏内置服务端）");
        }
        // ★ 导出成 AI 易读的文本：汇总 → 调色板 → 分层图 → 明细
        try {
            final String name = AiToolServer.str(args, "name", "region");
            final StringBuilder sb = new StringBuilder(32768);
            sb.append("# Cryptand aiauto — 区域扫描\n");
            sb.append("# 区域 (").append(minX).append(',').append(minY).append(',').append(minZ)
                    .append(") → (").append(maxX).append(',').append(maxY).append(',').append(maxZ)
                    .append(")   体积=").append(result.get("volume").getAsLong()).append('\n');
            sb.append("# 空气=").append(result.get("air").getAsLong())
                    .append("   非空气=").append(result.get("nonAir").getAsLong()).append('\n');
            sb.append("# 汇总（按数量降序）:\n");
            for (var c : result.getAsJsonArray("counts")) {
                final var e = c.getAsJsonObject();
                sb.append("#   ").append(e.get("block").getAsString())
                        .append(" x ").append(e.get("count").getAsInt()).append('\n');
            }
            if (result.has("mapLegend")) {
                sb.append("# 调色板: ");
                final var legend = result.getAsJsonObject("mapLegend");
                for (var k : legend.keySet()) {
                    sb.append(k).append('=').append(legend.get(k).getAsString()).append("  ");
                }
                sb.append('\n');
                sb.append("# 分层图（层按 y 递增；每层各行按 z 北→南；行内字符按 x 西→东；"
                        + "'.'=空气 '#'=调色板外 '?'=区块未加载）:\n");
                for (var l : result.getAsJsonArray("layers")) {
                    final var layer = l.getAsJsonObject();
                    sb.append("y=").append(layer.get("y").getAsInt()).append('\n');
                    for (var row : layer.getAsJsonArray("rows")) {
                        sb.append("    ").append(row.getAsString()).append('\n');
                    }
                }
            }
            if (result.has("entries")) {
                sb.append("# 非空气明细（x,y,z,block）:\n");
                for (var en : result.getAsJsonArray("entries")) {
                    final var e = en.getAsJsonObject();
                    sb.append("#   ").append(e.get("x").getAsInt()).append(',')
                            .append(e.get("y").getAsInt()).append(',')
                            .append(e.get("z").getAsInt()).append(',')
                            .append(e.get("block").getAsString()).append('\n');
                }
            }
            final java.nio.file.Path out =
                    com.hdf.cryptand.neoforge.aiauto.AiAutomation.artifact("scan", name, "txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            result.addProperty("exported", out.toString());
            result.addProperty("exportedBytes", sb.length());
        } catch (Throwable ex) {
            result.addProperty("exportError", String.valueOf(ex));
        }
        return result;
    }

    private static JsonObject error(String message) {
        final JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    /** 真正的扫描（在服务端线程执行） */
    private static JsonObject scanOn(ServerLevel level, JsonObject args,
                                     int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        final JsonObject o = new JsonObject();
        final String f = AiToolServer.str(args, "filter", "").toLowerCase(Locale.ROOT);
        final int limit = Math.max(1, AiToolServer.num(args, "limit", 300));
        final boolean detail = AiToolServer.bool(args, "detail", true);
        final boolean wantMap = AiToolServer.bool(args, "map", true);

        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        final JsonArray entries = new JsonArray();
        int air = 0;
        int nonAir = 0;
        int matched = 0;
        int unloaded = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    final var pos = new net.minecraft.core.BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        unloaded++;
                        continue;
                    }
                    final var state = level.getBlockState(pos);
                    final String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    counts.merge(id, 1, Integer::sum);
                    if (state.isAir()) {
                        air++;
                        continue;
                    }
                    nonAir++;
                    if (!f.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(f)) {
                        continue;
                    }
                    matched++;
                    if (detail && entries.size() < limit) {
                        final JsonObject e = new JsonObject();
                        e.addProperty("x", x);
                        e.addProperty("y", y);
                        e.addProperty("z", z);
                        e.addProperty("block", id);
                        entries.add(e);
                    }
                }
            }
        }
        o.addProperty("from", minX + "," + minY + "," + minZ);
        o.addProperty("to", maxX + "," + maxY + "," + maxZ);
        o.addProperty("volume", (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
        o.addProperty("air", air);
        o.addProperty("nonAir", nonAir);
        o.addProperty("matched", matched);
        if (unloaded > 0) {
            // 未加载区块会被跳过 —— 明确报出来，别让 AI 把"没加载"当成"是空气"
            o.addProperty("unloaded", unloaded);
        }
        final JsonArray summary = new JsonArray();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(en -> {
                    final JsonObject c = new JsonObject();
                    c.addProperty("block", en.getKey());
                    c.addProperty("count", en.getValue());
                    summary.add(c);
                });
        o.add("counts", summary);
        if (detail) {
            o.addProperty("returned", entries.size());
            o.addProperty("truncated", matched > entries.size());
            o.add("entries", entries);
        }
        if (wantMap) {
            addAsciiMap(o, level, counts, minX, minY, minZ, maxX, maxY, maxZ);
        }
        return o;
    }

    /** 附 ASCII 分层图：每层 y 一张字符网格（空气 '.'），字符按方块数量降序分配 */
    private static void addAsciiMap(JsonObject o, ServerLevel level,
                                    java.util.Map<String, Integer> counts,
                                    int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final java.util.List<String> order = counts.entrySet().stream()
                .filter(en -> !en.getKey().equals("minecraft:air"))
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(java.util.Map.Entry::getKey).toList();
        final java.util.Map<String, Character> palette = new java.util.HashMap<>();
        final JsonObject legend = new JsonObject();
        for (int i = 0; i < order.size() && i < alphabet.length(); i++) {
            final char ch = alphabet.charAt(i);
            palette.put(order.get(i), ch);
            legend.addProperty(String.valueOf(ch), order.get(i));
        }
        final JsonArray layers = new JsonArray();
        for (int y = minY; y <= maxY; y++) {
            final JsonObject layer = new JsonObject();
            layer.addProperty("y", y);
            final JsonArray rows = new JsonArray();
            for (int z = minZ; z <= maxZ; z++) {
                final StringBuilder sb = new StringBuilder();
                for (int x = minX; x <= maxX; x++) {
                    final var pos = new net.minecraft.core.BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        sb.append('?');
                        continue;
                    }
                    final var state = level.getBlockState(pos);
                    if (state.isAir()) {
                        sb.append('.');
                        continue;
                    }
                    final Character ch = palette.get(
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    sb.append(ch == null ? '#' : ch);
                }
                rows.add(sb.toString());
            }
            layer.add("rows", rows);
            layers.add(layer);
        }
        o.addProperty("mapAxes", "层按 y 递增（下→上）；每层 rows 按 z 递增（北→南）；"
                + "行内字符按 x 递增（西→东）；'.'=空气 '#'=调色板外的方块 '?'=区块未加载");
        o.add("mapLegend", legend);
        o.add("layers", layers);
    }

    /** 注册表条目查询（list_registry / list_blocks / list_items 共用） */
    private static JsonObject entries(String registryId, String filter, String mod, int limit) {
        final JsonObject o = new JsonObject();
        try {
            final var registry = BuiltInRegistries.REGISTRY.get(ResourceLocation.parse(registryId));
            if (registry == null) {
                o.addProperty("error", "未知注册表 " + registryId);
                return o;
            }
            final String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
            final String m = mod == null ? "" : mod.trim().toLowerCase(Locale.ROOT);
            final java.util.List<String> ids = registry.keySet().stream().map(ResourceLocation::toString)
                    .filter(id -> f.isEmpty() || id.toLowerCase(Locale.ROOT).contains(f))
                    .filter(id -> matchesMod(id, m))
                    .sorted().toList();
            final JsonArray arr = new JsonArray();
            ids.stream().limit(limit).forEach(arr::add);
            o.addProperty("registry", registryId);
            o.addProperty("total", registry.size());     // 该注册表全部条目
            o.addProperty("matched", ids.size());        // 过滤后命中数（未截断）
            o.addProperty("returned", arr.size());       // 本次实际返回
            if (!m.isEmpty()) {
                o.addProperty("mod", m);
            }
            o.add("entries", arr);
        } catch (Throwable ex) {
            o.addProperty("error", String.valueOf(ex));
        }
        return o;
    }

    /**
     * modid 过滤：含 ':' 视为条目 id 前缀（如 {@code create:mechanical}），
     * 否则视为命名空间精确匹配（如 {@code create} → 该 mod 的全部条目）。
     */
    private static boolean matchesMod(String id, String mod) {
        if (mod.isEmpty()) {
            return true;
        }
        final String lower = id.toLowerCase(Locale.ROOT);
        if (mod.indexOf(':') >= 0) {
            return lower.startsWith(mod);
        }
        final int colon = lower.indexOf(':');
        return colon > 0 && lower.substring(0, colon).equals(mod);
    }
}
