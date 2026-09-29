package com.hdf.cryptand.neoforge.ui.ldlib;

import com.hdf.cryptand.soc.link.HwCanvasLayout;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.GraphView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ===== EDA 视口（真实 EDA 设计层，2026-09-29 重写）=====
 *
 * <p>用 LDLib2 的通用 {@link GraphView} 做视口：自带网格（随视口移动/缩放）+ 平移 + 滚轮缩放 + overflow 裁剪，
 * 世界坐标内容挂在 {@code contentRoot} 上 ⇒ 器件能拖到视口外，不被夹住（用户定案：像 AD 那样）。</p>
 *
 * <p>分层：{@code wireLayer}（不参与命中）→ 器件元素（每个器件自带引脚点子元素）。
 * 线与点**都不缓存坐标**：线每帧用 {@code getWorldMouse} 现取端点屏幕坐标（官方 WireElement 同款做法），
 * 点用相对定位挂在器件下 ⇒ 拖动/缩放/平移全部自动跟随，不需要刷新。</p>
 */
public class DesignDiagramView extends GraphView {

    private final List<DesignNodeElement> nodeElements = new ArrayList<>();
    private final List<DesignDotElement> dotElements = new ArrayList<>();
    /** 全部引脚点（EDA 坐标快照）：命中判定用。 */
    private final List<HwCanvasLayout.Dot> allDots = new ArrayList<>();
    /** 拖动中的 EDA 增量（器件 key → {dx,dy}）：线层与命中都靠它跟手。 */
    private final Map<String, int[]> dragDelta = new HashMap<>();

    private DesignWireLayer wireLayer;
    private HwCanvasLayout.Dot wireFrom;
    private boolean pendingFit;
    private boolean fitted;

    private BiConsumer<HwCanvasLayout.Dot, HwCanvasLayout.Dot> onLink;
    private Consumer<HwCanvasLayout.Wire> onUnlink;
    private BiConsumer<String, int[]> onMoveNode;
    private Runnable onDragging;
    private Consumer<HwCanvasLayout.Dot> onInspect;

