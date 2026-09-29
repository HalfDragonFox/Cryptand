package com.hdf.cryptand.neoforge.soc.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * ===== SoC UI 组件工具箱（LDLib2，2026-09-15 样式系统版）=====
 *
 * <p>把 `info/AI/soc-ui-design.html` 的设计规范落成可复用构件：<b>外壳 / 标题栏 / 分组框 /
 * 行 / 列 / 语义化文本</b>。三个面板（机箱 · 组装台 · 下载）统一使用。</p>
 *
 * <h3>对照 LDLib2 调研手册（.ai_cache/ldlib2_research/03-ui-design-playbook.md）的落实项</h3>
 * <ul>
 *   <li><b>坑 4</b>：面板根统一加 {@code .panel_bg} 类（内置 10 套主题都实现它，换主题不丢背景）；</li>
 *   <li><b>§4 文本三配方</b>：行内文本一律 {@code adaptiveHeight(true) + adaptiveWidth(true) +
 *       fontSize(8) + textShadow(false) + textWrap(HOVER_ROLL)}——小字不裁、定高行不撑破；</li>
 *   <li><b>§3 主题配方</b>：自建 {@code cryptand:lss/cryptand.lss}（深色科技感，teal 主色），
 *       而不是硬套 {@code ldlib2:lss/modern.lss}；</li>
 *   <li><b>§4 尺寸体系</b>：按钮高 16、进度条高 8、物品槽 18×18、面板 padding 6 + gap 5。</li>
 * </ul>
 *
 * <p><b>分工约定</b>：<b>结构尺寸用 layout(...)</b>（确定性最强），<b>颜色/背景/圆角/字号用 LSS 类</b>。
 * 二者混用时 layout 相当于 inline 样式，会盖掉 lss 同名属性——本条是踩坑经验。</p>
 */
public final class SocUiKit {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * 自建主题资源位置。
     *
     * <p>⚠ <b>不要用 {@code UI.of(root, "cryptand:lss/cryptand.lss")} 这种字符串重载</b>：
     * 它内部走 {@code StylesheetManager.getMergedStylesheets(name)}，名字对不上时
     * <b>静默返回 null</b>（{@code UI.of} 里 {@code .filter(Objects::nonNull)} 直接丢掉），
     * 结果是 UI 一点样式都没有、面板没背景——2026-09-15 实测踩过。</p>
     */
    public static final net.minecraft.resources.ResourceLocation THEME =
            net.minecraft.resources.ResourceLocation.parse("cryptand:lss/cryptand.lss");

    /** @deprecated 保留兼容；请改用 {@link #createUI(UIElement)}（明确加载 + modern 打底） */
    @Deprecated
    public static final String STYLESHEET = "cryptand:lss/cryptand.lss";

    /**
     * 创建 UI：<b>modern 打底 + 自建主题覆盖</b>，任一张加载失败都不会让 UI 变成"无样式"。
     *
     * <p>加载结果会打进日志（{@code [SoC/UI] stylesheet …}），便于排查样式不生效。</p>
     */
    /**
     * 面板使用的样式表（modern 打底 + 自建主题覆盖）。
     *
     * <p>uidev 子包会用它在"程序化打开面板"时重建 UI，所以必须与 {@link #createUI} 保持一致。</p>
     */
    public static com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet[] stylesheets() {
        final com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager mgr =
                com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.INSTANCE;
        final com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet modern =
                mgr.getStylesheetSafe(com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.MODERN);
        com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet theme = null;
        try {
            theme = mgr.getStylesheet(THEME);
        } catch (Throwable ignored) {
        }
        return theme == null
                ? new com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet[]{modern}
                : new com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet[]{modern, theme};
    }

    public static com.lowdragmc.lowdraglib2.gui.ui.UI createUI(UIElement root) {
        final com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager mgr =
                com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.INSTANCE;
        com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet theme = null;
        try {
            theme = mgr.getStylesheet(THEME);
        } catch (Throwable ex) {
            LOGGER.warn("[SoC/UI] 主题样式表加载异常：{}", THEME, ex);
        }
        final com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet modern =
                mgr.getStylesheetSafe(com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.MODERN);
        LOGGER.info("[SoC/UI] 样式表加载：modern={}  theme({})={}",
                modern != null, THEME, theme != null);
        final com.lowdragmc.lowdraglib2.gui.ui.UI ui = theme == null
                ? com.lowdragmc.lowdraglib2.gui.ui.UI.of(root, modern)
                : com.lowdragmc.lowdraglib2.gui.ui.UI.of(root, modern, theme);
        // ⚠ LayoutStyle 只有设置方法（width(float)/width(StyleSizeLength)），没有读取用的 width()；
        //   要读"实际尺寸"只能用 UIElement.getSizeWidth()/getSizeHeight()（taffy 计算后的值，未布局时为 0）。
        LOGGER.info("[SoC/UI] UI 创建完成：root 当前尺寸={}x{}（0 = 尚未布局，正常）",
                Math.round(root.getSizeWidth()), Math.round(root.getSizeHeight()));
        return ui;
    }

