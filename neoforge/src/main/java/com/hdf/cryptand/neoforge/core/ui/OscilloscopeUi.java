/**
 * ===== 示波器放大界面 UI（LDLib2，Micsig 示波器风格） =====
 *
 * 2026-08-21 用户要求：用回 LDLib2；界面参考市面 Micsig 示波器。
 * 布局（自上而下，像 Micsig 平板示波器）：
 *   - 顶部状态栏：RUN● TRIG:AUTO CH1 | 时基/div | 采样率 | 标题
 *   - 中部大波形区：10×8 网格 + CH1(黄=合成) + 频率分量(青/品红/绿...)
 *     on-screen 读数（CH1 V/div、Freq/RMS、时基）
 *   - 测量条：CH1 Vpp/Vmax/Vmin/Freq/RMS/周期（动态刷新）
 *   - 控制行：检测时间(输入+单位+应用) + 软键(暂停/网格/T+/T-/V+/V-)
 *   - 图例：合成 + 各频率分量（Toggle 选择，通道色）
 * 数据来自 OscilloscopeStore（服务端求解回发）。
 * 旧 CC 终端风格原生 Screen 版已弃置（根目录 弃置区/OscilloscopeScreen_vanilla_old.java）。
 */

package com.hdf.cryptand.neoforge.core.ui;

