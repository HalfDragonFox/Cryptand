package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.hdf.cryptand.neoforge.aiauto.mc.McBuild;
import com.hdf.cryptand.neoforge.aiauto.mc.WorldOps;
import net.minecraft.world.item.ItemStack;

/** 工具分类：**玩家与世界操作**（物品/移动/时间/天气/模式/实体/潜行状态）。 */
public final class AiPlayerTools {

    private AiPlayerTools() {
    }

    public static void registerAll() {
        AiToolServer.register("give_item", "给自己物品",
                AiToolServer.schema("item", "string", "count", "int（默认 1）"),
                args -> {
                    final String item = McBuild.normalize(AiToolServer.str(args, "item", "stone"));
                    final int n = AiToolServer.num(args, "count", 1);
                    return AiToolServer.ok(AiToolServer.command("give @s " + item + " " + n)
                            ? "已给予 " + n + " 个 " + item : "ERR");
                });

        AiToolServer.register("teleport", "传送自己",
                AiToolServer.schema("x", "double/string", "y", "double/string", "z", "double/string"),
                args -> AiToolServer.ok(AiToolServer.command("tp @s "
                        + AiToolServer.str(args, "x", "~") + " " + AiToolServer.str(args, "y", "~")
                        + " " + AiToolServer.str(args, "z", "~")) ? "已传送" : "ERR"));

        AiToolServer.register("set_gamemode", "设置游戏模式",
                AiToolServer.schema("mode", "creative|survival|spectator|adventure"),
                args -> AiToolServer.ok(AiToolServer.command(
                        "gamemode " + AiToolServer.str(args, "mode", "creative")) ? "已切换" : "ERR"));

        AiToolServer.register("set_time", "设置时间",
                AiToolServer.schema("value", "day|night|noon|midnight|数字"),
                args -> AiToolServer.ok(AiToolServer.command(
                        "time set " + AiToolServer.str(args, "value", "day")) ? "已设置" : "ERR"));

        AiToolServer.register("set_weather", "设置天气",
                AiToolServer.schema("value", "clear|rain|thunder"),
                args -> AiToolServer.ok(AiToolServer.command(
                        "weather " + AiToolServer.str(args, "value", "clear")) ? "已设置" : "ERR"));

        AiToolServer.register("summon", "生成实体",
                AiToolServer.schema("entity", "string", "x", "double/string", "y", "double/string",
                        "z", "double/string"),
                args -> {
                    final String cmd = "summon " + McBuild.normalize(AiToolServer.str(args, "entity", "pig"))
                            + " " + AiToolServer.str(args, "x", "~") + " " + AiToolServer.str(args, "y", "~")
                            + " " + AiToolServer.str(args, "z", "~");
                    return AiToolServer.ok(AiToolServer.command(cmd) ? "已生成" : "ERR");
                });

        AiToolServer.register("kill_entities", "清除附近实体（默认保留玩家）",
                AiToolServer.schema("radius", "double（默认 64）", "type", "string（可空，指定则只清该类）"),
                args -> {
                    final double r = AiToolServer.dbl(args, "radius", 64);
                    final String type = AiToolServer.str(args, "type", "");
                    final String selector = type.isBlank()
                            ? "@e[type=!minecraft:player,distance=.." + (long) r + "]"
                            : "@e[type=" + McBuild.normalize(type) + ",distance=.." + (long) r + "]";
                    return AiToolServer.ok(AiToolServer.command("kill " + selector) ? "已清除" : "ERR");
                });

        AiToolServer.register("clear_effects", "清除自身药水效果",
                AiToolServer.schema(), args -> AiToolServer.ok(
                        AiToolServer.command("effect clear @s") ? "已清除" : "ERR"));

        AiToolServer.register("sneak_set",
                "设置玩家潜行（蹲下）开关 —— 很多方块是「**潜行右键**」才有特殊行为"
                        + "（例：OC 电脑/机箱 = 潜行右键开机，普通右键开界面），"
                        + "配合 screen_open 即可无人化触发这类交互",
                AiToolServer.schema("on", "bool（默认 true=开始潜行）"),
                args -> {
                    final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player == null) {
                        return AiToolServer.ok("ERR 需要玩家（在客户端世界内）");
                    }
                    final boolean on = AiToolServer.bool(args, "on", true);
                    final java.util.UUID id = mc.player.getUUID();
                    // ① 客户端一侧：按键状态 + 本地玩家标志。**两边必须一起设** ——
                    //    客户端每 tick 用 input.shiftKeyDown（= keyShift.isDown()）刷新本地标志，
                    //    而这个布尔又是 ServerboundPlayerInputPacket 里 shift 位的来源
                    //    （javap：LocalPlayer 构造 ServerboundPlayerInputPacket(..., shift)），
                    //    所以只设其中一个，下一个输入包就会把另一个方向"刷回去"。
                    mc.execute(() -> {
                        mc.player.setShiftKeyDown(on);
                        mc.options.keyShift.setDown(on);
                    });
                    // ② 服务端一侧（★ 2026-09-27 补）：潜行右键这类交互判定读的是**服务端**的
                    //    Entity#setShiftKeyDown（= shared flag bit 1，也就是 isCrouching）。
                    //    服务端那个位平时由 handlePlayerInput → ServerPlayer.setPlayerInput(..., shift)
                    //    从客户端输入包写入（javap 实证）⇒ 只设客户端就要等一个包往返，工具返回后
                    //    立刻交互会读到旧值（上一项实测"下一 tick 被刷回"就是这里）。
                    //    直接写服务端 = 让权威位当场就位，客户端稍后发来的同一个值也不会打架。
                    final boolean server = WorldOps.query(level -> {
                        final net.minecraft.server.level.ServerPlayer sp =
                                level.getServer().getPlayerList().getPlayer(id);
                        if (sp == null) {
                            return false;
                        }
                        sp.setShiftKeyDown(on);
                        return true;
                    }, false);
                    return AiToolServer.ok("潜行=" + on
                            + (server ? "（客户端 + 服务端权威位都已设置）"
                                    : "（只设了客户端：服务端没有这个玩家）")
                            + "；交互前请再等 1 tick（服务端 tick 落地）");
                });

        AiToolServer.register("drop_item", "丢弃手持物品的指定数量（背包整理用）",
                AiToolServer.schema("count", "int（默认 1）"),
                args -> AiToolServer.ok(AiToolServer.command(
                        "clear @s " + AiToolServer.str(args, "item", "").trim()
                                + " " + AiToolServer.num(args, "count", 1) + " 0")
                        ? "已清除" : "ERR (item 必填)"));
    }
}
