/**
 * ===== CryptandSable 子包子命令（/cryptand csable）=====
 *
 * ★ 2026-09-06 【子包自治·指令】本子包（cryptandsable）自己的命令树，经 core 注册器
 * {@link com.hdf.cryptand.neoforge.core.registry.CryptandRegistries#registerSubcommand}
 * 挂到 /cryptand 根（/cryptand csable）。core 不持有任何子包指令实现——只提供注册机制。
 *
 * <p>命名约定（用户 2026-09-06）：/cryptand csable = CryptandSable 核心；
 * /cryptand sable = 官方 sable 联动（预留，子包可自行注册）。
 *
 * <p>命令树：
 * <ul>
 *   <li>/cryptand csable list          → 列出全部物理化亚层</li>
 *   <li>/cryptand csable info [uuid]   → 导出诊断信息 txt</li>
 *   <li>/cryptand csable removeall     → 清空全部亚层</li>
 *   <li>/cryptand csable remove <uuid> → 按 uuid 清除</li>
 *   <li>/cryptand csable physics [on|off|status] → 开关物理计算</li>
 *   <li>/cryptand csable obj           → 导出全部物理空间 OBJ</li>
 * </ul>
 */
package com.hdf.cryptand.neoforge.cryptandsable;

import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandBounds3i;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevel;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevelContainer;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** CryptandSable 子包命令：/cryptand csable（经 core 注册器挂载）。 */
public final class CryptandSableCommands {

    private CryptandSableCommands() {
    }

    /** 子命令名（挂 /cryptand 根下）。 */
    public static final String NAME = "csable";

    /** 是否已处理（注册或确认关闭置位；防多次 Loading 重复注册/日志）。 */
    private static volatile boolean settled = false;

    /**
     * 注册到 core 注册器。
     * ★ 2026-09-06 三轮：【官方配置模式 + core 总闸】本方法只在 {@code ModConfigEvent}
     *   （NeoForge 官方 config 加载事件，cryptand 域 spec 已加载后）回调里调用：
     *   - enableCryptandSableCore=false → 完全不挂载 /cryptand csable（用户拍板：
     *     关闭核心 = 不加载 CryptandSable 子包相关内容）；
     *   - spec 尚未加载（构造期/其它域 Loading）→ 幂等等待下次事件。
     * 无手工 TOML 直读。
     */
    public static void register() {
        if (settled) return;
        try {
            if (!ConfigCryptandSable.SPEC.isLoaded()) {
                return; // cryptand-sable spec 尚未由 NeoForge 加载 → 等下一次 config 事件
            }
            settled = true;
            if (!ConfigCryptandSable
                    .ENABLE_CRYPTAND_SABLE_CORE.get()) {
                return; // ★ 完全关闭语义：不挂载且静默（无 [CryptandSable] 输出）
            }
            CryptandRegistries
                    .registerSubcommand(NAME, command());
        } catch (final Throwable ignored) {
        }
    }

