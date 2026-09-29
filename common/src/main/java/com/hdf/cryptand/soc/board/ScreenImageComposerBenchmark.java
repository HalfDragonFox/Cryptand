package com.hdf.cryptand.soc.board;

import java.util.Random;

/**
 * ===== 合成器离线基准（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>三个场景，对应三种真实负载：</p>
 * <ol>
 *   <li><b>静态</b>：屏内容不变。格级脏合成上线后应当直接早退（优化前是 2.77ms/帧白付）。</li>
 *   <li><b>单格变化</b>：光标闪烁/局部刷新。只该重画那一个 8x16 格。</li>
 *   <li><b>全屏变化</b>：整屏刷新（滚动/图形动画）。这是最坏值，优化收益最小。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runScreenComposerBenchmark}</p>
 */
public final class ScreenImageComposerBenchmark {

    private static final int COLS = 50;
    private static final int ROWS = 16;
    private static final int W = COLS * TrueColorScreen.CELL_W;   // 400
    private static final int H = ROWS * TrueColorScreen.CELL_H;   // 256
    private static final int WARMUP = 30;
    private static final int ITERATIONS = 300;

    private ScreenImageComposerBenchmark() {
    }

    /** 可变字符源：测试通过它制造"变化"。 */
    private static final class MutableReader implements ScreenImageComposer.CellReader {
        final char[][] chars = new char[ROWS][COLS];
        final int[][] fg = new int[ROWS][COLS];
        final int[][] bg = new int[ROWS][COLS];

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
        final MutableReader reader = new MutableReader();
        final Random rnd = new Random(42L);
        final String glyphs = " ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,:;!?";
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                reader.chars[r][c] = glyphs.charAt(rnd.nextInt(glyphs.length()));
                reader.fg[r][c] = rnd.nextInt(0x1000000);
                reader.bg[r][c] = rnd.nextInt(0x1000000);
            }
        }

        final ScreenImageComposer composer = new ScreenImageComposer(W, H, 1, 1);
        final long samples = (long) W * H;

        // 场景 1：静态（每帧完全相同的数据）
        measure("静态（无变化）", composer, reader, ITERATIONS, i -> {
        }, samples);

        // 场景 2：单格变化（每帧只改一个字符格）
        measure("单格变化", composer, reader, ITERATIONS, i -> {
            final int c = i % COLS;
            final int r = (i / COLS) % ROWS;
            reader.chars[r][c] = glyphs.charAt((i + 1) % glyphs.length());
        }, samples);

        // 场景 3：全屏变化（每帧所有格的字符都变）
        measure("全屏变化（最坏）", composer, reader, ITERATIONS, i -> {
            final char ch = glyphs.charAt((i + 1) % glyphs.length());
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    reader.chars[r][c] = ch;
                }
            }
        }, samples);

        System.out.println("[composer-bench] 对照：优化前（逐点全量重算）离线实测 2.770 ms/帧（27.05 ns/采样点）");
    }

    private interface Mutator {
        void mutate(int iteration);
    }

    private static void measure(String what, ScreenImageComposer composer, MutableReader reader,
                                int iterations, Mutator mutator, long samples) {
        for (int i = 0; i < WARMUP; i++) {
            mutator.mutate(i);
            composer.composeText(COLS, ROWS, reader);
            composer.markClean();
        }
        long total = 0L;
        long best = Long.MAX_VALUE;
        int changedRows = 0;
        for (int i = 0; i < iterations; i++) {
            mutator.mutate(i);
            final long t0 = System.nanoTime();
            changedRows += composer.composeText(COLS, ROWS, reader);
            final long dt = System.nanoTime() - t0;
            total += dt;
            if (dt < best) {
                best = dt;
            }
            composer.markClean();
        }
        final double avgMs = total / 1_000_000.0 / iterations;
        final double bestMs = best / 1_000_000.0;
        final double nsPerSample = (double) total / iterations / samples;
        System.out.printf("[composer-bench] %-14s 平均 %.3f ms/帧（最优 %.3f），%.2f ns/采样点，平均变化行 %.1f%n",
                what, avgMs, bestMs, nsPerSample, (double) changedRows / iterations);
    }
}
