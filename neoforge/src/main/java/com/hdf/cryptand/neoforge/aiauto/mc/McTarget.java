package com.hdf.cryptand.neoforge.aiauto.mc;

import com.hdf.cryptand.neoforge.aiauto.AiTarget;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * ===== aiauto 的 mc 目标：运行时报告 + 游戏操作 =====
 *
 * <p><b>报告</b>（{@code dump <类别>} → {@code latest-mc-<类别>.txt}）：mods / registries / recipes /
 * tags / packs / world / player / entities —— 重点是<b>动态加载</b>的内容（数据包、资源包、
 * 数据包驱动的动态注册表、配方与标签）。</p>
 *
 * <p><b>操作</b>（{@code act <动作> [参数…]}）：走玩家命令通道
 * （{@code ClientPacketListener#sendCommand}），因此需要 OP；
 * 集成服务器单人世界默认拥有权限。动作是原版命令的受控映射：</p>
 * <pre>
 *   give &lt;物品&gt; [数量]      tp &lt;x&gt; &lt;y&gt; &lt;z&gt;     setblock &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;方块&gt;
 *   summon &lt;实体&gt; [x y z]   time &lt;…&gt;          weather &lt;clear|rain|thunder&gt;
 *   gamemode &lt;模式&gt;         effect &lt;…&gt;        clear
 *   raw &lt;任意命令&gt;          （逃生口，不做任何改写）
 *   set &lt;x y z&gt; &lt;方块&gt;      fill &lt;区域&gt; &lt;方块&gt;  hollow &lt;宽 高 深&gt; &lt;方块&gt;
 *   house &lt;宽&gt; &lt;深&gt; [高] [材质]                ← 一栋简易房子（见 McBuild）
 * </pre>
 */
public final class McTarget implements AiTarget {

    public static final McTarget INSTANCE = new McTarget();

    private McTarget() {
    }

    @Override
    public String id() {
        return "mc";
    }

    @Override
    public String displayName() {
        return "Minecraft 运行时（动态内容报告 + 游戏操作）";
    }

    @Override
    public List<String> items() {
        return McReports.KINDS;
    }

    @Override
    public Path dump(String item) {
        try {
            return McReports.write(item);
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public String describe(String item) {
        return switch (item) {
            case "mods" -> "已加载模组清单（id/版本/名称）";
            case "registries" -> "静态注册表 + 数据包驱动的动态注册表（条目数 + 样本）";
            case "recipes" -> "配方总数与按类型统计（数据包驱动）";
            case "tags" -> "各注册表的标签数量与前若干条（数据包驱动）";
            case "packs" -> "资源包/数据包来源清单（动态加载载体）";
            case "world" -> "当前世界状态（维度/时间/天气/群系/方块/光照）";
            case "player" -> "玩家状态（位置/生命/饥饿/背包）";
            case "entities" -> "附近 64 格实体清单";
            default -> item;
        };
    }

    /**
     * <b>通用指令执行接口</b>：不维护动作白名单 —— AI 想执行什么就传什么。
     *
     * <ol>
     *   <li>先试建造便捷指令（set / fill / hollow / house）；</li>
     *   <li>否则把 {@code action + args} <b>原样拼成一条原版命令</b>发出去
     *       （唯一加工：把只写简名的方块/物品补成 {@code minecraft:xxx}，且只在注册表命中时才改）。</li>
     * </ol>
     *
     * <p>走的是玩家命令通道，与手敲完全一致，含权限与服务端校验；因此
     * <b>原版能做什么，这里就能做什么</b>（fill/clone/structure/execute/datapack…），
     * 新增能力不需要改代码。</p>
     */
    @Override
    public boolean act(String action, List<String> args) {
        if (action == null || action.isBlank()) {
            return false;
        }
        final String built = McBuild.build(action, McBuild.normalizeAll(args));
        if (built != null) {
            return !built.startsWith("ERR");
        }
        // raw 是"跳过便捷层"的显式写法，与直接透传等价
        final List<String> effective = "raw".equalsIgnoreCase(action) ? args : args;
        final String command = ("raw".equalsIgnoreCase(action) ? "" : action + " ")
                + String.join(" ", McBuild.normalizeAll(effective));
        final String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        mc.player.connection.sendCommand(trimmed);
        return true;
    }
}
