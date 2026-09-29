package com.hdf.cryptand.neoforge.aiauto.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ===== 建造指令集（aiauto mc 目标的"动手"部分）=====
 *
 * <p>给 AI 一个能"按名称放方块、按尺寸盖房子"的最小工具集。所有命令最终都转成<b>原版命令</b>
 * 走玩家命令通道发送（不绕过权限与服务端校验）。</p>
 *
 * <h3>名称友好</h3>
 * <p>`stone` / `oak_planks` / `glass` 这类<b>简名</b>会先查注册表补成 `minecraft:stone`；
 * 认不出来就原样传下去（自定义命名空间必须写全 `ns:name`）。</p>
 *
 * <h3>坐标</h3>
 * <p>一律用原版相对坐标 `~`（相对玩家），所以脚本可以复用、不绑定绝对位置。</p>
 *
 * <h3>指令</h3>
 * <pre>
 *   set  &lt;x y z&gt; &lt;方块&gt;                单点放置（等价 setblock）
 *   fill &lt;x1 y1 z1 x2 y2 z2&gt; &lt;方块&gt;    区域填充
 *   hollow &lt;宽&gt; &lt;高&gt; &lt;深&gt; &lt;方块&gt;        空心盒子（先实心再掏空）
 *   house &lt;宽&gt; &lt;深&gt; [高] [材质]          ★ 一栋简易房子：清场 → 地板 → 四壁 → 屋顶 → 门洞
 *   anchor ±&lt;x&gt; ±&lt;y&gt; ±&lt;z&gt;               把后续建造整体平移（记在本地，作为前缀拼接）
 * </pre>
 */
public final class McBuild {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private McBuild() {
    }

    /** 名称归一化：无命名空间的裸名先查方块表，再查物品表，命中则补 minecraft: */
    public static String normalize(String token) {
        if (token == null || token.isEmpty() || token.contains(":")) {
            return token;
        }
        final String lower = token.toLowerCase(Locale.ROOT);
        try {
            if (BuiltInRegistries.BLOCK.containsKey(ResourceLocation.withDefaultNamespace(lower))
                    || BuiltInRegistries.ITEM.containsKey(ResourceLocation.withDefaultNamespace(lower))) {
                return "minecraft:" + lower;
            }
        } catch (Throwable ignored) {
        }
        return token;
    }

