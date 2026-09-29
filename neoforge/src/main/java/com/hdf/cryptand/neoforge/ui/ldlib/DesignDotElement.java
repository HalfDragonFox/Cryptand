package com.hdf.cryptand.neoforge.ui.ldlib;

import com.hdf.cryptand.soc.link.HwCanvasLayout;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.function.Consumer;

/**
 * ===== EDA 引脚点元素（器件的子元素）=====
 *
 * <p>⚠ 2026-09-29 静态审查结论：点**必须做器件的子元素** —— 之前点是 contentRoot 里的独立元素、
 * 坐标在构造时定死，器件拖动后没有任何同步路径 ⇒ 只有刷新（全量重建）才回位。</p>
 *
 * <p>相对定位（left/top 相对器件左上角）⇒ 器件 layout 或 transform 一改，点和它一起动。
 * 可见色块 {@link #VISUAL}，命中热区 {@link #SIZE}（比可见点大一圈，缩放后也好点）。</p>
 */
public class DesignDotElement extends UIElement {

    /** 命中热区边长（世界单位）。 */
    public static final int SIZE = 11;
    /** 可见色块边长（世界单位）。 */
    private static final int VISUAL = 6;

    /** 拖拽载荷：正在从哪个引脚拉线。 */
    public record DotDrag(HwCanvasLayout.Dot dot) {
    }

    private final HwCanvasLayout.Dot dot;
    private Consumer<HwCanvasLayout.Dot> onPressed;
    private Consumer<HwCanvasLayout.Dot> onRightPressed;

    public DesignDotElement(HwCanvasLayout.Dot dot, int nodeX, int nodeY) {
        this.dot = dot;
        final int color = dot.argb() == 0 ? 0xFF808080 : dot.argb();
        final int relX = Math.round(dot.x()) - nodeX - SIZE / 2;
        final int relY = Math.round(dot.y()) - nodeY - SIZE / 2;
        layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(relX).top(relY).width(SIZE).height(SIZE));
        // 内嵌一个小色块（可见部分），热区仍是整个元素
        final UIElement chip = new UIElement();
        chip.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left((SIZE - VISUAL) / 2).top((SIZE - VISUAL) / 2)
                .width(VISUAL).height(VISUAL));
        chip.style(s -> s.background(new ColorRectTexture(color)));
        addChild(chip);
        addEventListener(UIEvents.MOUSE_DOWN, this::onDown);
    }

    public HwCanvasLayout.Dot dot() {
        return dot;
    }

    public DesignDotElement onPressed(Consumer<HwCanvasLayout.Dot> c) {
        this.onPressed = c;
        return this;
    }

    /** 右键点这个引脚：打开通道信息子页面。 */
    public DesignDotElement onRightPressed(Consumer<HwCanvasLayout.Dot> c) {
        this.onRightPressed = c;
        return this;
    }

    /** 这个点在**屏幕**上的中心（随器件拖动、视口平移缩放自动跟着变 —— 不读任何快照坐标）。 */
    public float[] screenCenter() {
        final var p = getWorldMouse(getPositionX() + getSizeWidth() / 2f,
                getPositionY() + getSizeHeight() / 2f);
        return new float[]{p.x, p.y};
    }

    private void onDown(UIEvent ev) {
        if (ev.button == 2) {
            if (onRightPressed != null) {
                onRightPressed.accept(dot);
                ev.stopPropagation();
            }
            return;
        }
        if (ev.button != 0) {
            return;
        }
        ev.stopPropagation();   // 左键拉线，别让器件/视口把它当拖动
        if (onPressed != null) {
            onPressed.accept(dot);
        }
        startDrag(new DotDrag(dot), null);
    }
}
