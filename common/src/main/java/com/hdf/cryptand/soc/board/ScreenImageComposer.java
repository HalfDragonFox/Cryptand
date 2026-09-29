package com.hdf.cryptand.soc.board;

import java.util.Arrays;

/**
 * ===== 真彩屏"下发图像"合成器（common，纯 Java 零 MC，2026-09-28）=====
 *
 * <p>用户定案（2026-09-28）：服务器把屏内容<b>合成成一张图像</b>再下发，客户端不再理解字符面的
 * 色深/调色板/颜色打包 —— 白屏那类"格式不一致"的 bug 从根上消失。本类是那个合成器：</p>
 *
 * <ol>
 *   <li><b>TEXT</b>：字符面 → ARGB 像素（口径与 {@link ScreenTextLayer#paint} 完全一致，
 *       只是本类按行合成、并支持采样）；</li>
 *   <li><b>GRAPHICS</b>：直接按 {@link TrueColorScreen#rgbAt(int, int)} 点采样
 *       （像素面的权威就是设备 VRAM）；</li>
 *   <li><b>采样</b>：按 {@link ScreenSamplingPolicy} 给的步长横竖抽点（远处省带宽）；
 *       每个采样点是<b>点采样</b>（不平均）—— 文本要的是"像"，不是抗锯齿；</li>
 *   <li><b>脏行</b>：逐行与上一帧比较，只有变化的行才标记为脏 ⇒ 增量包只发这些行。</li>
 * </ol>
 *
 * <p>几何/模式全部来自设备（没有第二份数值）；设备尺寸与构造时不符是<b>明确报错</b>，
 * 不静默重采样（调用方应重建合成器）。</p>
 */
public final class ScreenImageComposer {

    private final int width;
    private final int height;
    private int stepW;
    private int stepH;
    private int gridW;
    private int gridH;
    private int[] image;
    private boolean[] dirty;
    /**
     * 每行**实际变化的列区间**（{@code [dirtyColFrom[gy], dirtyColTo[gy])}，空 = from>=to）。
     *
     * <p>用户要求（2026-09-29）：下发按**矩形脏块**而不是整行 —— 光标闪烁/局部刷新这类变化
     * 往往只占一行里的几个采样点，整行下发会把带宽浪费在没变的像素上。</p>
     */
    private int[] dirtyColFrom;
    private int[] dirtyColTo;
    private boolean fullDirty = true;
    private int unknownGlyphs;
    private long composeCount;

    // ===== 格级脏合成（2026-09-29）=====
    // 问题：composeText 原来每帧把整屏 102400 个采样点全部重算一遍，哪怕只变了一个字符格。
    // 离线基准实测 27.05 ns/采样点 ⇒ 2.77ms/帧，静态屏也要白付（真机靠"无脏不回帧"侥幸躲开）。
    // 解法：缓存上一帧每个字符格的 (char, fg, bg)，逐格比较；只有变化格覆盖的采样点才重画，
    // 其余沿用 image 里上一帧的像素。一格没变 ⇒ 直接早退（0 成本）。
    private char[] cellChars;
    private int[] cellFg;
    private int[] cellBg;
    private boolean[] cellDirty;
    private int cellCacheCols;
    private int cellCacheRows;
    private int cellCacheStepW;
    private int cellCacheStepH;
    private boolean cellCacheValid;

    public ScreenImageComposer(int width, int height) {
        this(width, height, 1, 1);
    }

    public ScreenImageComposer(int width, int height, int stepW, int stepH) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("屏幕尺寸必须为正：" + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.stepW = Math.max(1, stepW);
        this.stepH = Math.max(1, stepH);
        rebuild();
    }

    private void rebuild() {
        this.gridW = ScreenSamplingPolicy.gridSize(width, stepW);
        this.gridH = ScreenSamplingPolicy.gridSize(height, stepH);
        this.image = new int[gridW * gridH];
        this.dirty = new boolean[gridH];
        this.dirtyColFrom = new int[gridH];
        this.dirtyColTo = new int[gridH];
        clearDirtyColumns();
        this.fullDirty = true;
    }

    /**
     * 字符源（TEXT 面的内容来源）：<b>不依赖设备</b>。
     *
     * <p>为什么要它：设备的像素/字符平面只在"图形面写入"时才建立（纯文本屏、机器停机重启后
     * 都可能没有设备）。而字符内容在**组件**里始终权威（字符 + 打包前景/背景 + 当前格式），
     * 服务端用自己那份格式解码就不会出现"客户端拿宽格式当 1bit 解"的白屏。</p>
     */
    public interface CellReader {
        /** 字符（1 基，与设备字符平面同口径）；越界必须返回空格，不抛。 */
        char charAt(int col, int row);

