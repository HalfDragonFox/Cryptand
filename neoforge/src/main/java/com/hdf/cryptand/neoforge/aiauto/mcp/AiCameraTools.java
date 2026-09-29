package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.mc.WorldOps;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * 工具分类：**取景与假玩家**（{@code ui} 类）。
 *
 * <h3>取景方案（2026-09-15 定稿）</h3>
 * <p><b>截图 = tp 玩家 + 直接截图</b>：{@code camera_set} 用服务端权威传送把玩家送到取景位
 * （临时关重力），中间插一个 {@code {"wait":{"ms":600}}} 等 1~2 帧，再 {@code screenshot}，
 * 最后 {@code camera_reset} 原位送回。<b>影响最小，不需要任何渲染 mixin。</b></p>
 *
 * <p>曾长期尝试"完全不依赖真人玩家的离屏 / 多视角渲染"（客户端假人相机 → 服务端假玩家 →
 * 借用 MC 渲染管线 → FBO / blit 备份 …）。**唯一成功的一次**是 ch1；
 * 全套方案、走过的 12 条路径、确凿的 API 事实与"若未来继续"的下一步
 * 已存档于 {@code viking://resources/cryptand/aiauto/offscreen-render-archive-2026-09-15.md}，
 * 并从代码中删除。**AI 看清建筑的主力改为 {@code scan_region} 导出文件**（纯数据、精确、
 * 不受渲染管线影响），截图只作辅助、允许主视角轻微闪屏。</p>
 *
 * <h3>假玩家</h3>
 * <p>{@code dummy_join} 让一个假玩家真实登录服务端（Tab 可见、聊天栏提示；可当人形参照，
 * 也可作为"区块由谁加载"的锚点）。它曾经也被用来当相机载体，
 * 但实测{@code setCameraEntity}在非旁观模式下会被 {@code Minecraft.tick()} 每 tick 重置，
 * 画面始终是玩家视角，所以 {@code camera_dummy} 已移除。</p>
 */
public final class AiCameraTools {

    /** 取景前玩家原位姿（第一次取景时记下，camera_reset 还回去） */
    private static boolean playerSaved;
    private static double savedX;
    private static double savedY;
    private static double savedZ;
    private static float savedYaw;
    private static float savedPitch;

    private AiCameraTools() {
    }

    public static void registerAll() {
        AiToolServer.register("camera_set",
                "把玩家送到指定位置/朝向取景（服务端传送；AI 自主取景，不依赖玩家在哪）",
                AiToolServer.schema("x", "double/string（支持 ~ 相对玩家）",
                        "y", "double/string（支持 ~）",
                        "z", "double/string（支持 ~）",
                        "yaw", "double（0=+z南，90=-x西，180=-z北，-90=+x东）",
                        "pitch", "double（正数向下看，-90..90）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) {
                        o.addProperty("error", "无玩家");
                        return o;
                    }
                    final double x = coord(AiToolServer.str(args, "x", "~"), mc.player.getX());
                    final double y = coord(AiToolServer.str(args, "y", "~"), mc.player.getY());
                    final double z = coord(AiToolServer.str(args, "z", "~"), mc.player.getZ());
                    final float yaw = (float) AiToolServer.dbl(args, "yaw", mc.player.getYRot());
                    final float pitch = (float) AiToolServer.dbl(args, "pitch", mc.player.getXRot());
                    final UUID uuid = mc.player.getUUID();
                    final boolean ok = WorldOps.query(level -> {
                        final var sp = level.getServer().getPlayerList().getPlayer(uuid);
                        if (sp == null) {
                            return false;
                        }
                        if (!playerSaved) {
                            savedX = sp.getX();
                            savedY = sp.getY();
                            savedZ = sp.getZ();
                            savedYaw = sp.getYRot();
                            savedPitch = sp.getXRot();
                            playerSaved = true;
                        }
                        sp.setNoGravity(true);                       // 悬停在取景位，不下落
                        sp.teleportTo(level, x, y, z, yaw, pitch);   // 服务端权威传送
                        return true;
                    }, false);
                    if (!ok) {
                        o.addProperty("error", "需要单人游戏内置服务端（多人环境请用 run_command tp）");
                        return o;
                    }
                    o.addProperty("camera", String.format("%.1f, %.1f, %.1f", x, y, z));
                    o.addProperty("yaw", yaw);
                    o.addProperty("pitch", pitch);
                    o.addProperty("hint", "接 {\"wait\":{\"ms\":600}} 再 screenshot，最后 camera_reset 送回");
                    return o;
                });

        AiToolServer.register("camera_reset",
                "取景结束：把玩家原位送回并恢复重力",
                AiToolServer.schema(),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) {
                        o.addProperty("error", "无玩家");
                        return o;
                    }
                    final UUID uuid = mc.player.getUUID();
                    final boolean ok = WorldOps.query(level -> {
                        final var sp = level.getServer().getPlayerList().getPlayer(uuid);
                        if (sp == null) {
                            return false;
                        }
                        sp.setNoGravity(false);
                        if (playerSaved) {
                            sp.teleportTo(level, savedX, savedY, savedZ, savedYaw, savedPitch);
                        }
                        return true;
                    }, false);
                    playerSaved = false;
                    o.addProperty("ok", ok);
                    o.addProperty("message", ok ? "已送回原位并恢复重力" : "恢复失败（没有内置服务端）");
                    return o;
                });

        AiToolServer.register("camera_info", "查当前取景状态（玩家在哪、有没有存过原位）",
                AiToolServer.schema(),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) {
                        o.addProperty("error", "无玩家");
                        return o;
                    }
                    o.addProperty("player", String.format("%.1f, %.1f, %.1f yaw=%.1f pitch=%.1f",
                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            mc.player.getYRot(), mc.player.getXRot()));
                    o.addProperty("savedOrigin", playerSaved
                            ? String.format("%.1f, %.1f, %.1f", savedX, savedY, savedZ)
                            : "（未取景）");
                    return o;
                });

        // ===== 假玩家（真实登录服务端；Tab 可见）=====
        AiToolServer.register("dummy_join",
                "让一个假玩家真实登录服务器（Tab 可见），可当人形参照 / 区块加载锚点",
                AiToolServer.schema("name", "string（默认 AI_Dummy）",
                        "x", "double/string（支持 ~）", "y", "double/string（支持 ~）",
                        "z", "double/string（支持 ~）",
                        "yaw", "double（朝向）", "pitch", "double（俯仰）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    final String name = AiToolServer.str(args, "name", "AI_Dummy");
                    final double bx = mc.player != null ? mc.player.getX() : 0;
                    final double by = mc.player != null ? mc.player.getY() : 64;
                    final double bz = mc.player != null ? mc.player.getZ() : 0;
                    final double x = coord(AiToolServer.str(args, "x", "~"), bx);
                    final double y = coord(AiToolServer.str(args, "y", "~"), by);
                    final double z = coord(AiToolServer.str(args, "z", "~"), bz);
                    final float yaw = (float) AiToolServer.dbl(args, "yaw", 0);
                    final float pitch = (float) AiToolServer.dbl(args, "pitch", 0);
                    final String[] err = {null};
                    final boolean ok = WorldOps.query(level -> {
                        try {
                            final var server = level.getServer();
                            final var old = server.getPlayerList().getPlayerByName(name);
                            if (old != null) {
                                server.getPlayerList().remove(old);
                            }
                            final com.mojang.authlib.GameProfile profile =
                                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
                            // 内存连接：出站包全丢（配套 SilentPacketListener，见 FakePlayerPlayerListMixin）
                            final net.minecraft.network.Connection conn =
                                    new com.hdf.cryptand.neoforge.aiauto.mc.DummyConnection();
                            final var sp = new net.minecraft.server.level.ServerPlayer(server, level, profile,
                                    net.minecraft.server.level.ClientInformation.createDefault());
                            server.getPlayerList().placeNewPlayer(conn, sp,
                                    new net.minecraft.server.network.CommonListenerCookie(
                                            profile, 0,
                                            net.minecraft.server.level.ClientInformation.createDefault(),
                                            false,
                                            net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE));
                            sp.setNoGravity(true);
                            sp.teleportTo(level, x, y, z, yaw, pitch);
                            server.getCommands().performPrefixedCommand(
                                    sp.createCommandSourceStack(), "gamemode creative");
                            return true;
                        } catch (Throwable ex) {
                            err[0] = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                            return level.getServer().getPlayerList().getPlayerByName(name) != null;
                        }
                    }, false);
                    if (!ok) {
                        o.addProperty("error", err[0] != null ? ("假玩家登录失败 → " + err[0])
                                : "没有可用的内置服务端");
                        return o;
                    }
                    o.addProperty("name", name);
                    o.addProperty("spot", String.format("%.1f, %.1f, %.1f", x, y, z));
                    return o;
                });

        AiToolServer.register("dummy_move", "移动已登录的假玩家",
                AiToolServer.schema("name", "string（默认 AI_Dummy）",
                        "x", "double/string（支持 ~）", "y", "double/string（支持 ~）",
                        "z", "double/string（支持 ~）",
                        "yaw", "double", "pitch", "double"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    final String name = AiToolServer.str(args, "name", "AI_Dummy");
                    final double bx = mc.player != null ? mc.player.getX() : 0;
                    final double by = mc.player != null ? mc.player.getY() : 64;
                    final double bz = mc.player != null ? mc.player.getZ() : 0;
                    final double x = coord(AiToolServer.str(args, "x", "~"), bx);
                    final double y = coord(AiToolServer.str(args, "y", "~"), by);
                    final double z = coord(AiToolServer.str(args, "z", "~"), bz);
                    final float yaw = (float) AiToolServer.dbl(args, "yaw", 0);
                    final float pitch = (float) AiToolServer.dbl(args, "pitch", 0);
                    final boolean ok = WorldOps.query(level -> {
                        final var sp = level.getServer().getPlayerList().getPlayerByName(name);
                        if (sp == null) {
                            return false;
                        }
                        sp.setNoGravity(true);
                        sp.teleportTo(level, x, y, z, yaw, pitch);
                        return true;
                    }, false);
                    o.addProperty("ok", ok);
                    o.addProperty("message", ok ? "已移动 " + name : "ERR 找不到假玩家 " + name);
                    return o;
                });

        AiToolServer.register("dummy_leave", "让假玩家下线",
                AiToolServer.schema("name", "string（默认 AI_Dummy）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final String name = AiToolServer.str(args, "name", "AI_Dummy");
                    final boolean ok = WorldOps.query(level -> {
                        final var sp = level.getServer().getPlayerList().getPlayerByName(name);
                        if (sp == null) {
                            return false;
                        }
                        level.getServer().getPlayerList().remove(sp);
                        return true;
                    }, false);
                    o.addProperty("ok", ok);
                    o.addProperty("message", ok ? "已下线 " + name : "ERR 没有这个假玩家");
                    return o;
                });
    }

    /** 坐标：支持 {@code ~}/{@code ~n} 相对玩家，或绝对数值 */
    private static double coord(String token, double origin) {
        final String t = token == null ? "~" : token.trim();
        if (t.startsWith("~")) {
            final String rest = t.substring(1);
            return origin + (rest.isEmpty() ? 0 : Double.parseDouble(rest));
        }
        return Double.parseDouble(t);
    }
}
