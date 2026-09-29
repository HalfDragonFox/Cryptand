package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/**
 * 工具分类：**世界与存档**（新建存档并直接载入，绕过菜单）。
 *
 * <p>为什么需要：原版没有"新建世界"命令，AI 也点不了原版菜单。
 * 本工具是<b>纯原版实现</b>：只用原版 API {@code Minecraft#createWorldOpenFlows().createFreshLevel(...)}
 * 与 {@code LevelSettings / WorldOptions / WorldPresets}，<b>不引用 LDLib2 或任何其它 mod 的类</b>，
 * 所以原版环境下也照常可用。（LDLib2 UITest 的 {@code WorldBootstrap} 走的是同一条原版调用，
 * 仅作旁证，不是依赖 —— 见 .ai_cache/LDLib2/.../uitest/WorldBootstrap.java。）</p>
 *
 * <p>允许在标题界面调用（ClientTickEvent 在主菜单同样触发），所以可以"启动 → 直接进新世界"全无人值守。</p>
 */
public final class AiWorldTools {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AiWorldTools() {
    }

    public static void registerAll() {
        AiToolServer.register("list_worlds", "列本地存档（saves/ 目录下的名字）",
                AiToolServer.schema(),
                args -> {
                    final JsonObject o = new JsonObject();
                    final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    try (var stream = java.nio.file.Files.list(
                            net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("saves"))) {
                        stream.filter(java.nio.file.Files::isDirectory)
                                .sorted()
                                .forEach(p -> arr.add(p.getFileName().toString()));
                    } catch (Throwable ex) {
                        o.addProperty("error", String.valueOf(ex));
                    }
                    o.add("worlds", arr);
                    return o;
                });

        AiToolServer.register("load_world",
                "载入一个已有存档（绕过菜单；重启客户端后回标题界面时用它回到工地）",
                AiToolServer.schema("name", "string（存档目录名，见 list_worlds）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    try {
                        final String name = AiToolServer.str(args, "name", "").trim();
                        if (name.isEmpty()) {
                            o.addProperty("error", "需要 name（先用 list_worlds 看有哪些存档）");
                            return o;
                        }
                        Minecraft.getInstance().createWorldOpenFlows()
                                .openWorld(name, () -> o.addProperty("note", "打开失败回调已触发"));
                        o.addProperty("level", name);
                        o.addProperty("message", "已请求载入存档 " + name + "；用 wait world 等它进来");
                    } catch (Throwable ex) {
                        o.addProperty("error", String.valueOf(ex));
                    }
                    return o;
                });

        AiToolServer.register("new_world",
                "新建并直接载入一个存档（绕过菜单；可选地形/种子/模式/难度）",
                AiToolServer.schema("name", "string（存档名，缺省 ai_<时间戳>；非法字符自动替换）",
                        "preset", "string（normal|flat|large_biomes|amplified，默认 normal）",
                        "seed", "long（缺省随机）",
                        "mode", "string（creative|survival|adventure|spectator，默认 creative）",
                        "difficulty", "string（peaceful|easy|normal|hard，默认 peaceful）",
                        "hardcore", "bool（默认 false）",
                        "allowCommands", "bool（默认 true）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    try {
                        final Minecraft mc = Minecraft.getInstance();
                        final String raw = AiToolServer.str(args, "name", "").trim();
                        final String name = raw.isEmpty()
                                ? "ai_world_" + LocalDateTime.now().format(STAMP)
                                : raw.replaceAll("[^A-Za-z0-9_\\-]", "_");
                        final String preset = AiToolServer.str(args, "preset", "normal").toLowerCase(Locale.ROOT);
                        final long seed = args.has("seed") && !args.get("seed").isJsonNull()
                                ? args.get("seed").getAsLong() : new Random().nextLong();
                        final GameType mode = switch (AiToolServer.str(args, "mode", "creative")
                                .toLowerCase(Locale.ROOT)) {
                            case "survival" -> GameType.SURVIVAL;
                            case "adventure" -> GameType.ADVENTURE;
                            case "spectator" -> GameType.SPECTATOR;
                            default -> GameType.CREATIVE;
                        };
                        final Difficulty difficulty = switch (AiToolServer.str(args, "difficulty", "peaceful")
                                .toLowerCase(Locale.ROOT)) {
                            case "easy" -> Difficulty.EASY;
                            case "normal" -> Difficulty.NORMAL;
                            case "hard" -> Difficulty.HARD;
                            default -> Difficulty.PEACEFUL;
                        };
                        final var presetKey = switch (preset) {
                            case "flat" -> WorldPresets.FLAT;
                            case "large_biomes" -> WorldPresets.LARGE_BIOMES;
                            case "amplified" -> WorldPresets.AMPLIFIED;
                            default -> WorldPresets.NORMAL;
                        };
                        final LevelSettings settings = new LevelSettings(name, mode,
                                AiToolServer.bool(args, "hardcore", false), difficulty,
                                AiToolServer.bool(args, "allowCommands", true),
                                new GameRules(), WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(name, settings,
                                new WorldOptions(seed, false, false),
                                registryAccess -> registryAccess.registryOrThrow(Registries.WORLD_PRESET)
                                        .getHolderOrThrow(presetKey).value().createWorldDimensions(),
                                null);
                        o.addProperty("level", name);
                        o.addProperty("preset", preset);
                        o.addProperty("seed", seed);
                        o.addProperty("mode", mode.getName());
                        o.addProperty("difficulty", difficulty.getKey());
                        o.addProperty("allowCommands", AiToolServer.bool(args, "allowCommands", true));
                        o.addProperty("message", "已开始创建并载入存档 " + name
                                + "；用 {\"wait\":{\"world\":true}} 等它就绪");
                    } catch (Throwable ex) {
                        o.addProperty("error", String.valueOf(ex));
                    }
                    return o;
                });
    }
}