    public DesignDiagramView(int width, int height) {
        layout(l -> l.width(width).height(height));
        setOverflowVisible(false);
        graphViewStyle(s -> s.gridSize(16f)
                .gridLineColor(0xFF1A2830).gridAccentColor(0xFF2C4450).gridMinPixels(10f));
        addEventListener(UIEvents.MOUSE_DOWN, this::onSelfDown);            // 右键断线（空白/线上）
        addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::onDragUpdate);  // 拉线预览
        addEventListener(UIEvents.DRAG_END, this::onDragEnd);
    }

    // ==================== 内容装配 ====================

    /** 铺内容（引脚点作为器件子元素，线挂在不参与命中的线层）。 */
    public void setContent(List<HwCanvasLayout.Node> nodes, List<HwCanvasLayout.Dot> dots,
                           List<HwCanvasLayout.Wire> wires, Map<String, ItemStack> icons) {
        clearAllContentChildren();
        nodeElements.clear();
        dotElements.clear();

        wireFrom = null;

        allDots.clear();
        if (dots != null) {
            allDots.addAll(dots);
        }
        // 线层在最底层：所有连线按 **EDA 坐标** 画，屏幕换算交给 GraphView 的 transform
        wireLayer = new DesignWireLayer();
        wireLayer.setContent(nodes == null ? List.of() : nodes,
                dots == null ? List.of() : dots, wires == null ? List.of() : wires);
        addContentChild(wireLayer);

        if (nodes != null) {
            for (final HwCanvasLayout.Node n : nodes) {
                final List<HwCanvasLayout.Dot> mine = new ArrayList<>();
                if (dots != null) {
                    for (final HwCanvasLayout.Dot d : dots) {
                        if (d.nodeKey().equals(n.key())) {
                            mine.add(d);
                        }
                    }
                }
                final DesignNodeElement el = new DesignNodeElement(n,
                        icons == null ? null : icons.get(n.key()), this, mine);
                el.onDragging(onDragging);
                if (onMoveNode != null) {
                    el.onMoved(xy -> {
                        dragDelta.remove(n.key());
                        onMoveNode.accept(n.key(), xy);
                    });
                }
                el.onDragDelta(d -> {
                    dragDelta.put(n.key(), d);
                    wireLayer.moveNode(n.key(), n.x() + d[0], n.y() + d[1]);
                });
                nodeElements.add(el);
                addContentChild(el);
                for (final DesignDotElement de : el.dotElements()) {
                    de.onPressed(this::startWire);
                    de.onRightPressed(onInspect);
                    dotElements.add(de);
                }
            }
        }

        if (!fitted && !nodeElements.isEmpty()) {
            pendingFit = true;   // taffy 要到本帧 renderFrame 才算尺寸 ⇒ 下一 tick 再适配视口
        }
    }

    @Override
    public void screenTick() {
        if (pendingFit) {
            pendingFit = false;
            fitted = true;
            fitToChildren(24f, 0.6f);
        }
        super.screenTick();
    }

    // ==================== 回调注册 ====================

    public DesignDiagramView onLink(BiConsumer<HwCanvasLayout.Dot, HwCanvasLayout.Dot> c) {
        this.onLink = c;
        return this;
    }

    public DesignDiagramView onUnlink(Consumer<HwCanvasLayout.Wire> c) {
        this.onUnlink = c;
        return this;
    }

    public DesignDiagramView onMoveNode(BiConsumer<String, int[]> c) {
        this.onMoveNode = c;
        return this;
    }

    public DesignDiagramView onDragging(Runnable r) {
        this.onDragging = r;
        return this;
    }

    public DesignDiagramView onInspect(Consumer<HwCanvasLayout.Dot> c) {
        this.onInspect = c;
        return this;
    }

    // ==================== 交互 ====================

    private void startWire(HwCanvasLayout.Dot dot) {
        wireFrom = dot;
    }

    private void onDragUpdate(UIEvent ev) {
        if (ev.dragHandler == null || !(ev.dragHandler.draggingObject instanceof DesignDotElement.DotDrag dd)) {
            return;
        }
        wireFrom = dd.dot();
        final DesignDotElement from = dotElement(dd.dot().nodeKey(), dd.dot().portId());
        if (from == null) {
            return;
        }
        final float[] world = worldOf(ev.x, ev.y);
        wireLayer.setPreview(dd.dot(), Math.round(world[0]), Math.round(world[1]));
    }

    private void onDragEnd(UIEvent ev) {
        wireLayer.clearPreview();
        final HwCanvasLayout.Dot target = dotAt(ev.x, ev.y);
        if (wireFrom != null && target != null && onLink != null
                && !(target.nodeKey().equals(wireFrom.nodeKey()) && target.portId().equals(wireFrom.portId()))) {
            onLink.accept(wireFrom, target);
        }
        wireFrom = null;
    }

    private void onSelfDown(UIEvent ev) {
        if (ev.button != 2) {
            return;
        }
        final float[] world = worldOf(ev.x, ev.y);
        final float tol = 6f / Math.max(0.0001f, getScale());
        final HwCanvasLayout.Wire hit = wireLayer.wireAt(Math.round(world[0]), Math.round(world[1]), tol);
        if (hit != null) {
            if (onUnlink != null) {
                onUnlink.accept(hit);
            }
            ev.stopPropagation();   // 右键点在线上 = 断线，不要触发视口平移
        }
    }

    // ==================== 命中（屏幕空间，实时取端点，不用快照） ====================

    private DesignDotElement dotElement(String nodeKey, String portId) {
        for (final DesignDotElement de : dotElements) {
            if (de.dot().nodeKey().equals(nodeKey) && de.dot().portId().equals(portId)) {
                return de;
            }
        }
        return null;
    }

    /**
     * 屏幕坐标 → **EDA 世界坐标**。
     *
     * <p>这是"输入方向"的唯一换算入口；"渲染方向"的换算由 GraphView 的 contentRoot transform 自动完成
     * （用户定案：前端只存坐标数据，后端渲染引擎自动按屏幕情况渲染）。</p>
     */
    private float[] worldOf(float screenX, float screenY) {
        final var local = getLocalMouse(screenX, screenY);
        final float inv = 1f / Math.max(0.0001f, getScale());
        return new float[]{
                getOffsetX() + (local.x - getContentX()) * inv,
                getOffsetY() + (local.y - getContentY()) * inv};
    }

    /** 命中引脚点：**EDA 坐标**里判定（容差按屏幕像素 ÷ scale），拖动中的偏移也算进来。 */
    public HwCanvasLayout.Dot dotAt(float screenX, float screenY) {
        final float[] w = worldOf(screenX, screenY);
        final float tol = 12f / Math.max(0.0001f, getScale());
        HwCanvasLayout.Dot best = null;
        float bestD2 = tol * tol;
        for (final HwCanvasLayout.Dot d : allDots) {
            final int[] delta = dragDelta.get(d.nodeKey());
            final float ex = d.x() + (delta == null ? 0 : delta[0]);
            final float ey = d.y() + (delta == null ? 0 : delta[1]);
            final float dx = ex - w[0];
            final float dy = ey - w[1];
            final float d2 = dx * dx + dy * dy;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = d;
            }
        }
        return best;
    }
}