import com.hdf.cryptand.neoforge.eda.canvas.CanvasSurface;
import com.hdf.cryptand.neoforge.core.client.OscilloscopeHud;
import com.hdf.cryptand.neoforge.core.client.OscilloscopeStore;
import com.hdf.cryptand.neoforge.powergrid.creative.OscilloscopeItem;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class OscilloscopeUi {

    /** 时间单位 */
    private static final String[] TIME_UNITS = {"us", "ms", "s"};
    private static final double[] TIME_MULT = {1e-6, 1e-3, 1.0};

    /** Micsig 通道色：CH1=黄（合成），分量从青/品红/绿…开始 */
    private static final int[] MICSIG = {
            0xFFFFFF33, 0xFF00E5FF, 0xFFFF2FA0, 0xFF33FF57,
            0xFFFFA040, 0xFFB066FF, 0xFFFF6B6B, 0xFF7FFFD4
    };

    private static int toneColor(int i) {
        return MICSIG[(i + 1) % MICSIG.length];
    }

    private OscilloscopeUi() {}

    /** 构建示波器放大界面（客户端 UI，无服务端）。Micsig 平板示波器风格：
     *  顶部状态栏 + 大波形区 + 测量条 + 控制行 + 图例。 */
    public static UI build() {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int windowW = Math.max(430, (int) (sw * 0.94));
        int windowH = Math.max(300, (int) (sh * 0.90));

        // 各条固定高度；波形区占满剩余
        int pad = 8, gap = 5;
        int statusH = 20, measureH = 20, controlH = 20, legendH = 22;
        int chartW = windowW - pad * 2;
        int chartH = windowH - pad * 2 - statusH - measureH - controlH - legendH - gap * 4 - 2;

        OscilloscopeChart chart = new OscilloscopeChart(chartW, chartH);
        chart.layout(l -> l.width(chartW).height(chartH).flexShrink(0));
        OscilloscopeStore.tick(); // 保证有最新采样

        // ===== 顶部状态栏（Micsig：RUN● / 触发 / 通道 | 时基 | 采样率 | 标题） =====
        String pos = OscilloscopeItem.probeLabel(OscilloscopeHud.handItem()).getString();
        UIElement statusBar = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(statusH)
                        .gapAll(10).alignItems(AlignItems.CENTER).paddingHorizontal(8))
                .style(s -> s.background(new ColorRectTexture(0xFF12181D)))
                .addChildren(
                        statusText("RUN ●", 0xFF00E676, 12),
                        statusText("TRIG:AUTO", 0xFF90A4AE, 11),
                        statusText("CH1", 0xFFFFD500, 12),
                        spacer(),
                        statusText(formatTimeBase(), 0xFF90A4AE, 11),
                        statusText("采样 20Hz", 0xFF90A4AE, 11),
                        statusText("示波器 — " + pos, 0xFFFFFFFF, 12)
                );

        // ===== 测量条（Micsig 测量读数，动态刷新） =====
        MeasureBar measureBar = new MeasureBar();
        measureBar.layout(l -> l.widthPercent(100).height(measureH).flexShrink(0));

        // ===== 控制行：检测时间 + 软键 =====
        UIElement controlRow = buildControlRow(chart);

        // ===== 图例（合成 + 频率分量，Toggle 选择） =====
        UIElement legend = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(legendH)
                        .gapAll(4).alignItems(AlignItems.CENTER).wrap(FlexWrap.WRAP).paddingHorizontal(8));
        refreshLegend(legend, chart);

        // ===== 窗口：深色背景，纵向排布 =====
        UIElement window = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN).width(windowW).height(windowH)
                        .paddingAll(pad).gapAll(gap))
                .style(s -> s.background(new ColorRectTexture(0xFF0B0F12)))
                .addChildren(statusBar, chart, measureBar, controlRow, legend);

        UIElement root = new UIElement()
                .layout(l -> l.justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER))
                .addChild(window);
        return UI.of(root);
    }

    // =====================================================================
    //  状态栏 / 控制行 / 图例 构件
    // =====================================================================

    private static Label statusText(String text, int color, int size) {
        Label l = new Label();
        l.setText(Component.literal(text));
        l.textStyle(ts -> ts.fontSize(size).textColor(color));
        l.layout(l2 -> l2.flexShrink(0));
        return l;
    }

    private static UIElement spacer() {
        return new UIElement().layout(l -> l.flex(1));
    }

    /** 控制行：检测时间输入 + 单位 + 应用 + 软键按钮（可换行） */
    private static UIElement buildControlRow(OscilloscopeChart chart) {
        double detect = OscilloscopeStore.detectSeconds();
        int unitIdx = 2; // 默认 s
        if (detect > 0 && detect < 1e-3) unitIdx = 0;
        else if (detect < 1.0) unitIdx = 1;
        double displayVal = detect / TIME_MULT[unitIdx];

        TextField timeField = CryptandUi.numberField(formatTime(displayVal));
        timeField.layout(l -> l.width(60).height(18));
        Selector<String> unitSelector = new Selector<>();
        unitSelector.setCandidates(List.of(TIME_UNITS));
        unitSelector.setSelected(TIME_UNITS[unitIdx]);
        unitSelector.layout(l -> l.width(44).height(18));

        Button applyTime = new Button();
        applyTime.setText(Component.translatable("cryptand.menu.apply"));
        applyTime.setOnClick(ev -> applyDetectTime(timeField, unitSelector));
        applyTime.layout(l -> l.width(40).height(18));

        Label detectLabel = new Label();
        detectLabel.setText(Component.translatable("cryptand.menu.detect_time"));
        detectLabel.textStyle(ts -> ts.fontSize(11).textColor(0xFFB0BEC5));
        detectLabel.layout(l -> l.flexShrink(0));

        Label cur = CryptandUi.infoText("当前: " + formatDetect(detect), 0xFF00E676);
        cur.layout(l -> l.flexShrink(0));

        UIElement row = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(20)
                        .gapAll(6).alignItems(AlignItems.CENTER).wrap(FlexWrap.WRAP).paddingHorizontal(8))
                .addChildren(
                        detectLabel, timeField, unitSelector, applyTime, cur,
                        softToggle("暂停", OscilloscopeStore.paused(),
                                on -> OscilloscopeStore.setPaused(on)),
                        softToggle("网格", chart.gridOn(),
                                on -> chart.setGrid(on)),
                        softButton("T+", () -> OscilloscopeStore.setDetectSeconds(
                                clampSec(OscilloscopeStore.detectSeconds() * 2))),
                        softButton("T-", () -> OscilloscopeStore.setDetectSeconds(
                                clampSec(OscilloscopeStore.detectSeconds() / 2))),
                        softButton("V+", () -> chart.setVolScale(chart.volScale() * 1.5)),
                        softButton("V-", () -> chart.setVolScale(chart.volScale() / 1.5))
                );
        return row;
    }

    /** Micsig 软键（深灰底小按钮） */
    private static Button softButton(String text, Runnable action) {
        Button b = new Button();
        b.setText(Component.literal(text));
        b.setOnClick(ev -> action.run());
        b.layout(l -> l.width(42).height(18));
        b.style(s -> s.color(0xFF1E2A35));
        return b;
    }

    /** Micsig 开关软键（激活=绿）。⚠ setOnToggleChanged 参数是 fastutil BooleanConsumer */
    private static Toggle softToggle(String text, boolean on,
                                     it.unimi.dsi.fastutil.booleans.BooleanConsumer onChanged) {
        Toggle t = new Toggle().setText(text).setOn(on).setOnToggleChanged(onChanged);
        t.layout(l -> l.width(46).height(18));
        t.toggleStyle(ts -> ts.markTexture(new ColorRectTexture(0xFF2E7D32))
                .unmarkTexture(new ColorRectTexture(0xFF1E2A35)));
        return t;
    }

    /** 图例：合成（CH1 黄）+ 每频率分量（通道色），Toggle 点击选择 → chart 高亮 */
    private static void refreshLegend(UIElement legend, OscilloscopeChart chart) {
        legend.clearAllChildren();
        List<OscilloscopeStore.Tone> tones = OscilloscopeStore.tones();

        Toggle synth = new Toggle().setText("合成")
                .setOn(chart.selected() == -1)
                .setOnToggleChanged(on -> { if (on) chart.setSelected(-1); });
        synth.toggleStyle(ts -> ts.markTexture(new ColorRectTexture(0xFFFFFF33))
                .unmarkTexture(new ColorRectTexture(0xFF1E2A35)));
        synth.layout(l -> l.height(18));
        legend.addChild(synth);

        for (int i = 0; i < tones.size(); i++) {
            final int idx = i;
            OscilloscopeStore.Tone t = tones.get(i);
            int color = toneColor(i);
            Toggle tg = new Toggle().setText(formatFreq(t.freq) + "Hz")
                    .setOn(chart.selected() == i)
                    .setOnToggleChanged(on -> { if (on) chart.setSelected(idx); });
            tg.toggleStyle(ts -> ts.markTexture(new ColorRectTexture(color))
                    .unmarkTexture(new ColorRectTexture(0xFF1E2A35)));
            tg.layout(l -> l.height(18));
            legend.addChild(tg);
        }
    }

    /** 应用检测时间：解析输入 + 单位 → 写回 Store 与物品 NBT */
    private static void applyDetectTime(TextField field, Selector<String> unit) {
        try {
            double v = Double.parseDouble(field.getValue().trim());
            String u = unit.getValue() == null ? "s" : unit.getValue();
            int idx = 2;
            for (int i = 0; i < TIME_UNITS.length; i++) {
                if (TIME_UNITS[i].equals(u)) { idx = i; break; }
            }
            double seconds = clampSec(v * TIME_MULT[idx]);
            OscilloscopeStore.setDetectSeconds(seconds);
            try {
                OscilloscopeItem.setDetectSeconds(
                        com.hdf.cryptand.neoforge.core.client.OscilloscopeHud.handItem(), seconds);
            } catch (Throwable ignored) {
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static double clampSec(double s) {
        return Math.max(1e-6, Math.min(10.0, s));
    }

    /** 时基（每格时间）：网格横向 10 格 */
    private static String formatTimeBase() {
        double[][] win = OscilloscopeStore.windowSamples();
        double[] ts = win[0];
        double span = ts.length >= 2 ? Math.max(ts[ts.length - 1] - ts[0], 1e-9)
                : OscilloscopeStore.detectSeconds();
        return formatDetect(span / 10.0) + "/div";
    }

    private static String formatTime(double v) {
        if (v == 0) return "0";
        if (Math.abs(v) >= 1000) return String.format("%.1f", v);
        return String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String formatDetect(double seconds) {
        if (seconds >= 1) return String.format("%.2f s", seconds);
        if (seconds >= 1e-3) return String.format("%.2f ms", seconds * 1e3);
        return String.format("%.2f us", seconds * 1e6);
    }

    private static String formatFreq(double f) {
        if (f >= 1000) return String.format("%.1fk", f / 1000);
        if (f >= 1) return String.format("%.1f", f);
        return String.format("%.0f", f);
    }

    // =====================================================================
    //  波形区（Micsig：10×8 网格 + CH1 黄合成 + 分量通道色）
    // =====================================================================
    public static final class OscilloscopeChart extends CanvasSurface {
        /** 选中波形：-1 = 合成（CH1）；0..n-1 = 对应频率分量 */
        private volatile int selected = -1;
        /** 垂直缩放倍率（V+/V-，默认 1） */
        private volatile double volScale = 1.0;
        /** 网格显示 */
        private volatile boolean gridOn = true;
        /** 上次重绘对应的采样 tick（波形每 tick 才变化 → 20Hz 重绘） */
        private long lastDrawTick = Long.MIN_VALUE;

        public OscilloscopeChart(int w, int h) {
            super(w, h, false, true); // skipLayout：由 build() 绝对尺寸控制
        }

        public int selected() { return selected; }

        public void setSelected(int sel) {
            selected = sel;
            redraw();
            uploadNow();
        }

        public double volScale() { return volScale; }

        public void setVolScale(double s) {
            volScale = Math.max(0.1, Math.min(20.0, s));
            redraw();
            uploadNow();
        }

        public boolean gridOn() { return gridOn; }

        public void setGrid(boolean on) {
            gridOn = on;
            redraw();
            uploadNow();
        }

        @Override
        public void screenTick() {
            long st = OscilloscopeStore.lastSampleTick();
            if (st != lastDrawTick) {
                lastDrawTick = st;
                redraw();
            }
            super.screenTick();
        }

        /** on-screen 读数（Micsig 风格，画布原点 ox,oy 上叠加） */
        @Override
        protected void renderExtraAt(GUIContext ctx, int ox, int oy) {
            GuiGraphics g = ctx.graphics;
            Font font = Minecraft.getInstance().font;
            int w = width(), h = height();
            if (OscilloscopeStore.tones().isEmpty()) {
                g.drawCenteredString(font, "未检测到波形（搭线到电网端点）",
                        ox + w / 2, oy + h / 2 - 4, 0xFF90A4AE);
                return;
            }
            double f = OscilloscopeStore.dominantFrequency();
            double rms = OscilloscopeStore.compositeRms();
            // 通道标签（左上，CH1 黄）
            g.drawString(font, "CH1  1.00V/div  DC", ox + 4, oy + 3, 0xFFFFD500, false);
            // 读数（右上）
            String rd = String.format("Freq %.1fHz  RMS %.3fV", f, rms);
            g.drawString(font, rd, ox + w - font.width(rd) - 4, oy + 3, 0xFFB0BEC5, false);
            // 时基（左下）
            g.drawString(font, "1.00ms/div", ox + 4, oy + h - 11, 0xFF90A4AE, false);
            // 触发标记（右下）
            g.drawString(font, "AC", ox + w - 20, oy + h - 11, 0xFF546E7A, false);
        }

        /** 光栅化网格 + 分量曲线 + 采样序列到像素缓冲（纯内存写，无 draw call） */
        private void redraw() {
            int w = width(), h = height();
            clear(0xFF0A0F14);
            drawRect(0, 0, w - 1, h - 1, 0xFF22303A);
            if (gridOn) {
                // Micsig：10(横)×8(纵) 格，中轴更亮
                for (int i = 1; i < 8; i++) drawHLine(0, w - 1, h * i / 8, 0xFF1E2A35);
                for (int i = 1; i < 10; i++) drawVLine(w * i / 10, 0, h - 1, 0xFF1E2A35);
                drawHLine(0, w - 1, h / 2, 0xFF2E4250);
                drawVLine(w / 2, 0, h - 1, 0xFF2E4250);
            }

            List<OscilloscopeStore.Tone> tones = OscilloscopeStore.tones();
            if (tones.isEmpty()) { markDirty(); return; }

            double[][] win = OscilloscopeStore.windowSamples();
            double[] ts = win[0], vs = win[1];
            double t0 = ts.length >= 2 ? ts[0] : 0;
            double tEnd = ts.length >= 2 ? ts[ts.length - 1] : 1.0;
            double span = Math.max(tEnd - t0, 1e-9);

            double maxPeak = OscilloscopeStore.compositeRms() * 2.5;
            for (OscilloscopeStore.Tone t : tones) maxPeak = Math.max(maxPeak, t.amp * 1.1);
            if (maxPeak <= 1e-6) maxPeak = 1.0;
            double vScale = (h / 2.0) / maxPeak * volScale;
            int mid = h / 2;

            // 每频率分量解析曲线（Micsig 通道色，列级竖段；选中加粗一列）
            int samples = Math.min(Math.max(60, w), 300);
            for (int i = 0; i < tones.size(); i++) {
                OscilloscopeStore.Tone t = tones.get(i);
                int color = toneColor(i);
                int py = Integer.MIN_VALUE;
                for (int s = 0; s <= samples; s++) {
                    double tt = t0 + span * s / samples;
                    double v = t.valueAt(tt);
                    int x = s * w / samples;
                    int y = mid - (int) Math.round(v * vScale);
                    if (py != Integer.MIN_VALUE) {
                        drawVLine(x, Math.min(py, y), Math.max(py, y), color);
                        if (selected == i) drawVLine(x - 1, Math.min(py, y), Math.max(py, y), color);
                    }
                    py = y;
                }
            }

            // 采样序列（合成 = CH1 黄，最后画）
            if (ts.length >= 2) {
                int py = Integer.MIN_VALUE;
                for (int i = 0; i < ts.length; i++) {
                    int x = (int) Math.round((ts[i] - t0) / span * (w - 1));
                    int y = mid - (int) Math.round(vs[i] * vScale);
                    if (py != Integer.MIN_VALUE) {
                        drawVLine(x, Math.min(py, y), Math.max(py, y), 0xFFFFFF33);
                        if (selected == -1) drawVLine(x - 1, Math.min(py, y), Math.max(py, y), 0xFFFFFF33);
                    }
                    py = y;
                }
            }
            markDirty();
        }
    }

    // =====================================================================
    //  测量条（Micsig：CH1 的 Vpp/Vmax/Vmin/Freq/RMS/周期，动态刷新）
    // =====================================================================
    public static final class MeasureBar extends UIElement {
        @Override
        public void drawContents(GUIContext ctx) {
            super.drawContents(ctx);
            GuiGraphics g = ctx.graphics;
            Font font = Minecraft.getInstance().font;
            int x = (int) getPositionX() + 8;
            int y = (int) getPositionY() + 5;

            double rms = OscilloscopeStore.compositeRms();
            double f = OscilloscopeStore.dominantFrequency();
            double[][] win = OscilloscopeStore.windowSamples();
            double[] vs = win[1];
            double vmax = 0, vmin = 0;
            if (vs.length > 0) {
                vmax = vs[0];
                vmin = vs[0];
                for (double v : vs) {
                    if (v > vmax) vmax = v;
                    if (v < vmin) vmin = v;
                }
            }
            double vpp = vmax - vmin;
            double period = f > 0 ? 1.0 / f : 0;

            g.drawString(font, "CH1", x, y, 0xFFFFD500, false);
            int cx = x + 34;
            g.drawString(font, "Vpp=" + String.format("%.3fV", vpp), cx, y, 0xFFB0BEC5, false); cx += 96;
            g.drawString(font, "Vmax=" + String.format("%.3fV", vmax), cx, y, 0xFFB0BEC5, false); cx += 96;
            g.drawString(font, "Vmin=" + String.format("%.3fV", vmin), cx, y, 0xFFB0BEC5, false); cx += 96;
            g.drawString(font, "Freq=" + (f >= 1000 ? String.format("%.2fkHz", f / 1000) : String.format("%.1fHz", f)),
                    cx, y, 0xFFB0BEC5, false); cx += 96;
            g.drawString(font, "RMS=" + String.format("%.3fV", rms), cx, y, 0xFFB0BEC5, false); cx += 80;
            g.drawString(font, "T=" + (period > 0 ? formatDetect(period) : "--"), cx, y, 0xFFB0BEC5, false);
        }
    }
}