    /** 把一整行参数里的裸名都归一化（坐标、选择器、数量保持原样） */
    public static List<String> normalizeAll(List<String> args) {
        final List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            // 只处理"看起来像名字"的 token：不含 ~ ^ @ 数字开头、不是纯数字
            if (a.isEmpty() || a.startsWith("~") || a.startsWith("^") || a.startsWith("@")
                    || Character.isDigit(a.charAt(0))) {
                out.add(a);
                continue;
            }
            out.add(normalize(a));
        }
        return out;
    }

    // ==================== 建造 ====================

    /**
     * 执行一个建造动作；返回 null 表示"不是建造指令"（交回通用 act 处理）。
     */
    public static String build(String action, List<String> args) {
        try {
            return switch (action.toLowerCase(Locale.ROOT)) {
                case "set" -> {
                    if (args.size() < 4) {
                        yield "ERR 用法：set <x y z> <方块>";
                    }
                    send("setblock " + args.get(0) + " " + args.get(1) + " " + args.get(2)
                            + " " + normalize(args.get(3)));
                    yield "OK setblock " + normalize(args.get(3));
                }
                case "fill", "fillx" -> {
                    if (args.size() < 7) {
                        yield "ERR 用法：fill <x1 y1 z1 x2 y2 z2> <方块>";
                    }
                    final String block = normalize(args.get(6));
                    send("fill " + String.join(" ", args.subList(0, 6)) + " " + block);
                    yield "OK fill " + block;
                }
                case "hollow" -> hollow(args);
                case "house" -> house(args);
                default -> null;
            };
        } catch (Throwable ex) {
            return "ERR " + ex;
        }
    }

    /** 空心盒子：先实心，再把内部掏空 */
    private static String hollow(List<String> args) {
        if (args.size() < 4) {
            return "ERR 用法：hollow <宽> <高> <深> <方块>";
        }
        final int w = Integer.parseInt(args.get(0));
        final int h = Integer.parseInt(args.get(1));
        final int d = Integer.parseInt(args.get(2));
        final String block = normalize(args.get(3));
        // 实心
        send("fill ~1 ~ ~1 ~" + w + " ~" + (h - 1) + " ~" + d + " " + block);
        // 掏空（留 1 格壁厚）
        if (w > 2 && d > 2 && h > 2) {
            send("fill ~2 ~1 ~2 ~" + (w - 1) + " ~" + (h - 2) + " ~" + (d - 1) + " air");
        }
        return "OK hollow " + w + "x" + h + "x" + d + " " + block;
    }

    /**
     * 一栋简易房子（相对玩家脚下）：清场 → 地板 → 四壁 → 屋顶 → 门洞。
     *
     * <p>用法：`house 9 7`（宽 9、深 7，高默认 4，材质默认 oak_planks），
     * 也可 `house 9 7 5 stone`。</p>
     */
    private static String house(List<String> args) {
        if (args.size() < 2) {
            return "ERR 用法：house <宽> <深> [高] [材质]";
        }
        final int w = Integer.parseInt(args.get(0));
        final int d = Integer.parseInt(args.get(1));
        final int h = args.size() > 2 ? Integer.parseInt(args.get(2)) : 4;
        final String mat = normalize(args.size() > 3 ? args.get(3) : "oak_planks");
        final String floor = normalize("stone");
        final int x2 = w, z2 = d, yTop = h - 1;

        final List<String> cmds = new ArrayList<>();
        // 1) 清出空间（含一格余量）
        cmds.add("fill ~1 ~-1 ~1 ~" + x2 + " ~" + (yTop + 1) + " ~" + z2 + " air");
        // 2) 地板
        cmds.add("fill ~1 ~-1 ~1 ~" + x2 + " ~-1 ~" + z2 + " " + floor);
        // 3) 四壁
        cmds.add("fill ~1 ~ ~1 ~" + x2 + " ~" + yTop + " ~1 " + mat);        // 北墙
        cmds.add("fill ~1 ~ ~" + z2 + " ~" + x2 + " ~" + yTop + " ~" + z2 + " " + mat);  // 南墙
        cmds.add("fill ~1 ~ ~2 ~1 ~" + yTop + " ~" + (z2 - 1) + " " + mat);  // 西墙
        cmds.add("fill ~" + x2 + " ~ ~2 ~" + x2 + " ~" + yTop + " ~" + (z2 - 1) + " " + mat); // 东墙
        // 4) 屋顶
        cmds.add("fill ~1 ~" + h + " ~1 ~" + x2 + " ~" + h + " ~" + z2 + " " + mat);
        // 5) 门洞（北墙正中，2 格高）
        final int doorX = Math.max(1, w / 2);
        cmds.add("fill ~" + doorX + " ~ ~1 ~" + doorX + " ~1 ~1 air");
        // 6) 窗（两侧各一个）
        if (w >= 5 && d >= 5) {
            cmds.add("setblock ~1 ~2 ~" + Math.max(2, d / 2) + " " + normalize("glass"));
            cmds.add("setblock ~" + x2 + " ~2 ~" + Math.max(2, d / 2) + " " + normalize("glass"));
        }

        cmds.forEach(McBuild::send);
        return "OK 已建造 " + w + "x" + d + "x" + h + " 的房子（" + cmds.size() + " 条命令，材质 " + mat + "）";
    }

    /** 走玩家命令通道（与手敲一致） */
    public static void send(String command) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("[aiauto/build] 无玩家，丢弃命令 {}", command);
            return;
        }
        mc.player.connection.sendCommand(command);
        AiConsoleBridge.log(command);
    }

    /** 记一行日志（延迟解析，避免静态初始化顺序问题） */
    private static final class AiConsoleBridge {
        static void log(String command) {
            try {
                com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.log("    · " + command);
            } catch (Throwable ignored) {
            }
        }
    }
}
