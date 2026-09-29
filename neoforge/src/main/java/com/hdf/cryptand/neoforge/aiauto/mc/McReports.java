package com.hdf.cryptand.neoforge.aiauto.mc;

import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ===== MC 状态与"动态加载内容"报告（aiauto 的 mc 目标）=====
 *
 * <p>给 AI 提供游戏运行时事实，重点是<b>动态加载</b>的东西（数据包/资源包/注册表/配方/标签），
 * 因为这些既不在源码里也不在静态分析范围内。</p>
 *
 * <p>报告写入 {@code <gameDir>/cryptand/ai-auto/latest-mc-<类别>.txt}，AI 直接读。</p>
 */
public final class McReports {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 可报告的类别（命令与 items() 共用） */
    public static final List<String> KINDS = List.of(
            "mods", "registries", "recipes", "tags", "packs", "world", "player", "entities");

    private McReports() {
    }

    /** 生成某类别的报告文本 */
    public static String build(String kind) {
        final StringBuilder sb = new StringBuilder(8192);
        sb.append("# Cryptand aiauto — MC 报告：").append(kind)
                .append("  @ ").append(LocalDateTime.now().format(STAMP)).append('\n');
        final Minecraft mc = Minecraft.getInstance();
        sb.append("# 环境：client=").append(net.minecraft.SharedConstants.getCurrentVersion().getName())
                .append("  world=").append(mc.level == null ? "（无）" : "已加载")
                .append("  screen=").append(mc.screen == null ? "（无）" : mc.screen.getClass().getSimpleName())
                .append('\n').append('\n');
        try {
            switch (kind) {
                case "mods" -> mods(sb);
                case "registries" -> registries(sb, mc);
                case "recipes" -> recipes(sb, mc);
                case "tags" -> tags(sb, mc);
                case "packs" -> packs(sb, mc);
                case "world" -> world(sb, mc);
                case "player" -> player(sb, mc);
                case "entities" -> entities(sb, mc);
                default -> sb.append("（未知类别：").append(kind).append("）\n");
            }
        } catch (Throwable ex) {
            sb.append("！生成报告时出错：").append(ex).append('\n');
        }
        return sb.toString();
    }

    public static Path write(String kind) throws IOException {
        Files.createDirectories(AiAutomation.outDir());
        final Path path = AiAutomation.artifact("mc", kind, "txt");
        Files.writeString(path, build(kind), StandardCharsets.UTF_8);
        Files.writeString(AiAutomation.outDir().resolve(
                "mc-" + kind + "-" + LocalDateTime.now().format(STAMP) + ".txt"),
                build(kind), StandardCharsets.UTF_8);
        return path;
    }

    // ==================== 各类别 ====================

    /** 已加载的模组（动态装载的东西，源码分析看不到） */
    private static void mods(StringBuilder sb) {
        final var mods = net.neoforged.fml.ModList.get().getMods();
        sb.append("已加载模组：").append(mods.size()).append(" 个\n");
        mods.stream()
                .sorted(Comparator.comparing(m -> m.getModId()))
                .forEach(m -> sb.append("  · ").append(m.getModId())
                        .append("  ").append(m.getVersion())
                        .append("  ").append(m.getDisplayName())
                        .append(m.getModId().equals("cryptand") ? "   ← Cryptand 自己" : "")
                        .append('\n'));
    }

    /** 注册表（静态 BuiltIn + 数据包驱动的动态 RegistryAccess） */
    private static void registries(StringBuilder sb, Minecraft mc) {
        sb.append("—— 静态注册表（BuiltInRegistries，代码注册）——\n");
        int staticTotal = 0;
        for (var entry : net.minecraft.core.registries.BuiltInRegistries.REGISTRY.entrySet()) {
            final var registry = entry.getValue();
            final int size = registry.size();
            staticTotal += size;
            sb.append("  ").append(entry.getKey().location()).append(" : ").append(size).append('\n');
        }
        sb.append("  静态条目合计：").append(staticTotal).append('\n').append('\n');

        final RegistryAccess access = registryAccess(mc);
        if (access == null) {
            sb.append("—— 动态注册表：不可用（未连接世界）——\n");
            return;
        }
        sb.append("—— 动态注册表（RegistryAccess：数据包驱动）——\n");
        access.registries().forEach(reg -> {
            final var registry = reg.value();
            sb.append("  ").append(reg.key().location()).append(" : ").append(registry.size()).append(" 条\n");
            // 给 AI 一点样本，便于判断内容形态
            final List<String> sample = new ArrayList<>();
            registry.keySet().stream().limit(5).forEach(id -> sample.add(id.toString()));
            sb.append("      样本：").append(String.join(", ", sample))
                    .append(registry.size() > 5 ? " …" : "").append('\n');
        });
    }

    /** 配方（数据包驱动） */
    private static void recipes(StringBuilder sb, Minecraft mc) {
        if (mc.level == null) {
            sb.append("（无世界，配方不可用）\n");
            return;
        }
        final var manager = mc.level.getRecipeManager();
        final var ids = manager.getRecipeIds().toList();
        sb.append("配方总数：").append(ids.size()).append('\n');
        sb.append("按类型：\n");
        manager.getRecipes().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.value().getType().toString(), java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(40)
                .forEach(e -> sb.append("  ").append(e.getKey()).append(" : ").append(e.getValue()).append('\n'));
    }

