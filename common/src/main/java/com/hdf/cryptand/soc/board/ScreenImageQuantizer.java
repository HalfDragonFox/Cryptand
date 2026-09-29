package com.hdf.cryptand.soc.board;

/**
 * ===== 图形面（图像）→ 字符格 + 前景/背景 的量化（common，纯 Java 零 MC，2026-09-27 任务 G）=====
 *
 * <p>用户定案（{@code repo/gpu-auto-convert-output-2026-09-27.md}）：</p>
 * <blockquote>程序直接画图像输出，然后具体是转字符还是直接画由虚拟机即 gpu 部分自动转换输出给屏幕</blockquote>
 * <p>其中"目标是字符屏（OC 原版屏等，只能显示字符）⇒ GPU 侧<b>自动转字符</b>（像素 → 字符格 + fg/bg 量化）
 * 再输出"这一半，就是本类。调用条件只有一个：{@link ScreenOutputFace#requiresImageToCharacters} 为 true
 * （见 {@code OcComponentBus} 的图形面输出那条链）。</p>
 *
 * <h3>算法（一格 8×8，全部确定性）</h3>
 * <ol>
 *   <li>格内每个像素**按调色板就近**归到一个索引（距离 = RGB 平方距离，全项目只有这一条算式）；</li>
 *   <li>取格内出现次数最多的索引 <b>majority</b>（并列取小索引）与其次的 <b>minority</b>；</li>
 *   <li>两个候选各算一次，**取失配更少的那个**（并列取候选 A）：
 *       <ul>
 *         <li>A（标准极性）：bg = majority、fg = minority ⇒ 背景是多数色，"亮位" = 少数色像素；</li>
 *         <li>B（反极性）：fg = majority、bg = minority ⇒ 当"亮位"反而占多数时用它（否则只能画成反色）。</li>
 *       </ul></li>
 *   <li>候选内：把"亮位"拼成 64 位掩码，与 {@link TextFont8x8} 的 <b>256 个字形逐一比对取失配最少者</b>
 *       （并列取小字形号）；全暗格特判成空格 {@link TextFont8x8#SPACE}（否则小字形号会挑到 NUL）；</li>
 *   <li>输出字符 + fg 索引 + bg 索引（0 基行优先三平面；1 基换算由调用方做，与设备口径一致）。</li>
 * </ol>
 *
 * <h3>★ 字形与颜色都**没有第二套**（硬约束）</h3>
 * <ul>
 *   <li><b>字形</b>：唯一来源 {@link TextFont8x8}（256 字形 CP437 ROM 位图）—— 本类只读
 *       {@link TextFont8x8#pixel(int, int, int)} 与 {@link TextFont8x8#GLYPH_COUNT}，没有自己的表；</li>
 *   <li><b>颜色</b>：调色板由**调用方传入**（设备自己的那份：真彩屏 {@code paletteColor(i)}／
 *       OC 屏 {@code getPaletteColor(i)}），本类不内置任何色表；</li>
 *   <li><b>方向一致性</b>：反方向（字符 → 像素）只有 {@link ScreenTextLayer#cellPixel} 一处定义，
 *       闸门里对 256 个字形做了"造图 → 量化 → 再用 cellPixel 画回来"的逐像素对拍 ⇒ 两个方向不可能各画各的。</li>
 * </ul>
 *
 * <h3>为什么必须放 common</h3>
 * <p>它是"查位 + 最近色 + 取最像字形"的纯算术，拿掉 Minecraft 一样成立；而它恰恰最容易错在边角
 * （全暗格、单色格、反极性、屏宽不是 8 的倍数、调色板并列）。放平台层就只有起客户端才能验。</p>
 *
 * <p>跑法（离线闸门）：{@code java -cp <common classes> com.hdf.cryptand.soc.board.ScreenImageQuantizerSelfTest}
 * （gradle 任务登记需要 {@code common/build.gradle} 授权，见任务 G 报告）。</p>
 */
public final class ScreenImageQuantizer {

    /** 一格多少像素（与设备同一常量：{@link TrueColorScreen#CELL_W} / {@link TrueColorScreen#CELL_H}） */
    public static final int CELL_W = TrueColorScreen.CELL_W;
    public static final int CELL_H = TrueColorScreen.CELL_H;

    /**
     * 量化结果：三平面（与字符屏的字符缓冲同构，**0 基行优先**数组，长度 = cols × rows）。
     *
     * @param chars 每格的字符（字形来自 {@link TextFont8x8} 的 CP437 码页 ⇒ 正好是一字节）
     * @param fg    每格前景<b>调色板索引</b>
     * @param bg    每格背景<b>调色板索引</b>
     * @param cols  格列数
     * @param rows  格行数
     */
    public record Result(char[] chars, int[] fg, int[] bg, int cols, int rows) {