        /** 前景 24 位 RGB（已按源的格式解出）；越界返回 0。 */
        int foregroundAt(int col, int row);

        /** 背景 24 位 RGB（已按源的格式解出）；越界返回 0。 */
        int backgroundAt(int col, int row);
    }

    // ==================== 几何 ====================

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int stepW() {
        return stepW;
    }

    public int stepH() {
        return stepH;
    }

    public int gridWidth() {
        return gridW;
    }

    public int gridHeight() {
        return gridH;
    }

    /** 这台设备还能用这个合成器吗（尺寸/模式无关，只看几何）。 */
    public boolean matches(TrueColorScreen screen) {
        return screen != null && screen.width() == width && screen.height() == height;
    }

    /**
     * 设置采样步长（横竖各自 2 的幂，来自 {@link ScreenSamplingPolicy}）。
     *
     * @return true = 步长变了（此时整幅置为全脏，客户端必须收到一次全量）
     */
    public boolean setSampling(int stepW, int stepH) {
        final int w = Math.max(1, stepW);
        final int h = Math.max(1, stepH);
        if (w == this.stepW && h == this.stepH) {
            return false;
        }
        this.stepW = w;
        this.stepH = h;
        rebuild();
        return true;
    }

    // ==================== 合成 ====================

    /**
     * 合成整幅；返回本次<b>变化</b>的采样行数（全脏时 = 全部行）。
     *
     * @throws IllegalStateException 设备尺寸与构造时不符（调用方应重建合成器）
     */
    public int compose(TrueColorScreen screen) {
        if (screen == null) {
            throw new IllegalArgumentException("screen 不能为 null");
        }
        if (!matches(screen)) {
            throw new IllegalStateException("设备几何变了（设备 " + screen.width() + "x" + screen.height()
                    + "，合成器 " + width + "x" + height + "）：请重建合成器（不静默重采样）");
        }
        composeCount++;
        final boolean text = screen.mode() == TrueColorScreen.Mode.TEXT;
        unknownGlyphs = text ? countUnknownGlyphs(screen) : 0;

        final int[] rowBuf = new int[gridW];
        int changed = 0;
        for (int gy = 0; gy < gridH; gy++) {
            final int y = gy * stepH;
            final boolean yInside = y < height;
            for (int gx = 0; gx < gridW; gx++) {
                final int x = gx * stepW;
                if (!yInside || x >= width) {
                    rowBuf[gx] = ScreenTextLayer.UNCOVERED;      // 采样网格超出屏幕：与字符层同口径
                } else if (text) {
                    rowBuf[gx] = textPixel(screen, x, y);
                } else {
                    rowBuf[gx] = screen.rgbAt(x, y);
                }
            }
            if (markRow(gy, rowBuf, gy * gridW)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 用**字符源**合成（TEXT 面，不依赖设备）—— 服务端合成图像的正路。
     *
     * <p>几何取自本合成器（构造时给的像素尺寸）；字符格数由调用方给（viewport），
     * 与设备无关。口径与 {@link #compose(TrueColorScreen)} 的 TEXT 分支逐字一致：
     * 点采样、字模只占上 {@code GLYPH_H} 行、格铺不到的边角 = {@link ScreenTextLayer#UNCOVERED}。</p>
     *
     * @return 本次变化的采样行数
     */
    public int composeText(int cols, int rows, CellReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("字符源不能为 null");
        }
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("字符格数必须为正：" + cols + "x" + rows);
        }
        composeCount++;
        unknownGlyphs = 0;

        // 几何/采样变了 ⇒ 缓存作废（image 布局变了，格内容不能沿用）
        final boolean cacheUsable = cellCacheValid
                && cellCacheCols == cols && cellCacheRows == rows
                && cellCacheStepW == stepW && cellCacheStepH == stepH;
        ensureCellCache(cols, rows);

        // 1) 逐格比较（每格 3 次回调，而不是每采样点 3 次）
        boolean anyCellDirty = !cacheUsable;
        for (int row = 1; row <= rows; row++) {
            final int rowBase = (row - 1) * cols;
            for (int col = 1; col <= cols; col++) {
                final int at = rowBase + (col - 1);
                final char ch = reader.charAt(col, row);
                final int fg = reader.foregroundAt(col, row) & 0xFFFFFF;
                final int bg = reader.backgroundAt(col, row) & 0xFFFFFF;
                if (TextFont8x8.glyphOf(ch) < 0) {
                    unknownGlyphs++;
                }
                if (cacheUsable && cellChars[at] == ch && cellFg[at] == fg && cellBg[at] == bg) {
                    cellDirty[at] = false;
                } else {
                    cellDirty[at] = true;
                    anyCellDirty = true;
                    cellChars[at] = ch;
                    cellFg[at] = fg;
                    cellBg[at] = bg;
                }
            }
        }
        cellCacheCols = cols;
        cellCacheRows = rows;
        cellCacheStepW = stepW;
        cellCacheStepH = stepH;
        cellCacheValid = true;

        // 一格都没变 ⇒ 上一帧的 image 就是本帧内容，直接早退（静态屏 0 成本）
        if (!anyCellDirty && !fullDirty) {
            return 0;
        }

        final int[] rowBuf = new int[gridW];
        int changed = 0;
        final int cellRows = Math.min(rows, gridH * stepH / Math.max(1, TrueColorScreen.CELL_H) + 1);
        final int cellCols = Math.min(cols, gridW * stepW / Math.max(1, TrueColorScreen.CELL_W) + 1);
        for (int gy = 0; gy < gridH; gy++) {
            final int y = gy * stepH;
            final boolean yInside = y < height;
            for (int gx = 0; gx < gridW; gx++) {
                final int x = gx * stepW;
                if (!yInside || x >= width) {
                    rowBuf[gx] = ScreenTextLayer.UNCOVERED;
                } else {
                    final int col = x / TrueColorScreen.CELL_W + 1;
                    final int row = y / TrueColorScreen.CELL_H + 1;
                    if (col > cellCols || row > cellRows) {
                        rowBuf[gx] = ScreenTextLayer.UNCOVERED;
                    } else if (anyCellDirty && cellDirty[(row - 1) * cols + (col - 1)]) {
                        rowBuf[gx] = ScreenTextLayer.cellPixel(
                                cellChars[(row - 1) * cols + (col - 1)],
                                cellFg[(row - 1) * cols + (col - 1)],
                                cellBg[(row - 1) * cols + (col - 1)],
                                x % TrueColorScreen.CELL_W, y % TrueColorScreen.CELL_H);
                    } else {
                        rowBuf[gx] = image[gy * gridW + gx];      // 未变格：沿用上一帧像素
                    }
                }
            }
            if (markRow(gy, rowBuf, gy * gridW)) {
                changed++;
            }
        }
        return changed;
    }

    /** 保证格缓存容量够（列/行数变了就重建；重建即"没有上一帧"，调用方按 cacheUsable 处理）。 */
    private void ensureCellCache(int cols, int rows) {
        final int need = cols * rows;
        if (cellChars == null || cellChars.length < need) {
            cellChars = new char[need];
            cellFg = new int[need];
            cellBg = new int[need];
            cellDirty = new boolean[need];
            cellCacheValid = false;
        }
    }

    private static int textPixel(TrueColorScreen screen, int x, int y) {
        final int col = x / TrueColorScreen.CELL_W + 1;
        final int row = y / TrueColorScreen.CELL_H + 1;
        if (col > screen.textCols() || row > screen.textRows()) {
            return ScreenTextLayer.UNCOVERED;                    // 字符格铺不到的边角
        }
        final char ch = screen.textAt(col, row);
        final int fg = screen.textForeground(col, row) & 0xFFFFFF;
        final int bg = screen.textBackground(col, row) & 0xFFFFFF;
        return ScreenTextLayer.cellPixel(ch, fg, bg, x % TrueColorScreen.CELL_W, y % TrueColorScreen.CELL_H);
    }

    private static int countUnknownGlyphs(TrueColorScreen screen) {
        int unknown = 0;
        final int cols = screen.textCols();
        final int rows = screen.textRows();
        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                if (TextFont8x8.glyphOf(screen.textAt(col, row)) < 0) {
                    unknown++;
                }
            }
        }
        return unknown;
    }

    // ==================== 结果 ====================

    /** 合成后的 ARGB 图像（**同一线程**使用；跨线程请用 {@link #copyImage()}）。 */
    public int[] image() {
        return image;
    }

    /** 跨线程安全的一份拷贝（报文在别的线程上序列化，不能共享缓冲）。 */
    public int[] copyImage() {
        return image.clone();
    }

    public boolean fullDirty() {
        return fullDirty;
    }

    /** 采样的行脏标记（下标 = 采样行）。 */
    public boolean[] dirtyRows() {
        return dirty;
    }

    /** 脏行合并成区间：{@code [start0, endExclusive0, start1, endExclusive1, ...]}；全脏 = 整幅一个区间。 */
    public int[] dirtyRanges() {
        return mergeRanges(fullDirty ? allDirty(gridH) : dirty);
    }

    /**
     * 脏**矩形**：{@code [row0, rowCount0, colFrom0, colToExclusive0, ...]}（列区间是半开）。
     *
     * <p>与 {@link #dirtyRanges()}（整行区间）相比，这里把**同一列范围**的相邻行合并成一个矩形 ——
     * 光标闪烁、局部刷新这类变化往往只占一行里的几个采样点，按矩形下发能把没变的像素整段省掉。
     * 全脏 = 整幅一个矩形。</p>
     */
    public int[] dirtyRects() {
        if (fullDirty) {
            return new int[] {0, gridH, 0, gridW};
        }
        final int[] out = new int[gridH * 4];
        int n = 0;
        int row = 0;
        while (row < gridH) {
            if (!dirty[row]) {
                row++;
                continue;
            }
            final int from = dirtyColFrom[row];
            final int to = dirtyColTo[row];
            int end = row + 1;
            while (end < gridH && dirty[end] && dirtyColFrom[end] == from && dirtyColTo[end] == to) {
                end++;
            }
            out[n++] = row;
            out[n++] = end - row;
            out[n++] = from;
            out[n++] = to;
            row = end;
        }
        return Arrays.copyOf(out, n);
    }

    /** 清掉所有行的列区间（空区间 = {@code from >= to}）。 */
    private void clearDirtyColumns() {
        Arrays.fill(dirtyColFrom, gridW);
        Arrays.fill(dirtyColTo, 0);
    }

    /**
     * 把一行的采样结果并入帧缓冲，并记录**变化的列区间**（两个合成入口共用）。
     *
     * <p>逐格比较而不是 {@code Arrays.equals}：我们要的是"哪一段变了"，不只是"变没变"。</p>
     *
     * @return 该行是否算作"变化"（调用方据此累计 changed）
     */
    private boolean markRow(int gy, int[] rowBuf, int off) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < gridW; i++) {
            if (image[off + i] != rowBuf[i]) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (!fullDirty && first < 0) {
            return false;                                    // 这一行没变
        }
        System.arraycopy(rowBuf, 0, image, off, gridW);
        dirty[gy] = true;
        if (first < 0) {                                     // 全脏帧：整行宽
            dirtyColFrom[gy] = 0;
            dirtyColTo[gy] = gridW;
        } else {
            if (dirtyColFrom[gy] > first) {
                dirtyColFrom[gy] = first;
            }
            if (dirtyColTo[gy] < last + 1) {
                dirtyColTo[gy] = last + 1;
            }
        }
        return true;
    }

