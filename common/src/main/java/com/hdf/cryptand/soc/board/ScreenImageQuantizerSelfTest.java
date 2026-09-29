package com.hdf.cryptand.soc.board;

/**
 * ===== 图形面 → 字符格量化闸门（common，纯 Java 零 MC，2026-09-27 任务 G）=====
 *
 * <p>钉死三件事：</p>
 * <ol>
 *   <li><b>往回对拍</b>：拿 <b>256 个字形全部</b>用 {@link ScreenTextLayer#cellPixel}
 *       （字符 → 像素的**唯一**定义）造出 8×8 图，再让 {@link ScreenImageQuantizer} 量化回去、
 *       用同一个 cellPixel 画回来 —— 必须**逐像素相同**；极性无歧义的那一大票字形还要求
 *       连（字符 + fg + bg）三元组都还原。两个方向共用一份字模/调色板，这两条断言就是证明。</li>
 *   <li><b>降色</b>：图像里出现调色板外的颜色时，必须落到**就近**索引
 *       （{@link ScreenImageQuantizer#nearestPaletteIndex} 是全项目唯一那条距离算式）；
 *       单色格/全暗格/图外格都是确定解，不编造。</li>
 *   <li><b>边角</b>：格数大于图像（多出来的格 ⇒ 空格）、图像大于格数（多出来的像素丢弃）、
 *       越界与非法输入明确报错。</li>
 * </ol>
 *
 * <p>跑法（离线闸门）：{@code java -cp <common classes> com.hdf.cryptand.soc.board.ScreenImageQuantizerSelfTest}
 * （gradle 任务登记需要 {@code common/build.gradle} 授权，见任务 G 报告）。</p>
 */
public final class ScreenImageQuantizerSelfTest {

    private static int passed;
    private static int failed;

    /** 一格多少像素（与设备/量化器同一常量） */
    private static final int CELL_W = ScreenImageQuantizer.CELL_W;
    private static final int CELL_H = ScreenImageQuantizer.CELL_H;

