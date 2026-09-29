package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 上屏计划的展开（common，纯 Java 零 MC，2026-09-18）=====
 *
 * <p>{@link DisplayPresenter} 只回答"<b>走 GPU 还是 CPU、要搬几次</b>"；本类把这一帧
 * <b>展开成平台层要执行的调用序列</b>。分开的理由：决策是纯算术（可离线钉死），
 * 展开要碰字符与颜色（同样纯 Java），而"真的去调 OC 组件"只有平台层做得到。</p>
 *
 * <h3>为什么按「同色分段」而不是逐格</h3>
 * <p>OC 写显存页时，每格的字符色由"当前前景/背景"决定 ⇒ 逐格上屏 = 每格一次
 * {@code setForeground + setBackground + set}（80x25 = 6000 次调用）。但真实画面里
 * <b>相邻格的颜色大多相同</b>（文本终端整行同色、帧缓冲量化后也是成片的）⇒ 把一段
 * <b>连续同色</b>的格子合并成一次 {@code set(整串)}，调用数降到"颜色段数"。</p>
 *
 * <p>⚠ 合并条件<b>只看颜色、不看字符</b>：一次 {@code set} 本来就带一整串文本，
 * 段内字符各异完全没问题（这正是它快的原因）。</p>
 */
public final class DisplayPainter {

    /**
     * 一段连续同色的字符：平台层对它执行一次
     * {@code setForeground(fg) + setBackground(bg) + set(col, row, text)}。
     */
    public record Run(int col, int len, int fg, int bg, String text) {
    }

    /** 一次搬运覆盖的行范围（闭区间）。GPU 后端 = 一"带"；CPU 后端 = 一行。 */
    public record Band(int rowFrom, int rowTo) {

        public int rows() {
            return rowTo - rowFrom + 1;
        }
    }

    private DisplayPainter() {
    }

    /**
     * 按决策把整帧切成搬运单元。
     *
     * <p>GPU 后端：一页装得下 ⇒ 一个带（整屏一次 blit）；装不下 ⇒ 按 {@code cellsPerMove}
     * 折算成<b>整行</b>的带（绝不切在半行上 —— OC 的 bitblt 是矩形，切半行就要改宽度，
     * 而且页尺寸一变就得重新分配）。CPU 后端：一行一个带。</p>
     */
    public static List<Band> bands(DisplayPresenter.Plan plan, DisplayWindow.Frame frame) {
        if (plan == null || frame == null) {
            throw new IllegalArgumentException("plan/frame must not be null");
        }
        final List<Band> out = new ArrayList<>();
        final int rows = frame.rows();
        if (rows <= 0) {
            return out;
        }
        if (!plan.gpu()) {
            for (int r = 0; r < rows; r++) {
                out.add(new Band(r, r));
            }
            return out;
        }
        final int cols = Math.max(1, frame.cols());
        final int rowsPerBand = Math.max(1, plan.cellsPerMove() / cols);
        for (int r = 0; r < rows; r += rowsPerBand) {
            out.add(new Band(r, Math.min(rows - 1, r + rowsPerBand - 1)));
        }
        return out;
    }

    /** 一行在 {@code colFrom}..{@code colTo}（闭区间）内的颜色分段 */
    public static List<Run> runs(DisplayWindow.Frame frame, int row, int colFrom, int colTo) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final List<Run> out = new ArrayList<>();
        final int cols = frame.cols();
        final int from = Math.max(0, colFrom);
        final int to = Math.min(cols - 1, colTo);
        if (row < 0 || row >= frame.rows() || from > to) {
            return out;
        }
        final int base = row * cols;
        int start = from;
        for (int x = from + 1; x <= to + 1; x++) {
            final boolean cut = x > to
                    || frame.fg()[base + x] != frame.fg()[base + start]
                    || frame.bg()[base + x] != frame.bg()[base + start];
            if (cut) {
                out.add(new Run(start, x - start,
                        frame.fg()[base + start] & 0xFF,
                        frame.bg()[base + start] & 0xFF,
                        DisplayCharset.decodeRange(frame.code(), base + start, base + x - 1)));
                start = x;
            }
        }
        return out;
    }

    /** 一整行的分段（等价于 {@code runs(frame, row, 0, cols-1)}） */
    public static List<Run> runs(DisplayWindow.Frame frame, int row) {
        return runs(frame, row, 0, frame.cols() - 1);
    }
}
