/**
 * ===== LDLib2 绘制空间扩展:CanvasSurface(像素画布,2026-08-15) =====
 *
 * 类 CC-Tweaked 的「固定网格 + 批量缓冲 + 脏更新」思路,为 LDLib2 UI 提供
 * 支持大量绘制的自定义绘制空间:
 *   - 所有图元(setPixel/fillRect/drawLine/drawRect/plotValues)光栅化进
 *     NativeImage 像素缓冲(纯 CPU 内存写,不产生任何 draw call);
 *   - 脏标记按 tick(≤20Hz)批量上传 DynamicTexture;
 *   - 每帧渲染只做一次 blit(1 个 quad),与内容复杂度、帧率完全解耦;
 *   - 类 CC 的固定像素网格:分辨率固定,绘制量再多也恒定一次上传 + 一次 blit。
 *
 * 生命周期:构造时注册到 TextureManager(资源重载自动释放),UI 移除
 * (onRemoved)时 release 释放纹理与像素缓冲。
 *
 * 线程约定:所有绘制与上传均在客户端主线程(GUI/渲染线程)进行。
 *
 * 用法示例(LDLib2 UI 内):
 *   CanvasSurface canvas = new CanvasSurface(520, 300);
 *   canvas.clear(0xFF0A0E12);
 *   canvas.drawRect(0, 0, 519, 299, 0xFF22303A);
 *   canvas.plotValues(4, 14, 512, 272, samples, -10.0, 10.0, 0xFFFFFFFF);
 *   // 之后每 tick 更新数据 → markDirty(),组件自动上传;渲染每帧自动 blit。
 */