    /** 测试用调色板：16 档（黑、蓝、绿、青、红、品、棕、灰、深灰与亮色）—— 就是设备调色板传进来的样子 */
    private static final int[] PALETTE = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    public static void main(String[] args) {
        roundTripAllGlyphs();
        nearestPalette();
        singleColorCells();
        blankAndOutOfImage();
        imageBiggerThanScreen();
        invalidInputs();

        System.out.println("[IMAGE-QUANTIZER] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 1. 256 字形往返（唯一字形来源的证明） ====================

    private static final int FG_INDEX = 10;      // 亮绿
    private static final int BG_INDEX = 1;       // 蓝

    /**
     * 对每个字形：用 {@link ScreenTextLayer#cellPixel}（字符 → 像素的唯一定义）造一张 8×8 图，
     * 让量化器把它转回字符格，再用 cellPixel 把结果画回像素 —— 必须与原图**逐像素相同**。
     *
     * <p>为什么断言"画回来一样"而不是"三元组一样"：当亮位比暗位多时，正确的表示是**反极性**
     * （fg/bg 互换 + 取补字形），画出来与原图一模一样，但三元组不是同一个 —— 那是对的，不是误差。
     * 所以还额外断言"亮位少于暗位"的那一大票字形必须连三元组也还原（极性无歧义）。</p>
     */
    private static void roundTripAllGlyphs() {
        final int[] image = new int[CELL_W * CELL_H];
        final int[] back = new int[CELL_W * CELL_H];
        int pixelExact = 0;
        int tripleExact = 0;
        int unambiguous = 0;
        int considered = 0;
        final StringBuilder firstBad = new StringBuilder();
        final StringBuilder firstTripleBad = new StringBuilder();
        for (int code = 0; code < TextFont8x8.GLYPH_COUNT; code++) {
            final char ch = TextFont8x8.unicodeOf(code);
            if (ch == 0) {
                continue;                       // 没有 Unicode 码位的字形不进字符缓冲（见实现注释）
            }
            considered++;
            int on = 0;
            for (int y = 0; y < CELL_H; y++) {
                for (int x = 0; x < CELL_W; x++) {
                    image[y * CELL_W + x] = ScreenTextLayer.cellPixel(ch,
                            PALETTE[FG_INDEX], PALETTE[BG_INDEX], x, y);
                    // 字模只覆盖格子的上 GLYPH_H 行（8×16 格的下半是背景）
                    if (y < TextFont8x8.GLYPH_H && x < TextFont8x8.GLYPH_W && TextFont8x8.pixel(code, x, y)) {
                        on++;
                    }
                }
            }
            final int[] out = new int[3];
            ScreenImageQuantizer.quantizeCell(image, CELL_W, CELL_H, 0, 0, PALETTE, out);
            for (int y = 0; y < CELL_H; y++) {
                for (int x = 0; x < CELL_W; x++) {
                    back[y * CELL_W + x] = ScreenTextLayer.cellPixel((char) out[0],
                            PALETTE[out[1]], PALETTE[out[2]], x, y);
                }
            }
            boolean same = true;
            for (int i = 0; i < image.length; i++) {
                if (image[i] != back[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                pixelExact++;
            } else if (firstBad.length() == 0) {
                firstBad.append("code=").append(code).append(" ch=U+")
                        .append(Integer.toHexString(ch)).append(" got=U+")
                        .append(Integer.toHexString(out[0])).append("/fg=").append(out[1])
                        .append("/bg=").append(out[2]);
            }
            if (on * 2 < CELL_W * CELL_H) {          // 亮位少于暗位 ⇒ 极性无歧义
                unambiguous++;
                // 全暗字形（空格 0x20、NBSP 0xFF 这些位图全 0 的字形）统一归一成空格：
                // "纯背景"在字符缓冲里只有一种表示（fg = bg），这是有意的归一，不是丢信息。
                final boolean blank = TextFont8x8.blank(code);
                final boolean ok = blank
                        ? (out[0] == ' ' && out[1] == out[2])
                        : (out[0] == ch && out[1] == FG_INDEX && out[2] == BG_INDEX);
                if (ok) {
                    tripleExact++;
                } else if (firstTripleBad.length() == 0) {
                    firstTripleBad.append("code=").append(code).append(" ch=U+")
                            .append(Integer.toHexString(ch)).append(" got=U+")
                            .append(Integer.toHexString(out[0])).append("/fg=").append(out[1])
                            .append("/bg=").append(out[2]);
                }
            }
        }
        check("有码位的字形（" + considered + " 个）量化后再由 ScreenTextLayer 画回来 **逐像素相同**"
                        + "（同一份字模/调色板）",
                pixelExact == considered);
        if (pixelExact != considered) {
            System.out.println("        首个画不回来的： " + firstBad);
        }
        check("亮位少于暗位的字形（极性无歧义）连三元组也还原（字符 + fg + bg）",
                tripleExact == unambiguous);
        if (tripleExact != unambiguous) {
            System.out.println("        首个三元组不还原的： " + firstTripleBad);
        }
        check("无歧义字形确实占多数（≥ 150，不是只测了几个）", unambiguous >= 150);
        check("空格格的往返也一致（空格 = 全背景）", roundTrip(' ', 7, 3));
        check("实心块（亮位占多数）也画得出原图（0xDB 全亮 ⇒ 纯背景表示）",
                roundTrip('\u2588', FG_INDEX, BG_INDEX));
    }

    private static boolean roundTrip(char ch, int fgIndex, int bgIndex) {
        final int[] image = new int[CELL_W * CELL_H];
        final int[] back = new int[CELL_W * CELL_H];
        for (int y = 0; y < CELL_H; y++) {
            for (int x = 0; x < CELL_W; x++) {
                image[y * CELL_W + x] = ScreenTextLayer.cellPixel(ch,
                        PALETTE[fgIndex], PALETTE[bgIndex], x, y);
            }
        }
        final int[] out = new int[3];
        ScreenImageQuantizer.quantizeCell(image, CELL_W, CELL_H, 0, 0, PALETTE, out);
        for (int y = 0; y < CELL_H; y++) {
            for (int x = 0; x < CELL_W; x++) {
                back[y * CELL_W + x] = ScreenTextLayer.cellPixel((char) out[0],
                        PALETTE[out[1]], PALETTE[out[2]], x, y);
            }
        }
        for (int i = 0; i < image.length; i++) {
            if (image[i] != back[i]) {
                return false;
            }
        }
        return true;
    }

    // ==================== 2. 就近色（调色板是颜色唯一来源） ====================

    private static void nearestPalette() {
        check("白 → 最近的白索引",
                ScreenImageQuantizer.nearestPaletteIndex(0xFFFFFF, PALETTE) == 15);
        check("黑 → 最近的黒索引",
                ScreenImageQuantizer.nearestPaletteIndex(0x000000, PALETTE) == 0);
        // 纯红 0x00FF00 到索引 2（0x00AA00，距离 85²）比到索引 10（0x55FF55，距离 2×85²）更近
        check("调色板外的颜色落到最近档（0x00FF00 → 2 号绿，不是 0）",
                ScreenImageQuantizer.nearestPaletteIndex(0x00FF00, PALETTE) == 2);
        check("并列取小索引（单色板恒 0）",
                ScreenImageQuantizer.nearestPaletteIndex(0x123456, new int[]{0x000000}) == 0);
    }

    // ==================== 3. 单色格 ====================

    private static void singleColorCells() {
        // 2×2 个字符格（格 = 8×16 像素）⇒ 图像 16×32
        final int[] image = new int[(2 * CELL_W) * (2 * CELL_H)];
        java.util.Arrays.fill(image, PALETTE[5]);          // 整屏一个颜色
        final ScreenImageQuantizer.Result r =
                ScreenImageQuantizer.quantize(image, 2 * CELL_W, 2 * CELL_H, 2, 2, PALETTE);
        boolean allBlank = true;
        boolean bgRight = true;
        for (int i = 0; i < r.cells(); i++) {
            allBlank &= r.chars()[i] == ' ';
            bgRight &= r.bg()[i] == 5;
        }
        check("单色图 ⇒ 全部空格（没有可画的字形）", allBlank);
        check("单色图 ⇒ 背景就是那个颜色（不是黑）", bgRight);
    }

    // ==================== 4. 空格格 / 图外格 ====================

    private static void blankAndOutOfImage() {
        final int[] image = new int[8 * 8];
        java.util.Arrays.fill(image, PALETTE[0]);
        final ScreenImageQuantizer.Result r =
                ScreenImageQuantizer.quantize(image, 8, 8, 4, 4, PALETTE);
        boolean outOfImageBlank = true;
        boolean outOfImageZero = true;
        for (int row = 1; row < 4; row++) {
            for (int col = 1; col < 4; col++) {
                outOfImageBlank &= r.charAt(col, row) == ' ';
                outOfImageZero &= r.fgAt(col, row) == 0 && r.bgAt(col, row) == 0;
            }
        }
        check("格数 > 图像 ⇒ 图外的格是空格（不编造字形）", outOfImageBlank);
        check("图外格的 fg/bg 是确定的 0（不是随机/上次残留）", outOfImageZero);
        check("格坐标越界明确报错", fails(() -> r.charAt(4, 0), "越界"));
    }

    // ==================== 5. 图像大于格数 ====================

    private static void imageBiggerThanScreen() {
        final int[] image = new int[32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                image[y * 32 + x] = (x < 8) ? PALETTE[12] : PALETTE[0];   // 左八列红
            }
        }
        final ScreenImageQuantizer.Result r =
                ScreenImageQuantizer.quantize(image, 32, 32, 2, 2, PALETTE);
        check("图像比格数大 ⇒ 只取左上 cols×CELL 区域（不是缩放）",
                r.bgAt(0, 0) == 12 && r.bgAt(1, 0) == 0);
        check("量化格数 = cols × rows", r.cells() == 4);
    }

    // ==================== 6. 非法输入 ====================

    private static void invalidInputs() {
        check("argb = null 明确报错",
                fails(() -> ScreenImageQuantizer.quantize(null, 8, 8, 1, 1, PALETTE), "不能为 null"));
        check("图像缓冲不足明确报错",
                fails(() -> ScreenImageQuantizer.quantize(new int[4], 8, 8, 1, 1, PALETTE), "缓冲不足"));
        check("调色板为空明确报错",
                fails(() -> ScreenImageQuantizer.quantize(new int[64], 8, 8, 1, 1, new int[0]), "调色板"));
        check("格尺寸非法明确报错",
                fails(() -> ScreenImageQuantizer.quantize(new int[64], 8, 8, 0, 1, PALETTE), "字符格尺寸非法"));
        check("图像尺寸非法明确报错",
                fails(() -> ScreenImageQuantizer.quantize(new int[64], 0, 8, 1, 1, PALETTE), "图像尺寸非法"));
    }

    // ==================== 小工具 ====================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static boolean fails(Runnable body, String expectContains) {
        try {
            body.run();
            System.out.println("        （预期抛异常，但没抛）");
            return false;
        } catch (RuntimeException e) {
            final String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.contains(expectContains)) {
                System.out.println("        （异常文本不含 \"" + expectContains + "\"：\"" + msg + "\"）");
                return false;
            }
            return true;
        }
    }
}
