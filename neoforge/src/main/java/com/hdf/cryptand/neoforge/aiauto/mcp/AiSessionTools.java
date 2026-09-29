package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.AiSession;
import net.minecraft.client.Minecraft;

/** 工具分类：**会话与诊断**（状态信号 / 队列 / 聊天 / 日志）。 */
public final class AiSessionTools {

    private AiSessionTools() {
    }

    public static void registerAll() {
        AiToolServer.register("status", "刷新并返回会话状态（阶段/世界/玩家/屏幕/队列/工具数）",
                AiToolServer.schema(), args -> {
                    AiSession.event("status.query", "工具调用");
                    final JsonObject o = new JsonObject();
                    o.addProperty("text", AiSession.statusText());
                    return o;
                });

        AiToolServer.register("queue_status", "查批处理队列进度（待执行条数 / 正在等待的条件）",
                AiToolServer.schema(), args -> AiToolServer.queueStatus());

        AiToolServer.register("progress",
                "阶段播报：往聊天栏发一条进度（本地显示，不产生服务器反馈）——"
                        + "只看整段流程的里程碑，别每个动作都发，避免刷屏",
                AiToolServer.schema("text", "string"), "player", args -> {
                    final Minecraft mc = Minecraft.getInstance();
                    final String text = AiToolServer.str(args, "text", "");
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("[AI] " + text), false);
                    }
                    return AiToolServer.ok(text);
                });

        AiToolServer.register("chat", "往玩家聊天栏发消息（同 progress；让人类看到 AI 在做什么）",
                AiToolServer.schema("text", "string"),
                args -> {
                    final Minecraft mc = Minecraft.getInstance();
                    final String text = AiToolServer.str(args, "text", "");
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("[AI] " + text), false);
                    }
                    return AiToolServer.ok(text);
                });

        AiToolServer.register("log_tail", "读 console.log 尾部",
                AiToolServer.schema("lines", "int（默认 20）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    try {
                        final var log = AiAutomation.outDir().resolve("console.log");
                        if (!java.nio.file.Files.exists(log)) {
                            o.addProperty("text", "（暂无日志）");
                            return o;
                        }
                        final var all = java.nio.file.Files.readAllLines(log);
                        final int n = Math.max(1, AiToolServer.num(args, "lines", 20));
                        o.addProperty("text", String.join("\n",
                                all.subList(Math.max(0, all.size() - n), all.size())));
                    } catch (Throwable ex) {
                        o.addProperty("error", String.valueOf(ex));
                    }
                    return o;
                });
    }
}