    public int unknownGlyphs() {
        return unknownGlyphs;
    }

    public long composeCount() {
        return composeCount;
    }

    /** 发完（或已确认客户端拿到）之后清脏。 */
    public void markClean() {
        Arrays.fill(dirty, false);
        clearDirtyColumns();
        fullDirty = false;
    }

    /** 全脏（换采样/重建时内部用；也供调用方在下发一条错误/重建后强制全量）。 */
    public void markAllDirty() {
        Arrays.fill(dirty, true);
        Arrays.fill(dirtyColFrom, 0);
        Arrays.fill(dirtyColTo, gridW);
        fullDirty = true;
    }

    // ==================== 纯函数（闸门直接钉住） ====================

    /** 把行脏标记合并成区间对（相邻行合成一段）。 */
    public static int[] mergeRanges(boolean[] dirtyRows) {
        if (dirtyRows == null || dirtyRows.length == 0) {
            return new int[0];
        }
        int[] out = new int[dirtyRows.length + 2];
        int n = 0;
        int i = 0;
        while (i < dirtyRows.length) {
            if (!dirtyRows[i]) {
                i++;
                continue;
            }
            final int start = i;
            while (i < dirtyRows.length && dirtyRows[i]) {
                i++;
            }
            if (n + 2 > out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            out[n++] = start;
            out[n++] = i;
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean[] allDirty(int rows) {
        final boolean[] all = new boolean[Math.max(0, rows)];
        Arrays.fill(all, true);
        return all;
    }
}