    /** 构建 /cryptand csable 命令树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal(NAME)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(ctx -> sableList(ctx.getSource())))
                .then(Commands.literal("info")
                        .executes(ctx -> sableInfo(ctx.getSource(), null))
                        .then(Commands.argument("uuid",
                                com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> sableInfo(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "uuid")))))
                .then(Commands.literal("removeall")
                        .executes(ctx -> sableRemoveAll(ctx.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("uuid",
                                com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> sableRemove(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "uuid")))))
                // /cryptand csable physics → 开关物理计算（2026-09-03）
                //   physics           → 显示当前状态
                //   physics on        → 开启物理计算
                //   physics off       → 关闭物理计算（结构保持位姿，冻结）
                //   physics status    → 显示当前状态
                .then(Commands.literal("physics")
                        .executes(ctx -> sablePhysics(ctx.getSource(), "status"))
                        .then(Commands.literal("on")
                                .executes(ctx -> sablePhysics(ctx.getSource(), "on")))
                        .then(Commands.literal("off")
                                .executes(ctx -> sablePhysics(ctx.getSource(), "off")))
                        .then(Commands.literal("status")
                                .executes(ctx -> sablePhysics(ctx.getSource(), "status"))))
                // /cryptand csable obj → 导出全部物理空间 OBJ（每空间一个；可视化建模验证）
                .then(Commands.literal("obj")
                        .executes(ctx -> {
                            try {
                                final CryptandSable core = CryptandSable.instance();
                                if (core == null || !core.isStarted()
                                        || !core.isOfficialEngine()) {
                                    ctx.getSource().sendFailure(net.minecraft.network.chat
                                            .Component.literal("CryptandSable 未启动/非官方引擎"));
                                    return 0;
                                }
                                final java.io.File dir = new java.io.File(
                                        "obj_export").getAbsoluteFile();
                                core.officialEngine().exportAllSpaceObj(dir);
                                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat
                                        .Component.literal("OBJ 已导出到 " + dir.getAbsolutePath()),
                                        false);
                                return 1;
                            } catch (final Exception ex) {
                                ctx.getSource().sendFailure(net.minecraft.network.chat
                                        .Component.literal("OBJ 导出失败：" + ex));
                                return 0;
                            }
                        }));
    }

    /** /cryptand csable physics <on|off|status>：开关官方 Rapier 物理计算。 */
    private static int sablePhysics(final CommandSourceStack src, final String action) {
        try {
            final CryptandSable core = CryptandSable.instance();
            if (core == null || !core.isStarted()) {
                src.sendFailure(net.minecraft.network.chat.Component.literal(
                        "CryptandSable 未启动"));
                return 0;
            }
            final com.hdf.cryptand.neoforge.cryptandsable.core.backend.official
                    .OfficialRapierEngine eng = core.officialEngine();
            if (eng == null || !core.isOfficialEngine()) {
                src.sendFailure(net.minecraft.network.chat.Component.literal(
                        "当前未使用官方 Rapier 引擎（dynamic/fallback 模式物理开关不可用）"));
                return 0;
            }
            final boolean enable = switch (action) {
                case "on" -> true;
                case "off" -> false;
                default -> eng.isPhysicsStepEnabled();
            };
            if (action.equals("on") || action.equals("off")) {
                eng.setPhysicsStepEnabled(enable);
            }
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "物理计算状态: " + (eng.isPhysicsStepEnabled()
                            ? "ON（正常积分）" : "OFF（冻结，结构保持位姿）")), false);
            return 1;
        } catch (final Throwable t) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    "csable physics 失败: " + t));
            return 0;
        }
    }

    /** /cryptand csable info [uuid]：导出指定（或全部）物理化结构的完整诊断信息。 */
    private static int sableInfo(final CommandSourceStack src, final String uuidStr) {
        try {
            final net.minecraft.server.level.ServerLevel level = src.getLevel();
            java.util.UUID uuid = null;
            if (uuidStr != null && !uuidStr.isBlank()) {
                try {
                    uuid = java.util.UUID.fromString(uuidStr.trim());
                } catch (IllegalArgumentException e) {
                    src.sendFailure(net.minecraft.network.chat.Component.literal("非法 uuid：" + uuidStr));
                    return 0;
                }
            }
            final java.util.List<String> lines =
                    CryptandSubLevelApi
                            .dumpStructureInfo(level, uuid);
            final String namePrefix = "cryptand_sable_info_" + (uuidStr != null
                    ? (uuidStr.length() > 8 ? uuidStr.substring(0, 8) : uuidStr) + "_"
                    : "");
            final java.nio.file.Path out = java.nio.file.Paths.get(
                    namePrefix
                            + java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter
                                            .ofPattern("yyyyMMdd_HHmmss"))
                            + ".txt");
            java.nio.file.Files.write(out, lines, java.nio.charset.StandardCharsets.UTF_8);
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "物理化结构信息已导出: " + out.toAbsolutePath()
                            + "（共 " + lines.size() + " 行）"), false);
        } catch (Exception ex) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("csable info 导出失败：" + ex));
        }
        return 1;
    }

    /** /cryptand csable list：列出当前世界全部物理化亚层。 */
    private static int sableList(final CommandSourceStack src) {
        try {
            final net.minecraft.server.level.ServerLevel level = src.getLevel();
            final CryptandSubLevelContainer container =
                    CryptandSubLevelContainer
                            .getContainer(level);
            final int count = container.getSubLevelMap().size();
            for (final CryptandSubLevel s
                    : container.getSubLevelMap().values()) {
                if (s == null) continue;
                final CryptandBounds3i box =
                        s.getPlot() != null ? s.getPlot().getBoundingBox() : null;
                src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "  [" + s.getUniqueId() + "] runtimeId=" + s.getRuntimeId()
                                + " bounds=" + (box == null ? "?" :
                                box.minX() + "," + box.minY() + "," + box.minZ() + ".."
                                        + box.maxX() + "," + box.maxY() + "," + box.maxZ())), false);
            }
            final int finalCount = count;
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "找到 " + finalCount + " 个物理化亚层"), false);
        } catch (Exception ex) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("csable list 失败：" + ex));
        }
        return 1;
    }

    /** /cryptand csable removeall：清空当前世界全部亚层（专属 API）。 */
    private static int sableRemoveAll(final CommandSourceStack src) {
        try {
            final net.minecraft.server.level.ServerLevel level = src.getLevel();
            final int n = CryptandSubLevelApi
                    .removeAllSubLevels(level);
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "已清除 " + n + " 个物理化亚层"), false);
        } catch (Exception ex) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("csable removeall 失败：" + ex));
        }
        return 1;
    }

    /** /cryptand csable remove <uuid>：按 uuid 清除单个亚层。 */
    private static int sableRemove(final CommandSourceStack src, final String uuidStr) {
        try {
            final net.minecraft.server.level.ServerLevel level = src.getLevel();
            final java.util.UUID uuid = java.util.UUID.fromString(uuidStr.trim());
            final CryptandSubLevel sub =
                    CryptandSubLevelApi
                            .getSubLevel(level, uuid);
            if (sub == null) {
                src.sendFailure(net.minecraft.network.chat.Component.literal("未找到亚层 " + uuid));
                return 0;
            }
            final int restored =
                    CryptandSubLevelApi
                            .disassembleSubLevel(level, sub);
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "已拆卸亚层 " + uuid + "（还原方块 " + restored + "）"), false);
        } catch (Exception ex) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("csable remove 失败：" + ex));
        }
        return 1;
    }
}
