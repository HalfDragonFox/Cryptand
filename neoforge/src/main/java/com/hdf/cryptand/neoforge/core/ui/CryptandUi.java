/**
 * ===== LDLib2 UI 共享工具（2026-08-13 用户要求：UI 全部使用 ldlib，现代化） =====
 *
 * 统一的面板/标题/输入行等 UI 构件，供各元件配置界面复用。
 * 全部基于 LDLib2（com.lowdragmc.lowdraglib2）声明式 Java API。
 */

package com.hdf.cryptand.neoforge.core.ui;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

public final class CryptandUi {

    private CryptandUi() {}

    /** 面板根：深色圆角边框 + 纵向布局，返回容器（宽度固定、高度自适应） */
    public static UIElement panel(float width, UIElement... children) {
        return new UIElement()
                .layout(l -> l.width(width)
                        .flexDirection(FlexDirection.COLUMN)
                        .paddingAll(16).gapAll(8))
                .style(s -> s.background(ColorPattern.SEAL_BLACK.borderTexture(2)))
                .addChildren(children);
    }

    /** 面板标题（居中、亮色、略大字号） */
    public static Label title(String translateKey) {
        Label l = new Label();
        l.setText(Component.translatable(translateKey));
        l.textStyle(ts -> ts.fontSize(16).textColor(0xFFFFFFFF)
                .textAlignHorizontal(Horizontal.CENTER));
        return l;
    }

    /** 副标题/说明文字（灰色小字，居中） */
    public static Label subtitle(String translateKey) {
        Label l = new Label();
        l.setText(Component.translatable(translateKey));
        l.textStyle(ts -> ts.fontSize(11).textColor(0xFF9AA5B1)
                .textAlignHorizontal(Horizontal.CENTER));
        return l;
    }

    /** 状态/数值文字（可指定颜色） */
    public static Label infoText(String text, int color) {
        Label l = new Label();
        l.setText(Component.literal(text));
        l.textStyle(ts -> ts.fontSize(12).textColor(color)
                .textAlignHorizontal(Horizontal.CENTER));
        return l;
    }

    /**
     * 输入行：左侧标签 + 右侧可伸展输入框（横向布局）。
     * label 可为 null（仅输入框）。
     * 2026-08-20 修复"标签文字不全（频率→频）"：Label 默认 flexShrink=1 被
     * 右侧 flex(1) 输入框压缩到最小宽度（只显示一个字符）。设 flexShrink(0)
     * 让标签按内容宽度完整显示。
     */
    public static UIElement inputRow(String labelKey, TextField field) {
        UIElement row = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6)
                        .alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER));
        if (labelKey != null) {
            Label lab = new Label();
            lab.setText(Component.translatable(labelKey));
            lab.textStyle(ts -> ts.textColor(0xFFD5DBE1));
            // 标签不被压缩（否则 flex(1) 输入框把标签挤到单字符宽）
            lab.layout(l -> l.flexShrink(0));
            row.addChild(lab);
        }
        row.addChild(field.layout(l -> l.flex(1)));
        return row;
    }

    /** 确认按钮（主操作，居中） */
    public static Button confirmButton(String translateKey, java.util.function.Consumer<com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent> onClick) {
        Button b = new Button();
        b.setText(Component.translatable(translateKey));
        b.setOnClick(ev -> onClick.accept(ev));
        b.layout(l -> l.widthPercent(100).height(20));
        b.style(s -> s.color(0xFF3A5F8A));
        return b;
    }

    /** 便捷：横向分隔线（深色细条） */
    public static UIElement divider() {
        return new UIElement()
                .layout(l -> l.widthPercent(100).height(1).marginVertical(2))
                .style(s -> s.background(ColorPattern.T_DARK_GRAY.rectTexture()));
    }

    /** 数字输入框（不强制验证，解析时容错） */
    public static TextField numberField(String initial) {
        return (TextField) new TextField().setText(initial)
                .textFieldStyle(ts -> ts.fontSize(12))
                .layout(l -> l.height(18));
    }
}