        /** 量化了多少格 */
        public int cells() {
            return cols * rows;
        }

        /** 第 (col, row) 格（0 基）的字符 —— 越界**明确报错**，不静默给空格 */
        public char charAt(int col, int row) {
            return chars[index(col, row)];
        }

        public int fgAt(int col, int row) {
            return fg[index(col, row)];
        }

        public int bgAt(int col, int row) {
            return bg[index(col, row)];
        }

        private int index(int col, int row) {
            if (col < 0 || row < 0 || col >= cols || row >= rows) {
                throw new IllegalArgumentException("格坐标越界：(" + col + ", " + row + ") 不在 "
                        + cols + "x" + rows + " 内");
            }
            return row * cols + col;
        }
    }

    private ScreenImageQuantizer() {
    }

    /**
     * 把一张 ARGB（{@code 0xAARRGGBB}，与 {@link ScreenTextLayer#paint} 同一口径）图像量化成字符格。
     *
     * @param argb    图像像素，长度必须 ≥ {@code pixelWidth × pixelHeight}（不足/NULL 明确报错）
     * @param palette 目标屏的调色板（{@code 0xRRGGBB} / {@code 0xAARRGGBB} 都吃，只取低 24 位）
     * @param cols    目标字符格列数；{@code cols × CELL} 之外的图像像素<b>丢弃</b>（不是缩放）
     * @param rows    目标字符格行数
     */
    public static Result quantize(int[] argb, int pixelWidth, int pixelHeight,
                                  int cols, int rows, int[] palette) {
        require(argb, pixelWidth, pixelHeight, palette);
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("字符格尺寸非法：" + cols + "x" + rows);
        }
        final char[] chars = new char[cols * rows];
        final int[] fg = new int[cols * rows];
        final int[] bg = new int[cols * rows];
        final int[] histogram = new int[palette.length];
        final int[] cellIndexes = new int[CELL_W * CELL_H];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final int at = row * cols + col;
                final Cell cell = cell(argb, pixelWidth, pixelHeight, col, row, palette,
                        histogram, cellIndexes);
                chars[at] = cell.ch;
                fg[at] = cell.fg;
                bg[at] = cell.bg;
            }
        }
        return new Result(chars, fg, bg, cols, rows);
    }

    /**
     * 单格量化（闸门/诊断用；{@link #quantize} 就是逐格调它 —— 同一段定义）。
     *
     * @param out 长度 ≥ 3 的输出：{@code [0]=字符, [1]=fg 索引, [2]=bg 索引}
     */
    public static void quantizeCell(int[] argb, int pixelWidth, int pixelHeight,
                                    int cellCol, int cellRow, int[] palette, int[] out) {
        if (out == null || out.length < 3) {
            throw new IllegalArgumentException("out 至少 3 个元素（字符/fg/bg）");
        }
        require(argb, pixelWidth, pixelHeight, palette);
        final int[] histogram = new int[palette.length];
        final int[] cellIndexes = new int[CELL_W * CELL_H];
        final Cell c = cell(argb, pixelWidth, pixelHeight, cellCol, cellRow, palette,
                histogram, cellIndexes);
        out[0] = c.ch;
        out[1] = c.fg;
        out[2] = c.bg;
    }

    /**
     * 一个像素的**就近调色板索引**（RGB 平方距离；并列取小索引 ⇒ 确定性）。
     *
     * <p>这条算式在本项目里**只有这一处**：原先 {@code CryptandGpuEnvironment.nearestPaletteIndex}
     * 是第二份私有实现，任务 G 已随"外部显式切模式"的像素路径一并删除。</p>
     */
    public static int nearestPaletteIndex(int rgb, int[] palette) {
        if (palette == null || palette.length == 0) {
            throw new IllegalArgumentException("调色板不能为空");
        }
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            final int c = palette[i] & 0xFFFFFF;
            final int dr = ((c >> 16) & 0xFF) - r;
            final int dg = ((c >> 8) & 0xFF) - g;
            final int db = (c & 0xFF) - b;
            final int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    // ==================== 内部 ====================

    /** 一格的量化结果（字符 + fg/bg 索引 + 与字形的失配位数，失配用于选极性） */
    private record Cell(char ch, int fg, int bg, int mismatch) {
    }

    private static void require(int[] argb, int pixelWidth, int pixelHeight, int[] palette) {
        if (argb == null) {
            throw new IllegalArgumentException("argb 不能为 null");
        }
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            throw new IllegalArgumentException("图像尺寸非法：" + pixelWidth + "x" + pixelHeight);
        }
        if (argb.length < pixelWidth * pixelHeight) {
            throw new IllegalArgumentException("图像缓冲不足：需要 " + (pixelWidth * pixelHeight)
                    + " 个像素，给了 " + argb.length);
        }
        if (palette == null || palette.length == 0) {
            throw new IllegalArgumentException("调色板不能为空（颜色来源必须由调用方给，本类不内置色表）");
        }
    }

    private static Cell cell(int[] argb, int pixelWidth, int pixelHeight, int cellCol, int cellRow,
                             int[] palette, int[] histogram, int[] cellIndexes) {
        java.util.Arrays.fill(histogram, 0);
        final int px0 = cellCol * CELL_W;
        final int py0 = cellRow * CELL_H;
        for (int y = 0; y < CELL_H; y++) {
            final int py = py0 + y;
            for (int x = 0; x < CELL_W; x++) {
                final int px = px0 + x;
                final int slot = y * CELL_W + x;
                if (px >= pixelWidth || py >= pixelHeight) {
                    cellIndexes[slot] = -1;         // 图外的像素不参与（不编造颜色）
                    continue;
                }
                final int idx = nearestPaletteIndex(argb[py * pixelWidth + px], palette);
                cellIndexes[slot] = idx;
                histogram[idx]++;
            }
        }
        final int majority = mostFrequent(histogram, -1);
        final int minority = mostFrequent(histogram, majority);
        // A（标准极性：背景 = 多数色）；B（反极性）；取失配更少的，并列取 A（确定性）
        final Cell a = match(cellIndexes, minority, majority);
        final Cell b = match(cellIndexes, majority, minority);
        return b.mismatch() < a.mismatch() ? b : a;
    }

    /** 直方图里出现次数最多的索引；{@code exclude >= 0} 时跳过它；并列取小索引 */
    private static int mostFrequent(int[] histogram, int exclude) {
        int best = -1;
        int bestCount = -1;
        for (int i = 0; i < histogram.length; i++) {
            if (i == exclude) {
                continue;
            }
            if (histogram[i] > bestCount) {
                bestCount = histogram[i];
                best = i;
            }
        }
        return best < 0 ? 0 : best;
    }

    /**
     * 固定 fg 索引后，在 {@link TextFont8x8} 的 256 个字形里挑与"亮位"掩码失配最少的。
     *
     * <p>全暗格（没有任何像素落在 fg 索引上）特判成空格：否则一堆全 0 字形并列，
     * 按小字形号会挑到 {@code 0x00}（NUL）—— 在字符缓冲里合法但读不出来。</p>
     */
    private static Cell match(int[] cellIndexes, int fgIndex, int bgIndex) {
        boolean anyOn = false;
        for (final int idx : cellIndexes) {
            if (idx == fgIndex && fgIndex != bgIndex) {
                anyOn = true;
                break;
            }
        }
        if (!anyOn) {
            return new Cell(' ', bgIndex, bgIndex, 0);   // 全背景：画出来就是那个背景色
        }
        int bestCode = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int code = 0; code < TextFont8x8.GLYPH_COUNT; code++) {
            int score = 0;
            for (int y = 0; y < CELL_H; y++) {
                for (int x = 0; x < CELL_W; x++) {
                    final boolean want = cellIndexes[y * CELL_W + x] == fgIndex;
                    // 字模只覆盖格子的**上 GLYPH_H 行**（与 ScreenTextLayer.cellPixel 同一口径）：
                    // 其余行的期望值就是"背景"。
                    final boolean glyphOn = y < TextFont8x8.GLYPH_H && x < TextFont8x8.GLYPH_W
                            && TextFont8x8.pixel(code, x, y);
                    if (glyphOn != want) {
                        score++;
                    }
                }
            }
            if (score < bestScore) {
                bestScore = score;
                bestCode = code;
            }
        }
        // 字形号 == CP437 码页字节 ⇒ 字符就是它的 Unicode 码位；没有对应码位的字形不能写进
        // 字符缓冲（写进去是乱码）⇒ 退化成空格，绝不静默换一个别的字。
        final char ch = TextFont8x8.unicodeOf(bestCode);
        if (ch == 0) {
            return new Cell(' ', bgIndex, bgIndex, bestScore);
        }
        return new Cell(ch, fgIndex, bgIndex, bestScore);
    }
}
