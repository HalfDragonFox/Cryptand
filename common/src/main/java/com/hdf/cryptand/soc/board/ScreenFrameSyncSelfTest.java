package com.hdf.cryptand.soc.board;

/**
 * ===== 屏幕帧同步（合成 / 距离采样 / 限帧）离线闸门（2026-09-28，纯 Java 零 MC）=====
 *
 * <p>跑法：{@code ./gradlew :common:runScreenFrameSyncTest}</p>
 *
 * <p>钉住三件事：① 服务器侧把屏内容合成成 ARGB 图像（TEXT 口径与 {@link ScreenTextLayer} 一致）；
 * ② 按距离决定横竖采样步长（2 的幂、单调、夹紧）；③ 每台机器的回帧上限（默认 60 fps）。</p>
 */
public final class ScreenFrameSyncSelfTest {

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) {
        System.out.println("=== Screen frame sync self test (composer + sampling + limiter) ===");

        limiter();
        sampling();
        composerText();
        composerCells();
        composerGraphics();
        composerSampling();
        rangesAndGuards();

        System.out.println("=== ScreenFrameSync " + passed + "/" + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 限帧

    private static void limiter() {
        section = "限帧器";
        final FrameRateLimiter l = new FrameRateLimiter(60.0);
        check("60fps ⇒ 间隔 16.66ms", l.intervalNanos() == 16_666_666L);
        long sent = 0;
        long dropped = 0;
        for (long ns = 0; ns < 1_000_000_000L; ns += 1_000_000L) {         // 1 秒，每 1ms 试一次
            if (l.tryAcquire(ns)) {
                sent++;
            } else {
                dropped++;
            }
        }
        check("1 秒内放行 ≈ 60 帧（实得 " + sent + "）", sent >= 59 && sent <= 61);
        check("其余全记丢弃（实得 " + dropped + "）", dropped == 1000 - sent);
        check("第一帧永远放行（新限帧器）", new FrameRateLimiter(60.0).tryAcquire(123L));

        final FrameRateLimiter unlimited = new FrameRateLimiter(0.0);
        boolean all = true;
        for (int i = 0; i < 100; i++) {
            all &= unlimited.tryAcquire(i);
        }
        check("fps<=0 ⇒ 不限速", all);

        final FrameRateLimiter r = new FrameRateLimiter(60.0);
        r.tryAcquire(0L);
        check("未到点不放行", !r.tryAcquire(1_000_000L));
        check("到点放行", r.tryAcquire(20_000_000L));
        r.reset(0L);
        check("reset 后新基准第一帧放行", r.tryAcquire(0L));
    }

    // ------------------------------------------------------------------ 采样策略

    private static void sampling() {
        section = "采样策略";
        // 64 像素宽、1 格大的屏，ppr=771：
        //   d=1  → 64*1/771  = 0.08 → 1
        //   d=32 → 64*32/771 = 2.66 → 4（2 的幂向上）
        //   d=64 → 5.31      → 8
        check("贴着屏 ⇒ 1", ScreenSamplingPolicy.stepFor(1, 64, 1, 771.0, 8) == 1);
        check("32 格 ⇒ 4", ScreenSamplingPolicy.stepFor(32, 64, 1, 771.0, 8) == 4);
        check("64 格 ⇒ 8", ScreenSamplingPolicy.stepFor(64, 64, 1, 771.0, 8) == 8);
        check("极远 ⇒ 夹在上限 8", ScreenSamplingPolicy.stepFor(10_000, 64, 1, 771.0, 8) == 8);
        check("上限可配（maxStep=2）", ScreenSamplingPolicy.stepFor(10_000, 64, 1, 771.0, 2) == 2);
        check("距离<=0 不炸", ScreenSamplingPolicy.stepFor(0, 64, 1, 771.0, 8) == 1);
        check("像素<=0 ⇒ 1", ScreenSamplingPolicy.stepFor(10, 0, 1, 771.0, 8) == 1);

        boolean mono = true;
        int last = 1;
        for (int d = 1; d <= 200; d++) {
            final int s = ScreenSamplingPolicy.stepFor(d, 128, 1, 771.0, 8);
            mono &= s >= last;
            last = s;
        }
        check("随距离单调不降", mono);

        check("网格 ceil(65/4)=17", ScreenSamplingPolicy.gridSize(65, 4) == 17);
        check("网格至少 1", ScreenSamplingPolicy.gridSize(1, 8) == 1);
    }

    // ------------------------------------------------------------------ 字符源合成（不依赖设备）

    private static void composerCells() {
        section = "字符源合成";
        final char[][] chars = new char[2][8];
        final int[][] fg = new int[2][8];
        final int[][] bg = new int[2][8];
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 8; c++) {
                chars[r][c] = ' ';
                fg[r][c] = 0xFFFFFF;
                bg[r][c] = 0x000000;
            }
        }
        final ScreenImageComposer.CellReader reader = new ScreenImageComposer.CellReader() {
            @Override
            public char charAt(int col, int row) {
                return chars[row - 1][col - 1];
            }

            @Override
            public int foregroundAt(int col, int row) {
                return fg[row - 1][col - 1];
            }

            @Override
            public int backgroundAt(int col, int row) {
                return bg[row - 1][col - 1];
            }
        };
        final ScreenImageComposer c = new ScreenImageComposer(64, 32);
        check("字符源：首次全脏", c.fullDirty() && c.composeText(8, 2, reader) == 32);
        check("字符源：空格黑底 ⇒ 不透明黑", c.image()[0] == 0xFF000000);
        c.markClean();
        check("字符源：无变化 ⇒ 0 行", c.composeText(8, 2, reader) == 0 && c.dirtyRanges().length == 0);

        chars[0][1] = 'A';
        fg[0][1] = 0x00FF00;
        final int changed = c.composeText(8, 2, reader);
        check("字符源：改一格 ⇒ 只脏该格所在的像素带（实得 " + changed + "）", changed >= 1 && changed <= 16);
        check("字符源：'A' 前景像素", c.image()[3 * c.gridWidth() + 9] == 0xFF00FF00);
        boolean band2Clean = true;
        for (int y = 16; y < 32; y++) {
            band2Clean &= !c.dirtyRows()[y];
        }
        check("字符源：不动第 2 字符行", band2Clean);
        c.markClean();

        chars[1][0] = '\u4E00';
        bg[1][0] = 0x123456;
        c.composeText(8, 2, reader);
        check("字符源：未识别字符计数 = 1", c.unknownGlyphs() == 1);
        check("字符源：未识别格只画背景", c.image()[16 * c.gridWidth() + 0] == (0xFF000000 | 0x123456));
        c.markClean();

        boolean threw = false;
        try {
            c.composeText(0, 2, reader);
        } catch (final IllegalArgumentException expected) {
            threw = true;
        }
        check("字符源：格数非法 ⇒ 明确报错", threw);
    }
    // ------------------------------------------------------------------ TEXT 合成

    private static void composerText() {
        section = "TEXT 合成";
        final TrueColorScreen dev = TrueColorScreen.of(64, 32, 8);
        dev.setMode(TrueColorScreen.Mode.TEXT);
        check("64x32 字符格 = 8x2", dev.textCols() == 8 && dev.textRows() == 2);

        for (int row = 1; row <= dev.textRows(); row++) {
            for (int col = 1; col <= dev.textCols(); col++) {
                dev.setText(col, row, ' ', 0xFFFFFF, 0x000000);
            }
        }
        final ScreenImageComposer c = new ScreenImageComposer(dev.width(), dev.height());
        check("首次合成 = 全脏", c.fullDirty());
        check("首次变化行数 = 全部行", c.compose(dev) == c.gridHeight());
        check("空格+黑底 ⇒ 整幅不透明黑", c.image()[0] == 0xFF000000 && c.image()[c.image().length - 1] == 0xFF000000);
        c.markClean();
        check("无变化 ⇒ 0 行", c.compose(dev) == 0 && c.dirtyRanges().length == 0);

        // 第 1 字符行 = 像素行 0..15（CELL_H=16）
        dev.setText(2, 1, 'A', 0x00FF00, 0x000000);
        final int changed1 = c.compose(dev);
        final int[] r = c.dirtyRanges();
        check("改一格 ⇒ 脏行落在第 1 字符行的像素带内 [0,16)（实得 "
                + java.util.Arrays.toString(r) + "，共 " + changed1 + " 行）",
                changed1 >= 1 && changed1 <= 16 && r.length == 2 && r[0] == 0 && r[1] > 0 && r[1] <= 16);
        check("'A' 的前景像素确实画出来了", c.image()[3 * c.gridWidth() + 9] == 0xFF00FF00);
        check("该行必被标脏（有前景就必脏）", c.dirtyRows()[3]);
        boolean secondBandClean = true;
        for (int y = 16; y < 32; y++) {                                  // 第 2 字符行的像素带
            secondBandClean &= !c.dirtyRows()[y];
        }
        check("改第 1 字符行不影响第 2 字符行", secondBandClean);
        final int bgPixel = c.image()[15 * c.gridWidth() + 0];              // 格内 y=15 ≥ GLYPH_H ⇒ 必是背景
        check("字模只占上 8 行（y=15 是背景）", bgPixel == 0xFF000000);
        boolean anyFg = false;
        for (int y = 0; y < 8; y++) {
            for (int x = 8; x < 16; x++) {                       // 第 2 格（col=2 ⇒ x ∈ [8,16)）
                if (c.image()[y * c.gridWidth() + x] == 0xFF00FF00) {
                    anyFg = true;
                }
            }
        }
        check("'A' 画出了前景像素", anyFg);
        c.markClean();

        // 第 2 字符行 ⇒ 像素行 16..31
        dev.setText(1, 2, 'B', 0xFFFFFF, 0x000000);
        final int changed2 = c.compose(dev);
        final int[] r2 = c.dirtyRanges();
        check("改第 2 字符行 ⇒ 脏行落在 [16,32)（实得 " + java.util.Arrays.toString(r2) + "）",
                changed2 >= 1 && changed2 <= 16 && r2.length == 2 && r2[0] >= 16 && r2[1] <= 32);
        boolean firstBandClean = true;
        for (int y = 0; y < 16; y++) {
            firstBandClean &= !c.dirtyRows()[y];
        }
        check("改第 2 字符行不影响第 1 字符行", firstBandClean);
        c.markClean();

        // ---- 矩形脏块（2026-09-29 用户要求：按矩形下发，不再整行）----
        dev.setText(3, 1, 'A', 0x00FF00, 0x000000);          // col=3 ⇒ 像素 x ∈ [16,24)
        c.compose(dev);
        final int[] rects = c.dirtyRects();
        check("矩形：改一格 ⇒ 有矩形（实得 " + java.util.Arrays.toString(rects) + "）",
                rects.length >= 4 && rects.length % 4 == 0);
        boolean rowsInBand = true;
        boolean colsInCell = true;
        long pixels = 0L;
        for (int k = 0; k < rects.length; k += 4) {
            rowsInBand &= rects[k] >= 0 && rects[k] + rects[k + 1] <= 16;
            colsInCell &= rects[k + 2] >= 16 && rects[k + 3] <= 24;   // 第 4 个是 colToExclusive
            pixels += (long) rects[k + 1] * (rects[k + 3] - rects[k + 2]);
        }
        check("矩形：所有行范围都在第 1 字符行的像素带 [0,16) 内（实得 "
                + java.util.Arrays.toString(rects) + "）", rowsInBand);
        check("矩形：所有列范围都被裁在该格 [16,24) 内（字模逐行形状不同 ⇒ 会拆成多块，这是对的；实得 "
                + java.util.Arrays.toString(rects) + "）", colsInCell);
        check("矩形：像素总量远少于整行（" + pixels + " < " + (16L * c.gridWidth()) + "）",
                pixels < 16L * c.gridWidth());
        check("矩形：与行脏标记一致", c.dirtyRows()[3]);
        c.markClean();
        check("清脏后：无矩形", c.dirtyRects().length == 0);

        // 相邻两字符行、**整格背景**都变 ⇒ 每行列范围同为该格 ⇒ 合并成一个跨两行的矩形
        dev.setText(5, 1, ' ', 0x000000, 0xFF0000);          // col=5 ⇒ 像素 x ∈ [40,48)
        dev.setText(5, 2, ' ', 0x000000, 0xFF0000);
        c.compose(dev);
        final int[] merged = c.dirtyRects();
        check("矩形：相邻行同列范围 ⇒ 合并成一个矩形（实得 " + java.util.Arrays.toString(merged) + "）",
                merged.length == 4 && merged[0] == 0 && merged[1] == 32
                        && merged[2] == 32 && merged[3] == 40);
        c.markClean();
        c.markAllDirty();
        final int[] allRects = c.dirtyRects();
        check("全脏 ⇒ 整幅一个矩形", allRects.length == 4 && allRects[0] == 0
                && allRects[1] == c.gridHeight() && allRects[2] == 0 && allRects[3] == c.gridWidth());
        c.markClean();

        // 字体里没有的字符：只画背景 + 计数（不编造字形）
        char unknownChar = 0;
        for (char ch = 0x4E00; ch < 0x9FFF; ch++) {
            if (TextFont8x8.glyphOf(ch) < 0) {
                unknownChar = ch;
                break;
            }
        }
        check("找到一个字体里没有的字（U+" + Integer.toHexString(unknownChar) + "）", unknownChar != 0);
        dev.setText(3, 1, unknownChar, 0xFFFFFF, 0x123456);
        c.compose(dev);
        check("未识别字符计数 = 1", c.unknownGlyphs() == 1);
        check("未识别格整格背景色", c.image()[3 * c.gridWidth() + 16] == (0xFF000000 | 0x123456)
                && c.image()[0 * c.gridWidth() + 16] == (0xFF000000 | 0x123456));
        c.markClean();
    }

    // ------------------------------------------------------------------ GRAPHICS 合成

    private static void composerGraphics() {
        section = "GRAPHICS 合成";
        final TrueColorScreen dev = TrueColorScreen.of(64, 32, 16);
        dev.setMode(TrueColorScreen.Mode.GRAPHICS);
        final ScreenImageComposer c = new ScreenImageComposer(dev.width(), dev.height());
        c.compose(dev);
        c.markClean();

        dev.setRgb(5, 7, 0x00FF00);
        check("改一个像素 ⇒ 1 行脏", c.compose(dev) == 1);
        final int[] r = c.dirtyRanges();
        check("脏区间 = [7,8)", r.length == 2 && r[0] == 7 && r[1] == 8);
        check("像素值 = 设备读回", (c.image()[7 * c.gridWidth() + 5] & 0xFFFFFF) == (dev.rgbAt(5, 7) & 0xFFFFFF));
        check("是绿色", (c.image()[7 * c.gridWidth() + 5] & 0x00FF00) != 0);
        c.markClean();
        check("无变化 ⇒ 0 行", c.compose(dev) == 0);

        dev.setRgb(63, 31, 0xFF0000);
        check("边角像素也能脏一行", c.compose(dev) == 1);
        c.markClean();
    }

    // ------------------------------------------------------------------ 采样接入

    private static void composerSampling() {
        section = "采样接入";
        final TrueColorScreen dev = TrueColorScreen.of(64, 32, 16);
        dev.setMode(TrueColorScreen.Mode.GRAPHICS);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                dev.setRgb(x, y, 0x000000);
            }
        }
        final ScreenImageComposer c = new ScreenImageComposer(dev.width(), dev.height());
        c.compose(dev);
        c.markClean();

        check("换采样步长返回 true", c.setSampling(2, 2));
        check("换采样 ⇒ 全脏（必须重发全量）", c.fullDirty());
        check("网格 32x16", c.gridWidth() == 32 && c.gridHeight() == 16);
        check("全脏时变化行 = 全部 16 行", c.compose(dev) == 16);
        check("同一步长再设 ⇒ false", !c.setSampling(2, 2));
        c.markClean();
        dev.setRgb(10, 10, 0xFFFFFF);
        check("采样下改一个像素仍只脏 1 行", c.compose(dev) == 1);
        c.markClean();
    }

    // ------------------------------------------------------------------ 纯函数与守卫

    private static void rangesAndGuards() {
        section = "区间与守卫";
        final boolean[] d = new boolean[8];
        d[3] = true;
        d[4] = true;
        d[7] = true;
        final int[] merged = ScreenImageComposer.mergeRanges(d);
        check("相邻行合并：{3,4,7} ⇒ [3,5,7,8]",
                merged.length == 4 && merged[0] == 3 && merged[1] == 5 && merged[2] == 7 && merged[3] == 8);
        check("空输入 ⇒ 空区间", ScreenImageComposer.mergeRanges(new boolean[0]).length == 0);
        check("全假 ⇒ 空区间", ScreenImageComposer.mergeRanges(new boolean[4]).length == 0);

        final TrueColorScreen dev = TrueColorScreen.of(64, 32, 8);
        dev.setMode(TrueColorScreen.Mode.TEXT);
        boolean threw = false;
        try {
            new ScreenImageComposer(32, 32).compose(dev);
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        check("几何不符 ⇒ 明确报错（不静默重采样）", threw);

        boolean threwNull = false;
        try {
            new ScreenImageComposer(64, 32).compose(null);
        } catch (final IllegalArgumentException expected) {
            threwNull = true;
        }
        check("null 设备 ⇒ 明确报错", threwNull);
    }

    // ------------------------------------------------------------------ 计数

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + section + " · " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + section + " · " + name);
        }
    }
}