package com.hdf.cryptand.neoforge.eda.canvas;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class CanvasSurface extends UIElement {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** 诊断计数（EDA 画布渲染定位，测试后删除，2026-08-17） */
    static int debugCount;

    /** 文字叠加条目（像素坐标，随画布原点定位；每帧用原版 Font 批量绘制） */
    public static final class TextEntry {
        public final float x, y;
        public final String text;
        public final int color;
        public final float scale;

        public TextEntry(float x, float y, String text, int color, float scale) {
            this.x = x; this.y = y; this.text = text; this.color = color; this.scale = scale;
        }
    }

    private final int width;
    private final int height;
    private final NativeImage image;
    private final DynamicTexture texture;
    private final ResourceLocation textureId;

    private volatile boolean dirty = true;
    private boolean disposed;
    /** 最近一次上传的客户端 tick(兜底上传节流:每 tick 最多一次) */
    private long lastUploadTick = Long.MIN_VALUE;
    /** 文字叠加层(字形无法光栅化进像素缓冲,用原版 Font 每帧批量绘制) */
    private final List<TextEntry> texts = new CopyOnWriteArrayList<>();

    // ===== 显示区域记录（2026-08-17：绕开 LDLib2 绝对定位下布局缓存不可靠的问题） =====
    /** 手动记录的显示区域（相对 UI 坐标系）；设置后 drawContents 优先用它渲染 */
    private int renderX, renderY, renderW, renderH;
    private boolean hasRenderRegion;

    public CanvasSurface(int width, int height) {
        this(width, height, false, false);
    }

    /**
     * @param fill true = 弹性填充模式：内部分辨率固定 width×height 像素缓冲，
     *             但 UI 布局用 flex(1) 占满父容器，显示时拉伸到布局区域
     *             （EDA 全屏画布用，2026-08-17）。
     */
    public CanvasSurface(int width, int height, boolean fill) {
        this(width, height, fill, false);
    }

    /**
     * @param skipLayout true = 构造时不设置任何 layout 尺寸/规则（调用方通过
     *                   applyLayout/绝对定位完全控制；EDA 画布用，避免与
     *                   LDLib2 绝对定位规则冲突导致布局缓存异常，2026-08-17）。
     */
    public CanvasSurface(int width, int height, boolean fill, boolean skipLayout) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.image = new NativeImage(this.width, this.height, false);
        this.texture = new DynamicTexture(this.image);
        this.textureId = ResourceLocation.fromNamespaceAndPath("cryptand",
                "canvas_" + COUNTER.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(this.textureId, this.texture);
        if (!skipLayout) {
            if (fill) {
                layout(l -> l.flex(1).flexShrink(0));
            } else {
                layout(l -> l.width(this.width).height(this.height));
            }
        }
    }

    /** 记录显示区域（相对 UI 坐标系）；drawContents 优先用它，绕开 LDLib2 布局缓存 */
    public CanvasSurface setRenderRegion(int x, int y, int w, int h) {
        this.renderX = x;
        this.renderY = y;
        this.renderW = Math.max(1, w);
        this.renderH = Math.max(1, h);
        this.hasRenderRegion = true;
        return this;
    }

    /** 导出当前像素缓冲为 PNG（EDA 工具栏 PNG 按钮，2026-08-17） */
    public void savePng(java.nio.file.Path p) throws java.io.IOException {
        uploadNow();
        image.writeToFile(p);
    }

    // ==================== 像素绘制 API(纯内存写,自动脏标记) ====================

    public int width() { return width; }

    public int height() { return height; }

    public CanvasSurface clear(int argb) {
        return fillRect(0, 0, width - 1, height - 1, argb);
    }

    public CanvasSurface setPixel(int x, int y, int argb) {
        if (disposed || x < 0 || y < 0 || x >= width || y >= height) return this;
        image.setPixelRGBA(x, y, toAbgr(argb));
        dirty = true;
        return this;
    }

    public CanvasSurface fillRect(int x0, int y0, int x1, int y1, int argb) {
        if (disposed) return this;
        int sx = clamp(x0, 0, width - 1), ex = clamp(x1, 0, width - 1);
        int sy = clamp(y0, 0, height - 1), ey = clamp(y1, 0, height - 1);
        if (sx > ex || sy > ey) return this;
        int abgr = toAbgr(argb);
        for (int y = sy; y <= ey; y++) {
            for (int x = sx; x <= ex; x++) {
                image.setPixelRGBA(x, y, abgr);
            }
        }
        dirty = true;
        return this;
    }

    public CanvasSurface drawHLine(int x0, int x1, int y, int argb) {
        return fillRect(Math.min(x0, x1), y, Math.max(x0, x1), y, argb);
    }

    public CanvasSurface drawVLine(int x, int y0, int y1, int argb) {
        return fillRect(x, Math.min(y0, y1), x, Math.max(y0, y1), argb);
    }

    /** 矩形边框 */
    public CanvasSurface drawRect(int x0, int y0, int x1, int y1, int argb) {
        drawHLine(x0, x1, y0, argb);
        drawHLine(x0, x1, y1, argb);
        drawVLine(x0, y0, y1, argb);
        return drawVLine(x1, y0, y1, argb);
    }

    /** Bresenham 直线(写入像素缓冲,无 draw call) */
    public CanvasSurface drawLine(int x0, int y0, int x1, int y1, int argb) {
        if (disposed) return this;
        int abgr = toAbgr(argb);
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy, x = x0, y = y0;
        while (true) {
            if (x >= 0 && y >= 0 && x < width && y < height) {
                image.setPixelRGBA(x, y, abgr);
            }
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
        dirty = true;
        return this;
    }

    /**
     * 曲线/采样序列绘制:把采样数组映射到指定像素区域(列级绘制,每列至多
     * 一个竖段,vMin/vMax 决定纵向缩放)。适合示波器波形、频谱等大量数据。
     */
    public CanvasSurface plotValues(int px, int py0, int pw, int ph,
                                    double[] values, double vMin, double vMax, int argb) {
        if (disposed || values == null || values.length < 2 || pw < 1 || ph < 1) return this;
        double range = vMax - vMin;
        if (Math.abs(range) < 1e-12) range = 1.0;
        int abgr = toAbgr(argb);
        int n = Math.min(values.length, pw); // 每列最多一个点,超出列数自动抽稀
        int prevY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            double v = clamp((values[i] - vMin) / range, 0.0, 1.0); // 0..1
            int x = px + (int) ((double) i / (n - 1) * (pw - 1));
            int y = py0 + ph - 1 - (int) Math.round(v * (ph - 1));
            if (prevY != Integer.MIN_VALUE) {
                drawVLineRaw(x, Math.min(prevY, y), Math.max(prevY, y), abgr);
            }
            prevY = y;
        }
        dirty = true;
        return this;
    }

    /** 内部列竖段写入(不重复置脏,供批量循环使用) */
    private void drawVLineRaw(int x, int y0, int y1, int abgr) {
        if (x < 0 || x >= width) return;
        int sy = clamp(y0, 0, height - 1), ey = clamp(y1, 0, height - 1);
        for (int y = sy; y <= ey; y++) image.setPixelRGBA(x, y, abgr);
    }

    private void setPixelRaw(int x, int y, int abgr) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        image.setPixelRGBA(x, y, abgr);
    }

    /** 中点画圆（边框，EDA 元件/焊盘） */
    public CanvasSurface drawCircle(int cx, int cy, int r, int argb) {
        if (disposed || r < 0) return this;
        int abgr = toAbgr(argb);
        int x = r, y = 0, err = 1 - r;
        while (x >= y) {
            setPixelRaw(cx + x, cy + y, abgr); setPixelRaw(cx + y, cy + x, abgr);
            setPixelRaw(cx - y, cy + x, abgr); setPixelRaw(cx - x, cy + y, abgr);
            setPixelRaw(cx - x, cy - y, abgr); setPixelRaw(cx - y, cy - x, abgr);
            setPixelRaw(cx + y, cy - x, abgr); setPixelRaw(cx + x, cy - y, abgr);
            y++;
            if (err < 0) err += 2 * y + 1;
            else { x--; err += 2 * (y - x) + 1; }
        }
        dirty = true;
        return this;
    }

    /** 折线/多边形（EDA 导线、元件轮廓） */
    public CanvasSurface drawPolygon(float[] xs, float[] ys, int argb, boolean closed) {
        if (disposed || xs == null || ys == null) return this;
        int n = Math.min(xs.length, ys.length);
        for (int i = 0; i + 1 < n; i++) {
            drawLine((int) xs[i], (int) ys[i], (int) xs[i + 1], (int) ys[i + 1], argb);
        }
        if (closed && n > 2) {
            drawLine((int) xs[n - 1], (int) ys[n - 1], (int) xs[0], (int) ys[0], argb);
        }
        return this;
    }

    // ==================== 文字叠加层 ====================

    /** 在画布像素坐标处叠加文字（原版字体，每帧批量绘制） */
    public CanvasSurface drawText(float x, float y, String text, int color) {
        return drawText(x, y, text, color, 1.0F);
    }

    public CanvasSurface drawText(float x, float y, String text, int color, float scale) {
        if (!disposed && text != null && !text.isEmpty()) {
            texts.add(new TextEntry(x, y, text, color, scale));
        }
        return this;
    }

    public CanvasSurface clearTexts() {
        texts.clear();
        return this;
    }

    public List<TextEntry> texts() { return texts; }

    public CanvasSurface markDirty() {
        dirty = true;
        return this;
    }

    // ==================== 上传 / 渲染 / 生命周期 ====================

    /** 立即上传像素到纹理(渲染线程) */
    public void uploadNow() {
        if (disposed || !dirty) return;
        try {
            texture.upload();
            dirty = false;
        } catch (Throwable ignored) {
            // 失败保持脏标记,下次重试
        }
    }

    /** 每客户端 tick:上传脏纹理(≤20Hz,由数据变化频率决定) */
    @Override
    public void screenTick() {
        if (dirty && !disposed) uploadNow();
    }

    /** 每帧渲染:一次 blit(1 个 quad),与内容复杂度无关 */
    @Override
    public void drawContents(GUIContext ctx) {
        if (disposed) return;
        // 诊断（2026-08-17：EDA 画布不显示，确认 drawContents 是否被调用 + 布局缓存值）
        if (CanvasSurface.debugCount++ < 10) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "[EDA-DIAG] drawContents {} layoutPos=({},{}) layoutSize=({},{}) region={}",
                    textureId, getPositionX(), getPositionY(),
                    getSizeWidth(), getSizeHeight(), hasRenderRegion);
        }
        // 兜底上传:每 tick 至多一次(防止 screenTick 未覆盖的场景)
        if (dirty) {
            long tick = Minecraft.getInstance().level == null
                    ? -1 : Minecraft.getInstance().level.getGameTime();
            if (tick != lastUploadTick) {
                lastUploadTick = tick;
                uploadNow();
            }
        }
        if (texture.getId() == -1) {
            try {
                texture.upload();
            } catch (Throwable ignored) {
                return;
            }
        }
        int rx, ry, rw, rh;
        if (hasRenderRegion) {
            rx = renderX; ry = renderY; rw = renderW; rh = renderH;
        } else {
            rx = (int) getPositionX(); ry = (int) getPositionY();
            rw = (int) getSizeWidth(); rh = (int) getSizeHeight();
        }
        GuiGraphics g = ctx.graphics;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(textureId, rx, ry, rw, rh,
                0.0F, 0.0F, width, height, width, height);
        renderTextsAt(ctx, rx, ry);
        renderExtraAt(ctx, rx, ry);
    }

    /**
     * 手动渲染到指定位置/尺寸（脱离 LDLib2 布局的独立 Screen 用，2026-08-17）。
     * 调用方需保证渲染线程；纹理自动上传（若脏）。
     */
    public void renderAt(GUIContext ctx, int x, int y, int w, int h) {
        if (disposed) return;
        if (dirty) uploadNow();
        if (texture.getId() == -1) {
            try {
                texture.upload();
            } catch (Throwable ignored) {
                return;
            }
        }
        GuiGraphics g = ctx.graphics;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(textureId, x, y, w, h, 0.0F, 0.0F, width, height, width, height);
        renderTextsAt(ctx, x, y);
        renderExtraAt(ctx, x, y);
    }

    /** 文字叠加层:按画布原点 (ox,oy) 平移后批量绘制 */
    private void renderTextsAt(GUIContext ctx, int ox, int oy) {
        if (texts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        Matrix4f base = new Matrix4f(ctx.graphics.pose().last().pose());
        base.translate(ox, oy, 0);
        for (TextEntry e : texts) {
            Matrix4f m = e.scale == 1.0F ? base
                    : new Matrix4f(base).translate(e.x, e.y, 0).scale(e.scale, e.scale, 1.0F)
                            .translate(-e.x, -e.y, 0);
            mc.font.drawInBatch(e.text, e.x, e.y, e.color, false, m, bs,
                    Font.DisplayMode.NORMAL, 0, 0xF000F0);
        }
        bs.endBatch();
    }

    /** 子类额外叠加绘制钩子（如 GraphCanvas 的世界坐标文字），默认空 */
    protected void renderExtraAt(GUIContext ctx, int ox, int oy) {
    }

    /** 释放纹理与像素缓冲(UI 移除时调用;TextureManager release 会 close 纹理) */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        try {
            Minecraft.getInstance().getTextureManager().release(textureId);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onRemoved() {
        dispose();
    }

    // ==================== 工具 ====================

    /** ARGB(0xAARRGGBB) → NativeImage 需要的 ABGR */
    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
