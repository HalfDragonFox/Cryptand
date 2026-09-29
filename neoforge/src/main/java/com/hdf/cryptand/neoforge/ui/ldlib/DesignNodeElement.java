package com.hdf.cryptand.neoforge.ui.ldlib;

import com.hdf.cryptand.soc.link.HwCanvasLayout;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.GraphView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ===== EDA 器件元素（世界坐标，2026-09-29 重写）=====
 *
 * <p>器件 = 一个框（世界坐标定位）+ {@link ItemSlot}（原生物品图标）+ {@link Label}（名称，后 addChild ⇒ 在图标之上）
 * + **自己的引脚点作为子元素**（相对定位 ⇒ 器件一动点就跟随）。</p>
 *
 * <p>静态审查（2026-09-29）改掉的三件事：</p>
 * <ol>
 *   <li><b>拖动用 transform 而不是 layout</b>：改 layout 会走 taffy 重排 + 全量缓存失效（整树 computeLayout），
 *       这是"拖动卡顿"的根因；{@code transform} 只影响渲染与命中，松手才落一次 layout。</li>
 *   <li><b>capture 阶段注册 MOUSE_DOWN</b>：{@link ItemSlot} 在 MOUSE_DOWN 上无条件 stopPropagation，
 *       冒泡阶段收不到 ⇒ 以前只有 4px 边框能拖动，现在整块都能拖。</li>
 *   <li><b>点做子元素</b>：见 {@link DesignDotElement}。</li>
 * </ol>
 */
public class DesignNodeElement extends UIElement {

    /** 拖拽载荷：按下那一刻的**世界坐标**（不读 getPositionX，避免布局/transform 掺进来）。 */
    public record NodeDrag(float worldX, float worldY) {
    }

    private final String nodeKey;
    private final GraphView view;
    private final List<DesignDotElement> dotElements = new ArrayList<>();
    private int curX;
    private int curY;
    private boolean moved;

    private Runnable onDragging;
    private Consumer<int[]> onMoved;
    /** 拖动**过程中**的 EDA 增量（线层/命中都靠它跟手；松手后归零由 view 清除）。 */
    private Consumer<int[]> onDragDelta;

    public DesignNodeElement(HwCanvasLayout.Node node, ItemStack icon, GraphView view,
                          List<HwCanvasLayout.Dot> dots) {
        this.nodeKey = node.key();
        this.view = view;
        this.curX = node.x();
        this.curY = node.y();

        layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(node.x()).top(node.y())
                .width(Math.max(26, node.w())).height(Math.max(26, node.h())));
        style(s -> s.background(new ColorRectTexture(0xFF1E2A31)));

        final ItemSlot slot = new ItemSlot();
        if (icon != null && !icon.isEmpty()) {
            slot.setItem(icon);
        }
        slot.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(Math.max(0, (node.w() - 18) / 2)).top(3).width(18).height(18));

        final Label name = new Label();
        name.setText(Component.literal(node.label()));
        name.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(2).top(23).width(Math.max(10, node.w() - 4)).height(10));

        addChildren(slot, name);

        // 引脚点：本器件的点作为子元素（相对定位 ⇒ 自动跟随本器件的 layout 与 transform）
        for (final HwCanvasLayout.Dot d : dots) {
            final DesignDotElement el = new DesignDotElement(d, node.x(), node.y());
            dotElements.add(el);
            addChild(el);
        }

        addEventListener(UIEvents.MOUSE_DOWN, this::onDown, true);   // capture：ItemSlot 会吞掉冒泡事件
        addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::onDragUpdate);
        addEventListener(UIEvents.DRAG_END, this::onDragEnd);
    }

    public String nodeKey() {
        return nodeKey;
    }

    public List<DesignDotElement> dotElements() {
        return dotElements;
    }

    public int curX() {
        return curX;
    }

    public int curY() {
        return curY;
    }

    public DesignNodeElement onDragging(Runnable r) {
        this.onDragging = r;
        return this;
    }

    public DesignNodeElement onDragDelta(Consumer<int[]> c) {
        this.onDragDelta = c;
        return this;
    }

    public DesignNodeElement onMoved(Consumer<int[]> c) {
        this.onMoved = c;
        return this;
    }

    /** 直接摆到某个世界坐标（刷新内容时用）。 */
    public void placeAt(int x, int y) {
        curX = x;
        curY = y;
        layout(l -> l.left(x).top(y));
    }

    private float scale() {
        final float s = view == null ? 1f : view.getScale();
        return s <= 0.0001f ? 1f : s;
    }

    private void onDown(UIEvent ev) {
        if (ev.button != 0) {
            return;
        }
        moved = false;
        startDrag(new NodeDrag(curX, curY), null);
    }

    private void onDragUpdate(UIEvent ev) {
        if (ev.dragHandler == null || !(ev.dragHandler.draggingObject instanceof NodeDrag d)) {
            return;
        }
        final float inv = 1f / scale();
        final float dx = (ev.x - ev.dragStartX) * inv;
        final float dy = (ev.y - ev.dragStartY) * inv;
        curX = Math.round(d.worldX() + dx);
        curY = Math.round(d.worldY() + dy);
        // ⚠ 只改 transform（不动 taffy 布局）：不触发整树重排，这才是跟手的关键
        transform(t -> t.translate(dx, dy));
        moved = true;
        if (onDragDelta != null) {
            onDragDelta.accept(new int[]{Math.round(dx), Math.round(dy)});
        }
        if (onDragging != null) {
            onDragging.run();
        }
    }

    private void onDragEnd(UIEvent ev) {
        transform(t -> t.translate(0f, 0f));    // transform 是覆盖式，松手归零
        if (moved) {
            placeAt(curX, curY);                // 落一次真正的布局位置
            if (onMoved != null) {
                onMoved.accept(new int[]{curX, curY});
            }
        }
    }
}
