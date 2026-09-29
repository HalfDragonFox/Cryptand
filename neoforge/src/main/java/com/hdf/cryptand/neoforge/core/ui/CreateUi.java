/**
 * ===== Create 风格 UI 构件（LDLib2，2026-09-13） =====
 *
 * 为"偏向 Create 风格"的界面提供统一配色与构件（用户要求）：
 * 深色黄铜调面板 + Create 自带 GUI 贴图（`create:textures/gui/value_settings.png`
 * 的黄铜框切片）+ 左标签右控件的信息行 + Create 风格按钮（底/悬停/按下三态）。
 *
 * 2026-09-13 二版（用户："太长了，UI 需要平铺"）：改为【横向平铺】构件——
 * {@link #column} / {@link #card} 两列分栏 + {@link #compactRow} 定宽标签行 +
 * {@link #gaugeLine} 单行数值条；并且【所有 Label 一律 adaptiveWidth(true)】——
 * LDLib2 的 TextElement 默认占满宽度，会让标签与右侧控件重叠（实测截图）。
 *
 * 纯 UI 工具，零子包引用（core 只提供能力）。
 */

package com.hdf.cryptand.neoforge.core.ui;

import com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class CreateUi {

    // ==================== 配色（Create 黄铜 / 深色 GUI 调） ====================

    /** 面板底（近黑棕，略透明） */
    public static final int PANEL_BG = 0xF01A1917;
    /** 卡片/内层底 */
    public static final int PANEL_INNER = 0xFF232120;
    /** 行背景 / 悬停 / 选中 */
    public static final int ROW_BG = 0xFF2F2C28;
    public static final int ROW_HOVER = 0xFF413B33;
    public static final int ROW_SELECTED = 0xFF5A4726;
    /** 黄铜（亮 / 暗 / 极暗） */
    public static final int BRASS = 0xFFC6A15B;
    public static final int BRASS_DARK = 0xFF7A6234;
    public static final int BRASS_DEEP = 0xFF4A3C21;
    /** 文字（主 / 次 / 强调） */
    public static final int TEXT = 0xFFE9E2D0;
    public static final int TEXT_DIM = 0xFF9C9584;
    public static final int TEXT_GOOD = 0xFF8FD07A;
    public static final int TEXT_WARN = 0xFFE0A05F;
    public static final int TEXT_BAD = 0xFFD96C6C;

    /** 行内标签列宽（px；定宽保证多行对齐；中文标签最长 6 字 ≈ 60px） */
    public static final float LABEL_W = 60f;
    /** 行高（px） */
    public static final float ROW_H = 14f;

    /**
     * 设备输入轴的<b>展示名</b>（顺序 = {@code GameInputState} 的 8 轴标准序）。
     * <p>⚠ 全外设共用这<b>一张</b>表（船舵"转向轴" / 拉杆"踏板轴" / 传动器"输入轴"）：
     * 传动器以前自己用 "Axis 0..7"，与其它外设对不上（用户实测截图要求统一）。</p>
     */
    public static final List<String> AXIS_NAMES = List.of(
            "X", "Y", "Z", "Rx", "Ry", "Rz", "Slider 0", "Slider 1");

    /** Create GUI 贴图（九宫格黄铜框 + 数值条素材） */
    public static final ResourceLocation CREATE_VALUE_SETTINGS =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/value_settings.png");

    private CreateUi() {
    }

    // ==================== 贴图工具 ====================

    /** Create 贴图切片（拉伸填满元素） */
    public static SpriteTexture sprite(ResourceLocation image, int x, int y, int w, int h) {
        return SpriteTexture.of(image).setSprite(x, y, w, h);
    }

    /** Create 黄铜横条（value_settings 的 BRASS_FRAME_TOP / _BOTTOM 切片，256x3） */
    public static UIElement brassStrip(boolean top) {
        return new UIElement()
                .layout(l -> l.widthPercent(100).height(3))
                .style(s -> s.background(sprite(CREATE_VALUE_SETTINGS, 0, top ? 24 : 27, 256, 3)));
    }

    /** 纯色块 */
    public static IGuiTexture fill(int color) {
        return new ColorRectTexture(color);
    }

    /** 纯色块 + 描边（Create 风格按钮/行的底） */
    public static IGuiTexture bordered(int fill, int border, int borderWidth) {
        return IGuiTexture.group(
                new ColorRectTexture(fill),
                new ColorBorderTexture().setBorder(borderWidth).setColor(border));
    }

    // ==================== 面板 / 分栏 ====================

    // ==================== 屏幕自适应（2026-09-14） ====================
    //
    // 用户反馈："UI页面过大…缩小后还是一样，UI能否自适应缩放"。实测根因：
    // LDLib2 的 UI 用的是【逻辑像素】，而面板尺寸是写死的常量（如 452）——
    // 当窗口较小、GUI Scale 较大时（逻辑分辨率可能只有 500×310），452 宽的面板必然溢出。
    // LDLib2 本身【没有整体缩放 API】（UIElement 上没有 scale），所以这里改用
    // "按当前屏幕逻辑尺寸收窄"的方式做自适应：面板/列宽都不会超过屏幕的 94%。

    /** 当前屏幕逻辑宽度（GUI Scale 之后） */
    public static float screenWidth() {
        try {
            return Math.max(160f,
                    net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth());
        } catch (Throwable t) {
            return 320f;   // MC 默认逻辑宽
        }
    }

    /** 当前屏幕逻辑高度（GUI Scale 之后） */
    public static float screenHeight() {
        try {
            return Math.max(120f,
                    net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight());
        } catch (Throwable t) {
            return 240f;
        }
    }

    /**
     * 宽度归一（<b>不再做自适应</b>）。
     * <p>用户 2026-09-14 定稿："可以不需要自己写的自适应，只需要把UI改小即可" ——
     * 面板尺寸一律用固定的设计值（调用方传入多少就是多少），
     * 只要设计值足够小，任何窗口/GUI Scale 下都不会溢出。
     * <p>注意：`panel`/`column` 里的 `minWidth/maxWidth` 锁定仍然保留 ——
     * 那是防止**子元素内容把父容器撑开**（另一类问题），与自适应无关。
     */
    public static float fitWidth(float want) {
        return want;
    }

    /**
     * 面板根（<b>宽高都由调用方给定</b>）—— 照 LDLib2 生态里 NeoECOAEExtension 的写法：
     * 它的 root 就是 `layout.width(344).height(232)` 这样的固定尺寸。
     * <p>为什么固定高度更稳：高度自适应时，内容多一行就会把"保存/取消"顶出窗口；
     * 给定高度后布局完全可预测（小窗口 + GUI Scale=2 也能放下）。
     *
     * @param width  设计宽（逻辑像素）
     * @param height 设计高（逻辑像素）；&lt;= 0 表示高度自适应（旧行为）
     */
    public static UIElement panel(float width, float height, UIElement... children) {
        return buildPanel(width, height, children);
    }

    /** 面板根：宽度固定、高度自适应（内容请用 {@link #content}） */
    public static UIElement panel(float width, UIElement... children) {
        return buildPanel(width, -1f, children);
    }

    // ==================== 面板尺寸约定（调用方不要手算） ====================

    /** 面板自身四周内边距（brassStrip 与内容之间） */
    public static final float PANEL_PAD = 3f;
    /** 面板内纵向间隙（条纹 / 内容之间） */
    public static final float PANEL_GAP = 3f;
    /** 横排主体 body 的列间距 */
    public static final float BODY_GAP = 6f;
    /** 横排主体 body 的左右内边距 */
    public static final float BODY_PAD_H = 8f;

    /**
     * 按列宽算出 panel 需要的总宽 —— 调用方【不要手算】。
     * <p>需要算进去的固定开销：面板 padding(PANEL_PAD×2) + body 的列间距(BODY_GAP×列数差)
     * + body 左右 padding(BODY_PAD_H×2)。漏算会让最右列顶出面板
     * （实测 bug：外设模拟传动器右列贴着面板右边缘、"曲线图超出"）。
     */
    public static float panelWidthFor(float... columnWidths) {
        float sum = 0f;
        for (float w : columnWidths) {
            sum += fitWidth(w);
        }
        final int gaps = Math.max(0, columnWidths.length - 1);
        return sum + BODY_GAP * gaps + BODY_PAD_H * 2f + PANEL_PAD * 2f;
    }

    private static UIElement buildPanel(float width, float height, UIElement... children) {
        final float w = fitWidth(width);
        final float h = height;
        UIElement inner = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.COLUMN).gapAll(4))
                .style(s -> s.background(fill(PANEL_BG)));
        // ⚠ 必须同时锁 min/max：只给 width 时，Taffy 下子元素的内容宽度会反过来把父容器撑开
        //   —— 实测"界面过宽、超出屏幕"，而且几个外设界面都有同样现象（共性问题，故修在此处）。
        UIElement root = new UIElement()
                // ⚠ 高度也要省着用：小窗口 + GUI Scale=2 时逻辑高度可能只有 ~310，
                //   内边距/行距每多 1px 都会把"保存/取消"挤出屏幕（用户实测"所有UI上下都超出"）。
                // ⚠ 四周留白（用户："上下左右必须有一块留白"）：margin 撑开与窗口边缘的距离，
                //   小窗口下也不至于贴边或被标题栏压住。
                .layout(l -> {
                    l.width(w).minWidth(w).maxWidth(w).marginAll(10)
                            .flexDirection(FlexDirection.COLUMN).paddingAll(PANEL_PAD).gapAll(PANEL_GAP);
                    if (h > 0f) {
                        l.height(h).minHeight(h).maxHeight(h);
                    }
                })
                .style(s -> s.background(bordered(PANEL_INNER, BRASS_DARK, 2)));
        root.addChild(brassStrip(true));
        root.addChild(inner);
        root.addChild(brassStrip(false));
        if (children.length > 0) {
            inner.addChildren(children);
        }
        return root;
    }

    /** 面板内容容器（panel 的索引 1 子元素） */
    public static UIElement content(UIElement panel) {
        return panel.getChildren().size() > 1 ? panel.getChildren().get(1) : panel;
    }

    /** 横向主体：左右分栏容器（平铺核心） */
    public static UIElement body(UIElement... columns) {
        UIElement row = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.STRETCH).gapAll(BODY_GAP).paddingHorizontal(BODY_PAD_H));
        row.addChildren(columns);
        return row;
    }

    /** 纵列（宽度按屏幕自适应收窄） */
    public static UIElement column(float width, UIElement... children) {
        final float w = fitWidth(width);
        // 同 panel：列宽必须锁定，否则列内长文本/列表会把整列撑开
        UIElement col = new UIElement()
                .layout(l -> l.width(w).minWidth(w).maxWidth(w)
                        .flexDirection(FlexDirection.COLUMN).gapAll(4));
        if (children.length > 0) {
            col.addChildren(children);
        }
        return col;
    }

    /** 标题栏：左侧黄铜竖条 + 标题 + 右侧状态（可空） */
    public static UIElement titleBar(Component title, Label trailing) {
        UIElement bar = new UIElement()
                .layout(l -> l.widthPercent(100).height(14).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER).gapAll(6).paddingHorizontal(6));
        bar.addChild(new UIElement()
                .layout(l -> l.width(3).height(11))
                .style(s -> s.background(fill(BRASS))));
        bar.addChild(label(title, BRASS, 11f));
        if (trailing != null) {
            trailing.layout(l -> l.marginLeftAuto());
            bar.addChild(trailing);
        }
        return bar;
    }

    /** 小节卡片（紧凑：小标题 + 内容行，暗底 + 细黄铜描边） */
    public static UIElement card(String titleKey, UIElement... children) {
        UIElement box = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.COLUMN)
                        .gapAll(3).paddingAll(5))
                .style(s -> s.background(bordered(0xB01B1A18, BRASS_DEEP, 1)));
        box.addChild(key(titleKey, BRASS, 9f));
        if (children.length > 0) {
            box.addChildren(children);
        }
        return box;
    }

    // ==================== 文本 ====================

    /**
     * 文本（⚠ 一律 adaptiveWidth + adaptiveHeight：LDLib2 的 TextElement 默认占满父宽，
     * 会让"左标签右控件"两两重叠——这是实测截图里"轴X/转反向"被压成一字的原因）。
     */
    public static Label label(Component text, int color, float fontSize) {
        Label l = new Label();
        l.setText(text);
        l.textStyle(ts -> ts.fontSize(fontSize).textColor(color)
                .adaptiveWidth(true).adaptiveHeight(true)
                .textWrap(TextWrap.HOVER_ROLL));
        return l;
    }

    public static Label key(String translateKey, int color, float fontSize) {
        return label(Component.translatable(translateKey), color, fontSize);
    }

    public static Label dim(String translateKey) {
        return key(translateKey, TEXT_DIM, 9f);
    }

    /** 定宽标签（行内左列；右对齐数值用 fixedLabel） */
    public static Label fixedLabel(String translateKey, float width, int color) {
        Label l = key(translateKey, color, 10f);
        l.layout(lo -> lo.width(width).flexShrink(0));
        return l;
    }

    /** 定宽数值（右对齐，避免数值变化导致布局跳动） */
    public static Label valueLabel(Component text, float width, int color, float fontSize) {
        Label l = new Label();
        l.setText(text);
        l.textStyle(ts -> ts.fontSize(fontSize).textColor(color)
                .adaptiveWidth(false).adaptiveHeight(true)
                .textAlignHorizontal(Horizontal.RIGHT)
                .textWrap(TextWrap.HOVER_ROLL));
        l.layout(lo -> lo.width(width).flexShrink(0));
        return l;
    }

    /** 说明文字（换行、自动高度） */
    public static Label hint(String translateKey) {
        Label l = key(translateKey, TEXT_DIM, 9f);
        l.textStyle(ts -> ts.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        l.layout(lo -> lo.widthPercent(100));
        return l;
    }

    // ==================== 行 / 控件 ====================

    /** 信息行：定宽标签 + 右侧控件（控件靠右） */
    public static UIElement row(String labelKey, UIElement control) {
        UIElement r = new UIElement()
                .layout(l -> l.widthPercent(100).height(ROW_H).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER).gapAll(4));
        if (labelKey != null) {
            r.addChild(fixedLabel(labelKey, LABEL_W, TEXT));
        }
        if (control != null) {
            control.layout(l -> l.marginLeftAuto());
            r.addChild(control);
        }
        return r;
    }

    /** 滑块行：定宽标签 + 伸展滑块 + 定宽数值 */
    public static UIElement sliderRow(String labelKey, UIElement slider, Label value) {
        UIElement r = new UIElement()
                .layout(l -> l.widthPercent(100).height(ROW_H).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER).gapAll(4));
        r.addChild(fixedLabel(labelKey, LABEL_W, TEXT));
        slider.layout(l -> l.flex(1).height(9));
        r.addChild(slider);
        r.addChild(value);
        return r;
    }

    // ==================== 弹窗层（overlay） ====================

    /**
     * 在 {@code root} 内叠一层【绝对定位的全屏遮罩】，内部 flex 居中放 {@code card}——弹窗统一写法。
     * <p>照 LDLib2 生态 NeoECOAEExtension 的做法：该项目<b>全项目不用 {@code Dialog}</b>。
     * 好处：弹窗不参与主面板的 flex 流（打开时页面不重排），位置完全由我们控制。
     * <p>⚠ 遮罩必须<b>最后 addChild</b> 才会盖在最上层；关闭请用 {@link #closeOverlay}。
     */
    public static UIElement overlay(UIElement root, UIElement card) {
        UIElement layer = new UIElement()
                .layout(l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(0).top(0).widthPercent(100).heightPercent(100)
                        // ⚠ justifyContent 取的是 AlignContent（不是 JustifyContent）—— javap 验证过
                        .justifyContent(dev.vfyjxf.taffy.style.AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER))
                .style(s -> s.background(fill(0x40000000)));
        layer.addChild(card);
        if (root != null) {
            root.addChild(layer);
        }
        layer.setDisplay(dev.vfyjxf.taffy.style.TaffyDisplay.FLEX);
        return layer;
    }

    /** 关闭弹窗层：隐藏并摘除（避免反复打开时在树里堆积） */
    public static void closeOverlay(UIElement root, UIElement layer) {
        if (layer == null) {
            return;
        }
        layer.setDisplay(dev.vfyjxf.taffy.style.TaffyDisplay.NONE);
        if (root != null) {
            try {
                root.removeChild(layer);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单行数值条：定宽标签 + Create 轨道（含填充）+ 定宽数值 */
    public static UIElement gaugeLine(String labelKey, UIElement fillElement, Label value) {
        UIElement r = new UIElement()
                .layout(l -> l.widthPercent(100).height(12).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER).gapAll(4));
        r.addChild(fixedLabel(labelKey, LABEL_W, TEXT_DIM));
        UIElement track = new UIElement()
                .layout(l -> l.flex(1).height(8))
                .style(s -> s.background(sprite(CREATE_VALUE_SETTINGS, 7, 0, 249, 8)));
        fillElement.layout(l -> l.heightPercent(100).widthPercent(0));
        fillElement.style(s -> s.background(fill(BRASS)));
        track.addChild(fillElement);
        r.addChild(track);
        r.addChild(value);
        return r;
    }

    /** Create 风格按钮（黄铜描边三态） */
    public static Button button(String translateKey, Runnable onClick) {
        Button b = new Button().setText(Component.translatable(translateKey));
        b.textStyle(ts -> ts.fontSize(10).textColor(TEXT).adaptiveWidth(true));
        b.buttonStyle(bs -> bs
                .baseTexture(bordered(ROW_BG, BRASS_DARK, 1))
                .hoverTexture(bordered(ROW_HOVER, BRASS, 1))
                .pressedTexture(bordered(ROW_SELECTED, BRASS, 1)));
        b.layout(l -> l.height(17));
        b.setOnClick(ev -> onClick.run());
        return b;
    }

    /** 小号按钮（定宽） */
    public static Button smallButton(String translateKey, float width, Runnable onClick) {
        Button b = button(translateKey, onClick);
        b.layout(l -> l.width(width).height(15));
        return b;
    }

    /** 可点击的行（设备列表项；selected 决定底色） */
    public static Button rowButton(boolean selected, Runnable onClick) {
        Button b = new Button();
        b.noText();
        int base = selected ? ROW_SELECTED : ROW_BG;
        int hover = selected ? 0xFF6B5527 : ROW_HOVER;
        b.buttonStyle(bs -> bs
                .baseTexture(bordered(base, selected ? BRASS : BRASS_DEEP, 1))
                .hoverTexture(bordered(hover, BRASS, 1))
                .pressedTexture(bordered(ROW_SELECTED, BRASS, 1)));
        b.layout(l -> l.widthPercent(100).height(15).flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER).paddingHorizontal(4).gapAll(4));
        b.setOnClick(ev -> onClick.run());
        return b;
    }

    /** 分隔线（暗黄铜细条） */
    public static UIElement divider() {
        return new UIElement()
                .layout(l -> l.widthPercent(100).height(1).marginVertical(1))
                .style(s -> s.background(fill(BRASS_DEEP)));
    }

    /** 底部按钮行 */
    public static UIElement buttonRow(UIElement... buttons) {
        UIElement row = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW)
                        .gapAll(6).paddingHorizontal(6).paddingBottom(3));
        row.addChildren(buttons);
        return row;
    }
}
