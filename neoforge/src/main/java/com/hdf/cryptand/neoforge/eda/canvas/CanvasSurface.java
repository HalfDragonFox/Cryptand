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
    /** 像素缓冲与纹理**惰性创建**（见 {@link #ensureInit()}）：服务端构建 UI 树时不碰客户端资源 */
    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private boolean initTried;

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
    /**
     * 最近一次渲染的**显示缩放**（显示尺寸 ÷ 缓冲尺寸）。文字与子类叠加层（{@link #renderExtraAt}）
     * 都按缓冲坐标给位置，必须乘上它才不会与拉伸后的像素错位（2026-09-29 实测：面板小的时候
     * 画布被等比缩小，文字全跑偏）。
     */
    private float renderScaleX = 1.0F;
    private float renderScaleY = 1.0F;
    /**
     * 等比显示时，画面在元素区域内的**居中偏移**（letterbox 留边）。
     *
     * <p>用户 2026-09-29 点破的真问题：以前直接把缓冲拉伸到布局尺寸（rw/width、rh/height 各算一个比例），
     * 一旦可用区不是 600:350，两个比例就不相等 ⇒ 文字、物品图标、鼠标命中全都会偏。
     * 现在统一按**等比缩放 + 居中**算显示矩形，缩放只有一个值，偏移由这里给出。</p>
     */
    private int renderOffX;
    private int renderOffY;

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
        if (!skipLayout) {
            if (fill) {
                layout(l -> l.flex(1).flexShrink(0));
            } else {
                layout(l -> l.width(this.width).height(this.height));
            }
        }
    }

    /**
     * 客户端资源惰性初始化（2026-09-29）：像素缓冲 + 纹理只在<b>真正要画</b>时创建。
     *
     * <p>为什么：LDLib2 的 UI 树是<b>双端构建</b>的（{@code ModularUIContainerMenu} 构造里就调
     * {@code createUI}，服务端同样执行），而服务端没有可用的 {@code Minecraft.getInstance()}
     * （TextureManager / GL 都不在）⇒ 构造期注册纹理会让服务端构建面板时直接 NPE。
     * 服务端只用到 UI 的逻辑部分（事件 / RPC），不画像素，所以惰性创建两头都成立。</p>
     *
     * @return true = 可画（已初始化），false = 现在不能画（服务端 / 已释放 / 初始化失败）
     */
    private boolean ensureInit() {
        if (disposed) {
            return false;
        }
        if (image != null) {
            return true;
        }
        if (initTried) {
            return false;
        }
        initTried = true;
        try {
            image = new NativeImage(width, height, false);
            texture = new DynamicTexture(image);
            textureId = ResourceLocation.fromNamespaceAndPath("cryptand",
                    "canvas_" + COUNTER.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            dirty = true;
            return true;
        } catch (Throwable t) {
            image = null;
            texture = null;
            textureId = null;
            return false;
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

    /**
     * 背景层快照（拖动性能优化，2026-09-29）。
     *
     * <p>为什么需要：背景（底色 + 网格 + 外框）如果每次都全量重画，就是 20 万次像素写
     * （clear + 600 条竖线 + 350 条横线）⇒ 单次重绘 5~10ms，再叠一次 840KB 纹理上传，
     * 拖动时帧率掉下来、手感就"微滞后"。子类可以存一份背景快照，重绘时一次 arraycopy
     * 回填（微秒级），再只画器件 / 引脚 / 连线。</p>
     *
     * @return 像素缓冲的副本（未初始化 / 服务端 ⇒ null）
     */
    protected long[] snapshotPixelsUnused() {
        return null;
    }

    /** 用 {@link #snapshotPixels()} 存下的背景快照整块回填（false = 快照不可用，调用方自行重画背景）。 */
    protected boolean restorePixelsUnused(long[] snapshot) {
        return false;
    }

    /** 导出当前像素缓冲为 PNG（EDA 工具栏 PNG 按钮，2026-08-17） */
    public void savePng(java.nio.file.Path p) throws java.io.IOException {
        if (!ensureInit()) {
            throw new java.io.IOException("画布未在客户端初始化，无法导出 PNG");
        }
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
        if (!ensureInit() || x < 0 || y < 0 || x >= width || y >= height) return this;
        image.setPixelRGBA(x, y, toAbgr(argb));
        dirty = true;
        return this;
    }

    public CanvasSurface fillRect(int x0, int y0, int x1, int y1, int argb) {
        if (!ensureInit()) return this;
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
        if (!ensureInit()) return this;
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
        if (!ensureInit() || values == null || values.length < 2 || pw < 1 || ph < 1) return this;
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
        if (!ensureInit() || r < 0) return this;
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
        if (!ensureInit() || xs == null || ys == null) return this;
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
        if (!ensureInit() || !dirty) return;
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
        if (!ensureInit()) return;
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
        // ===== 等比 letterbox：在给定显示区域里按缓冲比例居中缩放（唯一缩放来源）=====
        final float scale = Math.min(rw / (float) width, rh / (float) height);
        final int dw = Math.max(1, Math.round(width * scale));
        final int dh = Math.max(1, Math.round(height * scale));
        renderOffX = (rw - dw) / 2;
        renderOffY = (rh - dh) / 2;
        final int dx = rx + renderOffX;
        final int dy = ry + renderOffY;
        renderScaleX = scale;
        renderScaleY = scale;
        g.blit(textureId, dx, dy, dw, dh, 0.0F, 0.0F, width, height, width, height);
        // EDA = 一个"层"：属于画布的内容只画在画布矩形内，超出部分一律不渲染（用户定案 2026-09-29）
        ctx.enableScissor(dx, dy, dx + dw, dy + dh);   // 用 GUIContext 的 scissor（离屏层下坐标才正确）
        renderExtraAt(ctx, dx, dy);      // 物品图标先画：文字层与浮层压在上面
        renderTextsAt(ctx, dx, dy);
        ctx.disableScissor();
    }

    /**
     * 手动渲染到指定位置/尺寸（脱离 LDLib2 布局的独立 Screen 用，2026-08-17）。
     * 调用方需保证渲染线程；纹理自动上传（若脏）。
     */
    public void renderAt(GUIContext ctx, int x, int y, int w, int h) {
        if (!ensureInit()) return;
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
        final float scale2 = Math.min(w / (float) width, h / (float) height);
        final int dw2 = Math.max(1, Math.round(width * scale2));
        final int dh2 = Math.max(1, Math.round(height * scale2));
        renderOffX = (w - dw2) / 2;
        renderOffY = (h - dh2) / 2;
        final int dx2 = x + renderOffX;
        final int dy2 = y + renderOffY;
        renderScaleX = scale2;
        renderScaleY = scale2;
        g.blit(textureId, dx2, dy2, dw2, dh2, 0.0F, 0.0F, width, height, width, height);
        ctx.enableScissor(dx2, dy2, dx2 + dw2, dy2 + dh2);   // 同上：EDA 层裁剪
        renderExtraAt(ctx, dx2, dy2);
        renderTextsAt(ctx, dx2, dy2);
        ctx.disableScissor();
    }

    /** 画面在元素内的居中偏移（缓冲坐标原点落在这里）：子类做鼠标命中换算时用。 */
    protected int renderOffX() {
        return renderOffX;
    }

    protected int renderOffY() {
        return renderOffY;
    }

    /** 显示缩放（缓冲 → 屏幕）：子类画物品图标等叠加层时乘上它。 */
    protected float scaleX() {
        return renderScaleX;
    }

    protected float scaleY() {
        return renderScaleY;
    }

    /** 文字叠加层:按画布原点 (ox,oy) 平移后批量绘制 */
    private void renderTextsAt(GUIContext ctx, int ox, int oy) {
        if (texts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        Matrix4f base = new Matrix4f(ctx.graphics.pose().last().pose());
        // ⚠ z 必须是正数且**大于物品图标的 z**（MC 的 GuiGraphics.renderItem 内部把物品抬到 z≈150）：
        //   画布文字/叠加层若留在 z=0，无论先后调用，物品都会靠深度把它盖住（2026-09-29 用户实测多轮）。
        base.translate(ox, oy, 200.0F);
        base.scale(renderScaleX, renderScaleY, 1.0F);   // 文字坐标是缓冲坐标 ⇒ 跟着画布缩放
        for (TextEntry e : texts) {
            Matrix4f m = e.scale == 1.0F ? base
                    : new Matrix4f(base).translate(e.x, e.y, 0).scale(e.scale, e.scale, 1.0F)
                            .translate(-e.x, -e.y, 0);
            mc.font.drawInBatch(e.text, e.x, e.y, e.color, true, m, bs,
                    Font.DisplayMode.NORMAL, 0, 0xF000F0);   // dropShadow：深色底上小字更清楚
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
        if (textureId == null) {
            return;                 // 服务端从未初始化过资源（惰性创建）：没有要释放的纹理
        }
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