    /** 面板/分组类名（LSS 里定义） */
    public static final String CLS_PANEL = "panel_bg";
    public static final String CLS_CARD = "card";
    public static final String CLS_PANEL_TITLE = "panel_title";
    public static final String CLS_GROUP_TITLE = "group_title";

    private SocUiKit() {
    }

    // ==================== 结构 ====================

    /**
     * 面板外壳（.panel_bg = 深色圆角卡片）。
     *
     * <p>⚠ <b>必须按"屏幕逻辑尺寸"钳制</b>：MC 的 GUI Scale 决定逻辑分辨率 ——
     * 1920×1080 + 自动(≈3) 时逻辑只有约 640×360，写死 440×540 会<b>直接超出屏幕</b>
     * （2026-09-15 用户实测：面板上下被裁）。UITest 用最大化窗口（逻辑 ~1272×681）所以没暴露，
     * 复现要用 {@code -PldTestWindow=1280x720 -PldTestGuiScale=3}。</p>
     *
     * <p>⚠ 仍用显式 height：ModularUI 仅在 width/height 为 auto 时才回读 taffy 计算值。</p>
     */
    public static UIElement panel(int width, int height) {
        final UIElement panel = new UIElement().addClass(CLS_PANEL);
        int maxW = width;
        int maxH = height;
        try {
            final var window = net.minecraft.client.Minecraft.getInstance().getWindow();
            maxW = Math.max(180, window.getGuiScaledWidth() - 12);
            maxH = Math.max(140, window.getGuiScaledHeight() - 12);
        } catch (Throwable ignored) {
            // 客户端尚未就绪时按设计尺寸
        }
        final int w = Math.min(width, maxW);
        final int h = Math.min(height, maxH);
        panel.layout(l -> l.width(w).height(h).paddingAll(6).gapAll(5));
        return panel;
    }

