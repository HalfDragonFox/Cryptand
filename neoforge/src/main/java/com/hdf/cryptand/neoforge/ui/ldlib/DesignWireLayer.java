package com.hdf.cryptand.neoforge.ui.ldlib;

import com.hdf.cryptand.soc.link.HwCanvasLayout;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== EDA 线层（2026-09-29）=====
 *
 * <p><b>全部用 EDA 世界坐标</b>（0,0 起），不做任何屏幕换算 —— 屏幕变换由 GraphView 的
 * contentRoot transform 自动完成（参考 LibreCAD：世界坐标 + 视图变换链）。</p>
 *
 * <p>本层是所有连线的唯一绘制者：它不吃鼠标（{@code setAllowHitTest(false)}，否则元素 AABB 会
 * 吞掉画布的平移），命中由 {@link DesignDiagramView} 在 EDA 坐标里判定。</p>
 */
public class DesignWireLayer extends UIElement {

    /** 器件当前 EDA 位置（拖动中会带上临时偏移，所以线能跟着动）。 */
    private final Map<String, float[]> nodePos = new LinkedHashMap<>();
    /** 每个器件的引脚点（EDA 坐标，随器件位置平移）。 */
    private final Map<String, List<HwCanvasLayout.Dot>> nodeDots = new LinkedHashMap<>();
    private final List<HwCanvasLayout.Wire> wires = new ArrayList<>();

    private HwCanvasLayout.Wire hoverWire;
    private HwCanvasLayout.Dot previewFrom;
    private int previewX, previewY;

    public DesignWireLayer() {
        layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .width(4096).height(4096));
        setAllowHitTest(false);   // 只画不点
    }

    /** 铺内容：器件 EDA 位置 + 每个器件的引脚点 + 连线。 */
    public void setContent(List<HwCanvasLayout.Node> nodes, List<HwCanvasLayout.Dot> dots,
                           List<HwCanvasLayout.Wire> wires) {
        nodePos.clear();
        nodeDots.clear();
        for (final HwCanvasLayout.Node n : nodes) {
            nodePos.put(n.key(), new float[]{n.x(), n.y()});
        }
        for (final HwCanvasLayout.Dot d : dots) {
            nodeDots.computeIfAbsent(d.nodeKey(), k -> new ArrayList<>()).add(d);
        }
        this.wires.clear();
        this.wires.addAll(wires);
    }

    /** 拖动中：把某个器件临时挪到 (x,y)（EDA 坐标），线立刻跟着走。 */
    public void moveNode(String key, float x, float y) {
        final float[] p = nodePos.get(key);
        if (p == null) {
            nodePos.put(key, new float[]{x, y});
            return;
        }
        p[0] = x;
        p[1] = y;
    }

    public void setHover(HwCanvasLayout.Wire wire) {
        this.hoverWire = wire;
    }

    public void setPreview(HwCanvasLayout.Dot from, int x, int y) {
        this.previewFrom = from;
        this.previewX = x;
        this.previewY = y;
    }

    public void clearPreview() {
        this.previewFrom = null;
    }

    /** 某个端点在 EDA 坐标里的位置（器件位置 + 端口相对偏移）。取不到返回 null。 */
    public int[] pointOf(String nodeKey, String portId) {
        final float[] origin = nodePos.get(nodeKey);
        final List<HwCanvasLayout.Dot> list = nodeDots.get(nodeKey);
        if (origin == null || list == null) {
            return null;
        }
        for (final HwCanvasLayout.Dot d : list) {
            if (d.portId().equals(portId)) {
                return new int[]{Math.round(d.x() + origin[0]), Math.round(d.y() + origin[1])};
            }
        }
        return null;
    }

    /** EDA 坐标命中一条线（容差 = EDA 单位）。 */
    public HwCanvasLayout.Wire wireAt(int x, int y, float tol) {
        for (final HwCanvasLayout.Wire w : wires) {
            final int[] a = pointOf(w.aNode(), w.aPort());
            final int[] b = pointOf(w.bNode(), w.bPort());
            if (a == null || b == null) {
                continue;
            }
            for (final int[] s : HwCanvasLayout.route(a[0], a[1], b[0], b[1])) {
                if (HwCanvasLayout.segmentDistance(s[0], s[1], s[2], s[3], x, y) <= tol) {
                    return w;
                }
            }
        }
        return null;
    }

    @Override
    public void drawContents(GUIContext ctx) {
        for (final HwCanvasLayout.Wire wire : wires) {
            final int[] a = pointOf(wire.aNode(), wire.aPort());
            final int[] b = pointOf(wire.bNode(), wire.bPort());
            if (a == null || b == null) {
                continue;
            }
            final int color = wire.kind() == null ? 0xFF808080 : wire.kind().argb();
            final boolean hot = hoverWire != null && sameWire(hoverWire, wire);
            drawRoute(ctx, a[0], a[1], b[0], b[1], hot ? 0xFFFFFFFF : color);
        }
        if (previewFrom != null) {
            drawRoute(ctx, Math.round(previewFrom.x()), Math.round(previewFrom.y()),
                    previewX, previewY, 0xFFE8F4FF);
        }
    }

    private void drawRoute(GUIContext ctx, int x0, int y0, int x1, int y1, int argb) {
        for (final int[] s : HwCanvasLayout.route(x0, y0, x1, y1)) {
            ctx.graphics.fill(Math.min(s[0], s[2]), Math.min(s[1], s[3]),
                    Math.max(s[0], s[2]) + 1, Math.max(s[1], s[3]) + 1, argb);
        }
    }

    private static boolean sameWire(HwCanvasLayout.Wire a, HwCanvasLayout.Wire b) {
        return a.aNode().equals(b.aNode()) && a.aPort().equals(b.aPort())
                && a.bNode().equals(b.bNode()) && a.bPort().equals(b.bPort());
    }
}