    /** 标签（数据包驱动） */
    private static void tags(StringBuilder sb, Minecraft mc) {
        final RegistryAccess access = registryAccess(mc);
        if (access == null) {
            sb.append("（未连接世界，标签不可用）\n");
            return;
        }
        access.registries().forEach(reg -> {
            final var tags = reg.value().getTags().toList();
            if (tags.isEmpty()) {
                return;
            }
            sb.append(reg.key().location()).append(" 标签 ").append(tags.size()).append(" 个\n");
            tags.stream().limit(12).forEach(t -> sb.append("  #").append(t.getFirst().location())
                    .append(" → ").append(t.getSecond().size()).append(" 条\n"));
        });
    }

    /** 数据包 / 资源包（动态加载的载体） */
    private static void packs(StringBuilder sb, Minecraft mc) {
        final var repo = mc.getResourcePackRepository();
        sb.append("已选资源包（含内建/服务器推送）：\n");
        repo.getSelectedIds().forEach(id -> sb.append("  · ").append(id).append('\n'));
        sb.append("\n资源包来源（PackResources）：\n");
        final var packs = mc.getResourceManager().listPacks().toList();
        sb.append("  合计 ").append(packs.size()).append(" 个\n");
        packs.stream().limit(60).forEach(p -> sb.append("  · ").append(p.packId()).append('\n'));

        final var server = mc.getSingleplayerServer();
        if (server != null) {
            final var dp = server.getPackRepository().getSelectedIds();
            sb.append("\n数据包（服务端已选）：\n");
            dp.forEach(id -> sb.append("  · ").append(id).append('\n'));
        } else {
            sb.append("\n数据包：多人服务器（不在本进程内）\n");
        }
    }

    /** 世界状态 */
    private static void world(StringBuilder sb, Minecraft mc) {
        if (mc.level == null) {
            sb.append("（无世界）\n");
            return;
        }
        final Level level = mc.level;
        sb.append("维度：").append(level.dimension().location()).append('\n');
        sb.append("游戏时间：").append(level.getGameTime())
                .append("（day ").append(level.getGameTime() / 24000L).append("）\n");
        sb.append("白天：").append(level.isDay()).append("  下雨：").append(level.isRaining())
                .append("  雷暴：").append(level.isThundering()).append('\n');
        sb.append("难度：").append(level.getDifficulty()).append('\n');
        if (mc.player != null) {
            final var pos = mc.player.blockPosition();
            sb.append("玩家所在生物群系：")
                    .append(level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("?"))
                    .append('\n');
            sb.append("玩家所在方块：").append(level.getBlockState(pos)).append('\n');
            sb.append("光照：").append(level.getMaxLocalRawBrightness(pos)).append('\n');
        }
    }

    /** 玩家状态 */
    private static void player(StringBuilder sb, Minecraft mc) {
        if (mc.player == null) {
            sb.append("（无玩家）\n");
            return;
        }
        final var p = mc.player;
        sb.append("名称：").append(p.getName().getString()).append('\n');
        sb.append("位置：").append(fmt(p.getX())).append(", ").append(fmt(p.getY())).append(", ").append(fmt(p.getZ()))
                .append("  维度 ").append(p.level().dimension().location()).append('\n');
        sb.append("生命：").append(fmt(p.getHealth())).append('/').append(fmt(p.getMaxHealth()))
                .append("  饥饿：").append(p.getFoodData().getFoodLevel())
                .append("  经验等级：").append(p.experienceLevel).append('\n');
        sb.append("游戏模式：").append(p.isCreative() ? "创造" : p.isSpectator() ? "旁观" : "生存/冒险").append('\n');
        sb.append("主手：").append(p.getMainHandItem()).append('\n');
        sb.append("背包（非空）：\n");
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            final var stack = p.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                sb.append("  [").append(i).append("] ").append(stack).append('\n');
            }
        }
    }

    /** 附近实体 */
    private static void entities(StringBuilder sb, Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            sb.append("（无世界/玩家）\n");
            return;
        }
        final var around = mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(64.0));
        sb.append("64 格内实体：").append(around.size()).append(" 个\n");
        around.stream().limit(80).forEach(e -> sb.append("  · ")
                .append(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))
                .append("  ").append(fmt(e.getX())).append(", ").append(fmt(e.getY())).append(", ").append(fmt(e.getZ()))
                .append(e instanceof net.minecraft.world.entity.LivingEntity le
                        ? "  hp=" + fmt(le.getHealth()) : "")
                .append('\n'));
    }

    // ==================== 工具 ====================

    private static RegistryAccess registryAccess(Minecraft mc) {
        try {
            if (mc.getConnection() != null) {
                return mc.getConnection().registryAccess();
            }
        } catch (Throwable ignored) {
        }
        try {
            final var server = mc.getSingleplayerServer();
            if (server != null) {
                return server.registryAccess();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