    /**
     * 可滚动内容区：占据面板剩余高度，内容超出时可滚动。
     *
     * <p>配合 {@link #panel} 的尺寸钳制，小分辨率 / 大 GUI Scale 下也能看全内容
     * （头部、按钮行、玩家物品栏保持固定，只有中间的信息分组滚动）。</p>
     */
    public static UIElement body(UIElement... children) {
        final com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroller =
                new com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView();
        scroller.scrollerStyle(s -> s.mode(
                com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode.VERTICAL));
        // ⚠ Taffy 的 flexShrink 默认是 0：只写 flexGrow(1) 会让滚动区"只能涨不能缩"，
        //   面板被钳矮时它仍按内容撑高 → 直接溢出、底部内容被裁（2026-09-15 布局树实测：
        //   面板 278 高，scroller-view 却是 289 高）。必须显式允许收缩 + 给最小高度。
        scroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1).minHeight(30));
        scroller.viewContainer(view -> {
            view.layout(l -> l.widthPercent(100).gapAll(5));
            for (UIElement child : children) {
                if (child != null) {
                    view.addChild(child);
                }
            }
        });
        return scroller;
    }

    /**
     * 标题栏：主标题靠左 + 状态靠右（横排，占满宽度）。
     *
     * <p>⚠ 手册 §12 坑：`justifyContent(...)` 吃的是 <b>taffy 的 {@link AlignContent}</b>
     * （`dev.vfyjxf.taffy.style`，不在 LDLib2 源码树里，所以源码里 grep 不到），
     * 而 `alignItems(...)` 吃 LDLib2 自己的 {@link AlignItems} —— 两者不是同一个枚举。</p>
     */
    public static UIElement titleBar(Label title, Label state) {
        final UIElement bar = new UIElement();
        // ⚠ LDLib2 的 UIElement 默认 flex-direction 是 COLUMN（所以 Button 构造函数才自己设 ROW），
        //   凡"横排"必须显式 ROW —— 2026-09-15 UITest 实测：漏了会把标题/状态、按钮全部竖着堆起来。
        bar.layout(l -> l.widthPercent(100).gapAll(8)
                .flexDirection(FlexDirection.ROW)
                .justifyContent(AlignContent.SPACE_BETWEEN));
        bar.addChildren(title, state);
        return bar;
    }

    /** 分组框：金色小标题 + 内容（.card = 深一档 + 描边） */
    public static UIElement group(String title, UIElement... children) {
        final UIElement box = new UIElement().addClass(CLS_CARD);
        box.layout(l -> l.widthPercent(100).paddingAll(5).gapAll(4));
        box.addChild(groupTitle(title));
        for (UIElement child : children) {
            if (child != null) {
                box.addChild(child);
            }
        }
        return box;
    }

    /** 横向行（占满宽度、间距 6；显式 ROW —— 默认是纵向） */
    public static UIElement row(UIElement... children) {
        final UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100).gapAll(6).flexDirection(FlexDirection.ROW));
        for (UIElement child : children) {
            if (child != null) {
                row.addChild(child);
            }
        }
        return row;
    }

    /** 纵向列（按宽度百分比；用于双栏） */
    public static UIElement column(int widthPercent, UIElement... children) {
        final UIElement col = new UIElement();
        col.layout(l -> l.widthPercent(widthPercent).gapAll(6));
        for (UIElement child : children) {
            if (child != null) {
                col.addChild(child);
            }
        }
        return col;
    }

    /**
     * 玩家物品栏 —— 直接用 LDLib2 官方组件 {@code InventorySlots}。
     *
     * <p>它自带"主背包 3 行 + 快捷栏 1 行"的布局与 id（{@code inventory_0..35}），并在
     * {@code MUI_CHANGED} 时按 {@code mui.getMenu()}/{@code mui.player} 自动绑定玩家背包
     * —— 所以<b>必须走容器菜单</b>（方块 UI 的 BlockUIMenuType 正是），纯客户端屏幕绑不上。</p>
     *
     * <p>机箱/组装台没有它玩家就没有"来源"槽位，硬件放不进去（2026-09-15 用户反馈）。</p>
     */
    public static UIElement playerInventory(net.minecraft.world.entity.player.Player player) {
        final UIElement box = new UIElement();
        box.layout(l -> l.widthPercent(100));
        // 即使 player 为 null 也照常构建（组件会在菜单就绪后自行绑定）
        box.addChild(new com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots());
        return box;
    }

    /** 双栏容器（左 52% / 右 48%） */
    public static UIElement twoColumns(UIElement left, UIElement right) {
        final UIElement body = new UIElement();
        body.layout(l -> l.widthPercent(100).gapAll(6).flexDirection(FlexDirection.ROW));
        body.addChildren(column(52, left), column(100, right));
        return body;
    }

    // ==================== 语义化文本 ====================

    /**
     * 行内文本配方（手册 §4 配方 a）：宽度跟字走 + 自适应高度 + 小字 + 关阴影 + HOVER_ROLL。
     *
     * <p>⚠ {@code textStyle(...)} 声明在 {@link com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement} 上
     * 且返回 TextElement，<b>不能链式赋值回 Label</b>，所以这里用"先建后配"的写法。</p>
     */
    private static Label styled(Label label) {
        label.textStyle(s -> s.adaptiveHeight(true).adaptiveWidth(true)
                .fontSize(8f).textShadow(false).textWrap(TextWrap.HOVER_ROLL));
        return label;
    }

    private static Label label(String text, ChatFormatting color, String cls) {
        final Label label = new Label().setValue(Component.literal(text).withStyle(color));
        if (cls != null) {
            label.addClass(cls);
        }
        return styled(label);
    }

    /** 面板主标题（青） */
    public static Label title(String text) {
        return label(text, ChatFormatting.AQUA, CLS_PANEL_TITLE);
    }

    /** 分组标题（金） */
    public static Label groupTitle(String text) {
        return label(text, ChatFormatting.GOLD, CLS_GROUP_TITLE);
    }

    /** 次要文本（灰） */
    public static Label dim(String text) {
        return label(text, ChatFormatting.DARK_GRAY, "t-dim");
    }

    /** 普通文本（白） */
    public static Label text(String text) {
        return label(text, ChatFormatting.WHITE, null);
    }

    /** 强调文本（青） */
    public static Label accent(String text) {
        return label(text, ChatFormatting.AQUA, "t-accent");
    }

    /** 正常/成功（绿） */
    public static Label ok(String text) {
        return label(text, ChatFormatting.GREEN, "t-ok");
    }

    /** 警告（黄） */
    public static Label warn(String text) {
        return label(text, ChatFormatting.YELLOW, "t-warn");
    }

    /** 错误（红） */
    public static Label err(String text) {
        return label(text, ChatFormatting.RED, "t-err");
    }

    /** 刷新既有 Label 的文本（保留原语义色） */
    public static void set(Label label, String text, ChatFormatting color) {
        label.setValue(Component.literal(text).withStyle(color));
    }
}