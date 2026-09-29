package com.hdf.cryptand.neoforge.aiauto;

import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ===== AI 会话：状态信号 + 事件流 =====
 *
 * <p><b>唯一对外协议是工具调用</b>（见 {@link com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer}）：
 * 写 {@code calls/<id>.json}，读 {@code results/<id>.json}。本类只提供两样东西：</p>
 * <ul>
 *   <li><b>状态信号</b>：{@code status.txt} —— 阶段/世界/玩家/屏幕/队列（每 0.5s 刷新，AI 随时读）；</li>
 *   <li><b>事件流</b>：{@code events.log} —— 生命周期事件（启动 → 客户端就绪 → 世界加载 → 进入世界 →
 *       屏幕变化 → 工具调用），AI 用它判断"现在到哪一步、能不能动手"。</li>
 * </ul>
 *
 * <p>阶段语义：BOOTING → CLIENT_READY → WORLD_LOADING → <b>WORLD_READY</b>（可以操作）→ DISCONNECTED。</p>
 */
public final class AiSession {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 生命周期阶段（AI 用它决定"现在能不能操作"） */
    public enum Phase {
        BOOTING("启动中"),
        CLIENT_READY("客户端就绪（尚未进入世界）"),
        WORLD_LOADING("世界加载中"),
        WORLD_READY("已进入世界"),
        DISCONNECTED("已退出世界");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static volatile Phase phase = Phase.BOOTING;
    private static volatile String lastScreen = "（无）";
    private static long lastStatusWrite;
    private static long startedAt = System.currentTimeMillis();

    private AiSession() {
    }

    public static Path statusFile() {
        return AiAutomation.outDir().resolve("status.txt");
    }

    public static Path eventsFile() {
        return AiAutomation.outDir().resolve("events.log");
    }

    /** 当前阶段名（供等待条件 {"wait":{"phase":...}} 判断） */
    public static String phaseName() {
        return phase.name();
    }

    // ==================== 事件 ====================

    /** 追加一条生命周期事件 */
    public static void event(String name, String detail) {
        if (!AiAutomation.allowed()) {
            return;
        }
        try {
            Files.createDirectories(AiAutomation.outDir());
            Files.writeString(eventsFile(),
                    LocalDateTime.now().format(CLOCK) + "  " + name
                            + (detail == null || detail.isBlank() ? "" : "  |  " + detail)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(eventsFile())
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (Throwable ignored) {
        }
        LOGGER.info("[aiauto/event] {} {}", name, detail == null ? "" : detail);
    }

    public static void markBoot() {
        startedAt = System.currentTimeMillis();
        phase = Phase.BOOTING;
        event("client.boot", "aiauto 已装载");
    }

    public static void onLevelReady(String worldName) {
        phase = Phase.WORLD_READY;
        event("world.ready", worldName + "（可以开始操作）");
        writeStatus();
    }

    public static void onLogout() {
        phase = Phase.DISCONNECTED;
        event("world.left", "");
    }

    // ==================== 状态 ====================

    /** 每帧调用（内部节流 0.5s 写盘） */
    public static void tick() {
        if (!AiAutomation.allowed()) {
            return;
        }
        detectTransitions();
        final long now = System.currentTimeMillis();
        if (now - lastStatusWrite >= 500) {
            lastStatusWrite = now;
            writeStatus();
        }
    }

    /**
     * 阶段跃迁检测。
     *
     * <p>刻意不走 ScreenEvent / LevelEvent：这些事件类在 NeoForge 各版本间改名频繁
     * （ScreenOpenEvent → ScreenEvent.Opening …），轮询几个字段更稳。</p>
     */
    private static void detectTransitions() {
        final Minecraft mc = Minecraft.getInstance();
        final boolean inWorld = mc.level != null && mc.player != null;

        if (inWorld && phase != Phase.WORLD_READY) {
            onLevelReady(mc.level.dimension().location().toString());
        } else if (!inWorld && phase == Phase.WORLD_READY) {
            onLogout();
        }
        if (!inWorld && phase == Phase.BOOTING) {
            phase = Phase.CLIENT_READY;
            event("client.ready", "客户端已就绪（未进入世界）");
        }

        final String screen = mc.screen == null ? "（无）" : mc.screen.getClass().getSimpleName();
        if (!screen.equals(lastScreen)) {
            lastScreen = screen;
            event("screen.change", screen);
        }
    }

    /** 状态文本（= status.txt 内容） */
    public static String statusText() {
        final Minecraft mc = Minecraft.getInstance();
        final StringBuilder sb = new StringBuilder(512);
        sb.append("phase=").append(phase.name()).append("（").append(phase.label()).append("）\n");
        sb.append("uptimeMs=").append(System.currentTimeMillis() - startedAt).append('\n');
        sb.append("client=").append(net.minecraft.SharedConstants.getCurrentVersion().getName()).append('\n');
        sb.append("inWorld=").append(mc.level != null && mc.player != null).append('\n');
        sb.append("player=").append(mc.player == null ? "（无）" : mc.player.getName().getString()).append('\n');
        sb.append("screen=").append(mc.screen == null ? "（无）" : mc.screen.getClass().getSimpleName()).append('\n');
        sb.append("queue=").append(com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.queueStatus()).append('\n');
        sb.append("targets=").append(AiAutomation.targetIds()).append('\n');
        for (AiTarget t : AiAutomation.targets()) {
            sb.append("  · ").append(t.id()).append(" : ").append(t.items().size()).append(" 个对象\n");
        }
        sb.append("tools=").append(com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.toolNames().size())
                .append(" 个（清单见 tools.json）\n");
        return sb.toString();
    }

    private static void writeStatus() {
        try {
            Files.createDirectories(AiAutomation.outDir());
            Files.writeString(statusFile(), statusText(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 供工具调用的几个原子动作 ====================

    /**
     * 执行一个"会话级"动作（供工具层直接调用）：
     * {@code prep}（干净工作台）与 {@code status}（刷新状态）。
     */
    public static String execute(String line) {
        final String cmd = line == null ? "" : line.trim();
        try {
            return switch (cmd) {
                case "status" -> {
                    writeStatus();
                    yield "OK（" + statusFile() + "）";
                }
                case "prep" -> prepWorkbench();
                default -> "ERR 未知动作 " + cmd;
            };
        } catch (Throwable ex) {
            return "ERR " + ex;
        }
    }

    /** 干净工作台：让 AI 从一个确定状态开始 */
    private static String prepWorkbench() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "ERR 尚未进入世界";
        }
        final java.util.List<String> commands = java.util.List.of(
                "gamemode creative",
                "time set day",
                "weather clear",
                "kill @e[type=!minecraft:player,distance=..64]",
                "effect clear @s");
        commands.forEach(c -> mc.player.connection.sendCommand(c));
        event("session.prep", String.join(" ; ", commands));
        return "OK（干净工作台：" + commands.size() + " 条命令）";
    }
}
