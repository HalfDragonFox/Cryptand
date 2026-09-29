/**
 * ===== 波形曲线编辑器（2026-09-14 交互定稿；2026-09-16 加滑块指示）=====
 *
 * 用户定稿的交互：
 * <ul>
 *   <li><b>左键点空白</b> ⇒ 按【横轴坐标】在曲线对应位置<b>增加一个点</b>
 *       （新点落在曲线当前值上，随后可上下拖）；</li>
 *   <li><b>左键点已有点</b> ⇒ 选中；按住拖动 ⇒ <b>只能上下移动</b>
 *       （⚠ 所有点、包括首尾两端，x 都锁定 —— 曲线永远覆盖 [0,1]）；</li>
 *   <li><b>右键点</b> ⇒ 由外部弹出子页面（删除该点 / 重置 / <b>绑定按键与时间</b>）；</li>
 *   <li><b>鼠标进入</b> ⇒ 按横轴坐标预渲染一个<b>半透明预览点</b>。</li>
 * </ul>
 *
 * <h3>滑块指示（2026-09-16 用户要求）</h3>
 * "增加曲线滑块表示目前在曲线哪一块" + "滑块只需要读取进度值即可渲染" +
 * "滑块读取方块缓存的进度值，这个值也是输入输出映射的百分比的值" ⇒
 * 画布只读一个 **进度值（0..1 = 输出百分比）** 画水平指示线 + 右侧圆点，
 * 不持有任何滑块状态（状态在 BE 缓存 / {@link PeripheralTransmissionKeyInput}）。
 *
 * <p>绘制用仓库自有的 {@link com.hdf.cryptand.neoforge.eda.canvas.CanvasSurface} 逐像素完成，
 * 曲线采样直接调 common 算法库的 PCHIP 公式（与服务端求值同一份）。</p>
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

import com.hdf.cryptand.algorithm.curve.CurvePoint;
import com.hdf.cryptand.algorithm.curve.EditableCurve;
import com.hdf.cryptand.neoforge.core.ui.CreateUi;
import com.hdf.cryptand.neoforge.eda.canvas.CanvasSurface;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;

import java.util.List;

public final class PeripheralTransmissionKeyCurveCanvas extends CanvasSurface {

    private final EditableCurve[] model;
    private final Runnable onEdit;
    /** 右键回调（下标；由父界面弹子页面） */
    private final java.util.function.IntConsumer onContextMenu;

    /** 滑块进度取值器（0..1；NaN = 不显示）—— 用户口径：进度就是输出百分比，读方块缓存即可 */
    private java.util.function.DoubleSupplier progressSource = () -> Double.NaN;
    /** 上一帧进度（只在变化时重绘，避免每帧刷画布） */
    private double lastProgress = Double.NaN;

    /** 每帧钩子（界面用来轮询按键录制、刷新进度等；可为 null） */
    private Runnable tickHook;

    /** 正在拖动的点（-1 = 无） */
    private int dragIndex = -1;
    /** 选中的点（高亮显示；-1 = 无） */
    private int selected = -1;
    /** 鼠标横轴像素位置（-1 = 不在画布内）—— 用于半透明预览点 */
    private float hoverX = -1f;
    /** 命中半径（像素） */
    private static final float HIT_R = 7f;

    private boolean dirty = true;

    public PeripheralTransmissionKeyCurveCanvas(int width, int height, EditableCurve[] model,
                                            Runnable onEdit,
                                            java.util.function.IntConsumer onContextMenu) {
        super(width, height);
        // 自绘画布没有背景贴图，显式允许命中测试（否则鼠标事件可能根本不到这里）
        setAllowHitTest(true);
        this.model = model;
        this.onEdit = onEdit;
        this.onContextMenu = onContextMenu;
        addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown);
        addEventListener(UIEvents.MOUSE_MOVE, this::onMouseMove);
        addEventListener(UIEvents.MOUSE_UP, ev -> dragIndex = -1);
        addEventListener(UIEvents.MOUSE_ENTER, ev -> {
            hoverX = localX(ev);
            dirty = true;
        });
        addEventListener(UIEvents.MOUSE_LEAVE, ev -> {
            hoverX = -1f;
            dragIndex = -1;
            dirty = true;
        });
    }

    /**
     * 设置滑块进度来源（界面每帧读一次；进度变化才重绘）。
     *
     * <p>典型用法：{@code canvas.setProgressSource(() -> be != null ? be.getTargetRatio() : 0)}</p>
     */
    public void setProgressSource(java.util.function.DoubleSupplier source) {
        this.progressSource = source == null ? () -> Double.NaN : source;
        this.dirty = true;
    }

    /**
     * 每帧钩子：界面把"轮询按键录制、刷新状态"等放进这里执行
     * （画布本来就每帧 tick，借它的节拍最省事，也不必依赖额外的 UI 事件）。
     */
    public void setTickHook(Runnable hook) {
        this.tickHook = hook;
    }

    /** 模型被外部替换（例如【重置】）后调用 */
    public void syncFromModel() {
        dragIndex = -1;
        selected = -1;
        dirty = true;
    }

    /** 让画布下一帧重绘（外部改了按键标记等；刻意不叫 markDirty，避免与父类同名方法冲突） */
    public void requestRedraw() {
        dirty = true;
    }

    @Override
    public void screenTick() {
        super.screenTick();
        // 每帧钩子（按键录制轮询等）——先跑，它可能又改了需要重绘的内容
        if (tickHook != null) {
            try {
                tickHook.run();
            } catch (Throwable ignored) {
            }
        }
        // 进度变化 ⇒ 重绘（滑块推进时才刷新，静止时不刷）
        final double p = progressSource.getAsDouble();
        if (!Double.isNaN(p) && Math.abs(p - lastProgress) > 1.0E-4) {
            lastProgress = p;
            dirty = true;
        }
        if (dirty) {
            redraw();
            dirty = false;
        }
    }

    // ==================== 交互 ====================

    private void onMouseDown(UIEvent ev) {
        final float mx = localX(ev);
        final float my = localY(ev);
        int hit = hitTest(mx, my);
        if (ev.button == 1) {
            // 右键 ⇒ 交给父界面弹子页面（删除 / 重置 / 绑定按键与时间）
            if (hit >= 0) {
                selected = hit;
                dirty = true;
                onContextMenu.accept(hit);
            }
            return;
        }
        if (hit >= 0) {
            // 命中已有点 ⇒ 选中 + 进入拖动
            selected = hit;
            dragIndex = hit;
            dirty = true;
            return;
        }
        // 空白 ⇒ 按横轴坐标在曲线上加点（y 取曲线当前值，视觉上"点落在曲线上"）
        float t = toT(mx);
        double y = model[0].apply(t);
        model[0] = model[0].withPoint(new CurvePoint(t, y));
        afterEdit();
        // 新点即选中（方便立刻上下微调）
        selected = nearestIndex(t);
        dirty = true;
    }

    private void onMouseMove(UIEvent ev) {
        final float mx = localX(ev);
        final float my = localY(ev);
        hoverX = mx;
        if (dragIndex >= 0) {
            // ⚠ 只改 y：所有点（含两端）的 x 都锁定
            List<CurvePoint> pts = model[0].points();
            if (dragIndex < pts.size()) {
                CurvePoint old = pts.get(dragIndex);
                model[0] = model[0].withPointMoved(dragIndex, new CurvePoint(old.x(), toY(my)));
                onEdit.run();
            }
        }
        dirty = true;   // 预览点跟着鼠标走
    }

    private void afterEdit() {
        onEdit.run();
        dirty = true;
    }

    // ==================== 坐标换算 ====================

    /**
     * ⚠ UIEvent 的 x/y 是【世界坐标】（javap 实证：UIElement.isMouseOverElement 正是拿
     * {@code getPositionX()} 与传入的 x/y 直接比较 ⇒ 同一坐标系）。元素内坐标 = 世界坐标 - 元素位置。
     * <p>⚠ <b>不要用 {@code getLocalMouse} 做这个换算</b>：它走 {@code getWorldToLocalPose()}
     * 矩阵变换，自绘画布上该矩阵不可靠，返回越界值会让 t 被 clamp 到 0/1，
     * 新点全部叠在端点上 —— 表现为"点了建不出点"（用户实测 2026-09-15）。
     */
    private float localX(UIEvent ev) {
        return ev.x - getPositionX();
    }

    private float localY(UIEvent ev) {
        return ev.y - getPositionY();
    }

    // ⚠ toT/toPx（toY/toPy）必须严格互逆，且与 redraw 的采样基准一致（都用 size-1），
    //   否则点会落在离鼠标 1~2px 的地方。
    private float toT(float px) {
        return Math.max(0f, Math.min(1f, px / Math.max(1, width() - 1)));
    }

    private float toY(float py) {
        return Math.max(0f, Math.min(1f, 1f - py / Math.max(1, height() - 1)));
    }

    private float toPx(float t) {
        return t * Math.max(1, width() - 1);
    }

    private float toPy(float y) {
        return (1f - y) * Math.max(1, height() - 1);
    }

    /** 命中最近的控制点；没有则 -1 */
    private int hitTest(float px, float py) {
        List<CurvePoint> pts = model[0].points();
        int best = -1;
        float bestD = HIT_R * HIT_R;
        for (int i = 0; i < pts.size(); i++) {
            float dx = px - toPx((float) pts.get(i).x());
            float dy = py - toPy((float) pts.get(i).y());
            float d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 按下标找最接近给定 t 的点（加点后用于选中） */
    private int nearestIndex(float t) {
        List<CurvePoint> pts = model[0].points();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            float d = Math.abs((float) pts.get(i).x() - t);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ==================== 绘制 ====================

    private void redraw() {
        int w = width();
        int h = height();
        clear(0xFF131110);
        // Create 风格：内凹细边框（与面板/卡片的黄铜描边呼应，让画布像一块嵌进面板的刻度板）
        drawRect(0, 0, w - 1, h - 1, CreateUi.BRASS_DARK);
        // 网格 4×4
        for (int i = 1; i < 4; i++) {
            drawVLine(w * i / 4, 0, h - 1, 0xFF2A2521);
            drawHLine(0, w - 1, h * i / 4, 0xFF2A2521);
        }
        // 对角参考线（默认两点线性的位置）
        drawLine(0, h - 1, w - 1, 0, 0xFF312A22);

        // 曲线：按 PCHIP 公式采样成折线（铺满整个画布宽度）
        int prevX = 0;
        int prevY = Math.round(toPy((float) model[0].apply(0f)));
        for (int px = 1; px < w; px++) {
            int py = Math.round(toPy((float) model[0].apply(px / (float) (w - 1))));
            drawLine(prevX, prevY, px, py, CreateUi.BRASS);
            prevX = px;
            prevY = py;
        }

        // 控制点：首尾端点更大更亮（锁定在 x=0 / x=1）
        List<CurvePoint> pts = model[0].points();
        for (int i = 0; i < pts.size(); i++) {
            CurvePoint p = pts.get(i);
            int cx = Math.round(toPx((float) p.x()));
            int cy = Math.round(toPy((float) p.y()));
            boolean endpoint = i == 0 || i == pts.size() - 1;
            boolean sel = i == selected;
            int r = sel ? 5 : (endpoint ? 4 : 3);
            drawCircle(cx, cy, r, 0xFF1A1613);
            drawCircle(cx, cy, r - 1, endpoint || sel ? 0xFFC6A15B : 0xFFE9E2D0);
        }

        // ===== 滑块指示（只读进度值）：当前进度 = 输出百分比 = 0..1 ⇒ 画水平线 + 右侧圆点 =====
        final double progress = progressSource.getAsDouble();
        if (!Double.isNaN(progress)) {
            final double clamped = Math.max(0.0, Math.min(1.0, progress));
            final int py = Math.round(toPy((float) clamped));
            drawHLine(0, w - 1, py, 0x88FFD24A);            // 进度水平线（半透明金黄）
            drawHLine(0, w - 1, py + 1, 0x33FFD24A);        // 加粗一像素，暗底上更清楚
            drawCircle(w - 6, py, 4, 0xFF1A1613);
            drawCircle(w - 6, py, 3, 0xFFFFD24A);           // 右侧圆点标记
            // 左侧小刻度：便于一眼看出进度大概在几成
            drawVLine(2, py, Math.min(h - 1, py + 6), 0xFFFFD24A);
        }

        // 半透明预览点：鼠标横轴处、落在曲线上 —— 提示"点这里会在曲线的这一段加点"
        if (hoverX >= 0f && hoverX <= w) {
            float t = toT(hoverX);
            int px = Math.round(toPx(t));
            int py = Math.round(toPy((float) model[0].apply(t)));
            drawCircle(px, py, 4, 0x66FFFFFF);
            drawCircle(px, py, 2, 0x99C6A15B);
            drawVLine(px, 0, h - 1, 0x33C6A15B);
        }
        markDirty();
    }
}
