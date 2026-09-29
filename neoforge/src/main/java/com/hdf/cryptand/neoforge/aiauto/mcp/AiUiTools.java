package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Slider;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== UI 交互工具：查界面元素 + 真点击（aiauto/mcp）=====
 *
 * <p>让 AI 不只看布局树，还能<b>看到有哪些可交互控件、并真的点下去</b>：</p>
 * <ul>
 *   <li>{@code ui_query}：列出指定界面的<b>可交互元素</b>（按钮/下拉/开关/输入框/槽位/滑条），
 *       带索引、类型、文本、位置尺寸 —— 索引可用于后续点击；</li>
 *   <li>{@code ui_click}：按索引或文本匹配元素，走 LDLib2 真实事件派发
 *       （mouseEnter → mouseDown → mouseUp，与人工点击同一条代码路径）；</li>
 *   <li>{@code ui_set_text}：给输入框写值。</li>
 * </ul>
 *
 * <p>元素来源是 aiauto 已登记的界面根（{@link LdlibPanels#root(String)}），
 * 因此不依赖任何 ModularUI 内部 API。</p>
 */
public final class AiUiTools {

    private AiUiTools() {
    }

    public static void registerAll() {
        AiToolServer.register("ui_list", "列出已登记的界面（可作为 item 参数的对象）",
                AiToolServer.schema(), args -> {
                    final JsonObject o = new JsonObject();
                    final JsonArray arr = new JsonArray();
                    LdlibPanels.names().forEach(arr::add);
                    o.add("items", arr);
                    return o;
                });

        AiToolServer.register("ui_dump", "导出界面布局树（每元素计算后 pos/size/文本）",
                AiToolServer.schema("item", "string"),
                args -> {
                    try {
                        return AiToolServer.ok(String.valueOf(
                                LdlibPanels.dump(AiToolServer.str(args, "item", "download"))));
                    } catch (Exception ex) {
                        return AiToolServer.ok("ERR " + ex.getMessage());
                    }
                });

        AiToolServer.register("ui_open", "程序化打开界面（无需人工点击）",
                AiToolServer.schema("item", "string"),
                args -> AiToolServer.ok(LdlibPanels.open(AiToolServer.str(args, "item", "download"))
                        ? "已打开" : "ERR 打开失败"));

        AiToolServer.register("ui_close", "关闭当前界面",
                AiToolServer.schema(), args -> {
                    final var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.closeContainer();
                    }
                    mc.setScreen(null);
                    return AiToolServer.ok("已关闭");
                });

        AiToolServer.register("ui_screenshot", "截当前界面到 latest-ldlib-<item>.png",
                AiToolServer.schema("item", "string"),
                args -> {
                    final String item = AiToolServer.str(args, "item", "download");
                    AiAutomation.grabScreenshot("ldlib", item);
                    return AiToolServer.ok(String.valueOf(AiAutomation.artifact("ldlib", item, "png")));
                });

        AiToolServer.register("reload_resources",
                "重载资源包（等价 F3+T）：让改过的 .lss 主题/贴图免重启生效",
                AiToolServer.schema("confirm", "bool（必须为 true，避免误触）"),
                args -> {
                    if (!AiToolServer.bool(args, "confirm", false)) {
                        return AiToolServer.ok("ERR 需要 {\"confirm\":true} 才会重载");
                    }
                    net.minecraft.client.Minecraft.getInstance().reloadResourcePacks();
                    return AiToolServer.ok("已触发资源重载（.lss 主题改动生效；"
                            + "注意 Java 构建的布局仍需要重启）");
                });

        AiToolServer.register("ui_reload",
                "重开界面：关掉当前屏幕并用登记时的样式表重新打开（配合 reload_resources 应用新样式）",
                AiToolServer.schema("item", "string（download/chip/assembler）"),
                args -> {
                    final String item = AiToolServer.str(args, "item", "download");
                    final var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.closeContainer();
                    }
                    mc.setScreen(null);
                    final boolean ok = LdlibPanels.open(item);
                    return AiToolServer.ok(ok ? "已重开 " + item : "ERR 重开失败（未登记？）");
                });

        AiToolServer.register("ui_debug", "打开 LDLib2 UIDebugger（层级树/计算样式/盒模型高亮）",
                AiToolServer.schema(), args -> AiToolServer.ok(
                        LdlibPanels.debug() ? "已打开" : "ERR 需要界面在屏幕上"));

        AiToolServer.register("ui_query",
                "列出界面的可交互元素（按钮/下拉/开关/输入框/槽位/滑条）与索引",
                AiToolServer.schema("item", "string（download/chip/assembler）",
                        "all", "boolean（true = 连非交互元素一起列出，默认 false）"),
                args -> {
                    final String item = AiToolServer.str(args, "item", "download");
                    final boolean all = AiToolServer.bool(args, "all", false);
                    final UIElement root = LdlibPanels.root(item);
                    final JsonObject o = new JsonObject();
                    if (root == null) {
                        o.addProperty("error", "界面 " + item + " 未登记（先在游戏里打开一次）");
                        return o;
                    }
                    final List<UIElement> flat = new ArrayList<>();
                    flatten(root, flat, 0);
                    final JsonArray interactive = new JsonArray();
                    final JsonArray others = new JsonArray();
                    int index = 0;
                    for (UIElement el : flat) {
                        final String kind = kindOf(el);
                        final JsonObject j = describe(el, index);
                        if (!kind.isEmpty()) {
                            interactive.add(j);
                        } else if (all) {
                            others.add(j);
                        }
                        index++;
                    }
                    o.addProperty("item", item);
                    o.addProperty("elements", flat.size());
                    o.addProperty("interactiveCount", interactive.size());
                    o.add("interactive", interactive);
                    if (all) {
                        o.add("all", others);
                    }
                    return o;
                });

        AiToolServer.register("ui_click",
                "点击界面元素（走真实事件派发；用 index 或 text 指定目标）",
                AiToolServer.schema("item", "string（download/chip/assembler）",
                        "index", "int（ui_query 给的索引，可空）",
                        "text", "string（按钮文本，可空；与 index 二选一）"),
                args -> {
                    final String item = AiToolServer.str(args, "item", "download");
                    final UIElement target = find(item, AiToolServer.num(args, "index", -1),
                            AiToolServer.str(args, "text", ""));
                    final JsonObject o = new JsonObject();
                    if (target == null) {
                        o.addProperty("error", "没找到目标元素（先用 ui_query 看清单）");
                        return o;
                    }
                    o.addProperty("clicked", kindOf(target));
                    o.addProperty("ok", click(target));
                    return o;
                });

        AiToolServer.register("ui_set_text",
                "给界面的输入框写值（用 index 或 text 定位输入框本身）",
                AiToolServer.schema("item", "string", "index", "int",
                        "value", "string（要写入的文本）"),
                args -> {
                    final String item = AiToolServer.str(args, "item", "download");
                    final UIElement target = find(item, AiToolServer.num(args, "index", -1), "");
                    final JsonObject o = new JsonObject();
                    if (target instanceof TextField tf) {
                        final String value = AiToolServer.str(args, "value", "");
                        tf.setValue(value);
                        o.addProperty("ok", true);
                        o.addProperty("value", value);
                    } else {
                        o.addProperty("ok", false);
                        o.addProperty("error", "目标不是输入框");
                    }
                    return o;
                });
    }

    // ==================== 元素遍历与描述 ====================

    private static void flatten(UIElement element, List<UIElement> out, int depth) {
        if (element == null || depth > 32) {
            return;
        }
        out.add(element);
        for (UIElement child : element.getChildren()) {
            flatten(child, out, depth + 1);
        }
    }

    private static JsonObject describe(UIElement el, int index) {
        final JsonObject j = new JsonObject();
        j.addProperty("index", index);
        String type = null;
        try {
            type = el.getElementName();
        } catch (Throwable ignored) {
        }
        j.addProperty("type", type == null || type.isBlank() ? el.getClass().getSimpleName() : type);
        j.addProperty("kind", kindOf(el));
        if (el instanceof Label label) {
            final var value = label.getValue();
            j.addProperty("text", value == null ? "" : value.getString());
        }
        j.addProperty("x", Math.round(el.getPositionX()));
        j.addProperty("y", Math.round(el.getPositionY()));
        j.addProperty("w", Math.round(el.getSizeWidth()));
        j.addProperty("h", Math.round(el.getSizeHeight()));
        return j;
    }

    /** 可交互类型判定（空串 = 非交互） */
    private static String kindOf(UIElement el) {
        if (el instanceof Button) {
            return "button";
        }
        if (el instanceof Selector) {
            return "selector";
        }
        if (el instanceof TextField) {
            return "text-field";
        }
        if (el instanceof Switch) {
            return "switch";
        }
        if (el instanceof Slider) {
            return "slider";
        }
        if (el instanceof ItemSlot) {
            return "item-slot";
        }
        if (el instanceof ScrollerView) {
            return "scroller";
        }
        return "";
    }

    // ==================== 查找与点击 ====================

    private static UIElement find(String item, int index, String text) {
        final UIElement root = LdlibPanels.root(item);
        if (root == null) {
            return null;
        }
        final List<UIElement> flat = new ArrayList<>();
        flatten(root, flat, 0);
        if (index >= 0 && index < flat.size()) {
            return flat.get(index);
        }
        if (!text.isBlank()) {
            for (UIElement el : flat) {
                if (el instanceof Label label) {
                    final var v = label.getValue();
                    if (v != null && v.getString().contains(text)) {
                        return el;
                    }
                }
            }
            // 文本在按钮的子 label 上 → 退一步找最近的 Button 祖先
            for (UIElement el : flat) {
                if (el instanceof Button && el.getChildren().stream()
                        .filter(c -> c instanceof Label)
                        .anyMatch(c -> ((Label) c).getValue() != null
                                && ((Label) c).getValue().getString().contains(text))) {
                    return el;
                }
            }
        }
        return null;
    }

    /**
     * 真实点击：按 LDLib2 的事件链路派发 mouseEnter → mouseDown → mouseUp。
     *
     * <p>坐标用元素中心（事件坐标是相对屏幕的 GUI 坐标，与 {@code getPositionX/Y} 同一坐标系）。</p>
     */
    private static boolean click(UIElement el) {
        try {
            final float cx = el.getPositionX() + el.getSizeWidth() / 2.0F;
            final float cy = el.getPositionY() + el.getSizeHeight() / 2.0F;
            for (String type : List.of(UIEvents.MOUSE_ENTER, UIEvents.MOUSE_DOWN, UIEvents.MOUSE_UP)) {
                final UIEvent event = UIEvent.create(type);
                event.target = el;
                event.x = cx;
                event.y = cy;
                event.button = 0;
                UIEventDispatcher.dispatchEvent(event);
            }
            return true;
        } catch (Throwable ex) {
            AiToolServer.log("  [ui_click] 失败 " + ex);
            return false;
        }
    }
}
