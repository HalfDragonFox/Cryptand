package com.hdf.cryptand.soc.board;

/**
 * ===== 格级脏合成闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>2026-09-29 把 `composeText` 从"每帧全量重算 102400 个采样点"改成
 * "缓存每格 (char,fg,bg) + 只重画变化格 + 一格没变就早退"。
 * 这个优化**改变了对外行为**（同一份字符源连合成两次，第二次返回 0），必须钉住：</p>
 *
 * <ul>
 *   <li>静态早退：无变化 ⇒ 返回 0、无脏区间（优化前是每帧 2.77ms）</li>
 *   <li>只重画变化格：改一格 ⇒ 脏区间只落在那一格的列范围</li>
 *   <li><b>未变格沿用上一帧</b>：改一格不能污染其它格的像素（缓存接错就会串色）</li>
 *   <li>几何/采样变化 ⇒ 缓存作废，回到全量（不能拿旧 image 当内容）</li>
 * </ul>
 *
 * <p>跑法：{@code ./gradlew :common:runScreenImageComposerTest}</p>
 */
public final class ScreenImageComposerSelfTest {

    private static final int COLS = 3;
    private static final int ROWS = 2;
    private static final int W = COLS * TrueColorScreen.CELL_W;   // 24
    private static final int H = ROWS * TrueColorScreen.CELL_H;   // 32

    private static int passed;
    private static int failed;

    private ScreenImageComposerSelfTest() {
    }

    private static final class Reader implements ScreenImageComposer.CellReader {
        final char[][] chars = new char[ROWS][COLS];
        final int[][] fg = new int[ROWS][COLS];
        final int[][] bg = new int[ROWS][COLS];

        Reader() {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    chars[r][c] = 'A';
                    fg[r][c] = 0xFFFFFF;
                    bg[r][c] = 0x000000;
                }
            }
        }

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
    }

    public static void main(String[] args) {
        final Reader reader = new Reader();
        final ScreenImageComposer c = new ScreenImageComposer(W, H, 1, 1);

        // 首次：全量（缓存无效）
        final int first = c.composeText(COLS, ROWS, reader);
        check("首次合成有变化", first > 0);
        check("首次是全脏", c.fullDirty());
        c.markClean();

        // 静态早退：同一份数据再合成 ⇒ 0 变化、无脏区间
        final int second = c.composeText(COLS, ROWS, reader);
        check("静态：第二次返回 0（格级早退）", second == 0);
        check("静态：无脏矩形", c.dirtyRects().length == 0);

        // 记下"改格之前"的整幅像素，用于断言未变格不被污染
        final int[] before = c.image().clone();

        // 改第 2 行第 1 列的字符（格索引 row=2,col=1）
        reader.chars[1][0] = 'B';
        final int changed = c.composeText(COLS, ROWS, reader);
        check("改一格：有变化", changed > 0);
        // 矩形口径：[row0, rowCount0, colFrom0, colToExclusive0, ...]
        final int[] rects = c.dirtyRects();
        check("改一格：脏矩形存在", rects.length >= 4);
        if (rects.length >= 4) {
            final int row0 = rects[0];
            final int rowCount = rects[1];
            final int colFrom = rects[2];
            final int colTo = rects[3];
            check("改一格：脏列只落在第 1 格（" + colFrom + ".." + colTo + " ⊂ 0..8）",
                    colFrom >= 0 && colTo <= TrueColorScreen.CELL_W);
            check("改一格：脏行从第二字符格起（row0=" + row0 + "）", row0 >= TrueColorScreen.CELL_H);
            check("改一格：最多一个字符格高（rowCount=" + rowCount + "）", rowCount <= TrueColorScreen.CELL_H);
        }

        // 关键：未变格必须沿用上一帧像素（第 1 行那 3 个格一个像素都不许变）
        final int[] after = c.image();
        boolean untouched = true;
        for (int y = 0; y < TrueColorScreen.CELL_H && untouched; y++) {
            for (int x = 0; x < W; x++) {
                if (after[y * W + x] != before[y * W + x]) {
                    untouched = false;
                    break;
                }
            }
        }
        check("改一格：第一行三格像素一字未动（未变格沿用上一帧）", untouched);

        // 变化格确实被重画了（第 2 行第 1 列该和以前不同）
        boolean repainted = false;
        for (int y = TrueColorScreen.CELL_H; y < H && !repainted; y++) {
            for (int x = 0; x < TrueColorScreen.CELL_W; x++) {
                if (after[y * W + x] != before[y * W + x]) {
                    repainted = true;
                    break;
                }
            }
        }
        check("改一格：该格确实被重画", repainted);
        c.markClean();

        // 再静态一次：仍然早退
        check("改完再静态：返回 0", c.composeText(COLS, ROWS, reader) == 0);
        c.markClean();

        // 背景色变化 ⇒ 整格 8x16 都要变（格级比较必须覆盖 bg，而不只是字符）
        final int[] beforeBg = c.image().clone();
        reader.bg[0][2] = 0xFF0000;
        check("改背景：有变化", c.composeText(COLS, ROWS, reader) > 0);
        boolean bgRepainted = false;
        final int[] afterBg = c.image();
        for (int y = 0; y < TrueColorScreen.CELL_H && !bgRepainted; y++) {
            for (int x = 2 * TrueColorScreen.CELL_W; x < 3 * TrueColorScreen.CELL_W; x++) {
                if (afterBg[y * W + x] != beforeBg[y * W + x]) {
                    bgRepainted = true;
                    break;
                }
            }
        }
        check("改背景：整格重画", bgRepainted);
        c.markClean();

        // 采样变化 ⇒ 缓存作废，回到全量（否则会拿旧 image 当内容）
        c.setSampling(2, 2);
        check("采样变化：回到全脏", c.fullDirty());
        check("采样变化：重新合成有变化", c.composeText(COLS, ROWS, reader) > 0);

        System.out.println("[composer] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
