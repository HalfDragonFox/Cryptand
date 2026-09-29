package com.hdf.cryptand.soc.board;

/**
 * ===== 上屏决策（common，纯 Java 零 MC，2026-09-18）=====
 *
 * <p>用户定案："刷新的话可以使用 GPU，如果没有 GPU 则 CPU 手动刷新"、
 * "对于超大屏幕 GPU 才能刷的快"。</p>
 *
 * <p>这里只做**决策**（后端 + 要搬几次），执行留给平台层（它才碰得到 OC 的 gpu 组件）：</p>
 * <ul>
 *   <li><b>GPU 后端</b>：把整屏写进一块**显存页**，再一次 {@code bitblt} 上屏 —— 搬运次数 O(1)；</li>
 *   <li>一页装不下整屏（超大屏）⇒ **按带（band）切**，每带一次 blit；</li>
 *   <li><b>CPU 后端</b>：没有可用 GPU 通路、或固件显式要求强制 CPU ⇒ 逐行 {@code gpu.set}（最慢兜底）。</li>
 * </ul>
 *
 * <p>决策结果会被写回窗口的 {@code backend} 字段，固件读出来打 UART（无人化据此断言用的是哪条路）。</p>
 */
public final class DisplayPresenter {

    /**
     * 上屏计划。
     *
     * @param backend      实际使用的后端（{@link DisplayWindow#BACKEND_GPU} / {@link DisplayWindow#BACKEND_CPU}）
     * @param moves        要搬几次（GPU 后端 = 带数；CPU 后端 = 行数）
     * @param cellsPerMove 每带/每行多少字符格
     * @param reason       人可读理由（日志/诊断）
     */
    public record Plan(int backend, int moves, int cellsPerMove, String reason) {

        public boolean gpu() {
            return backend == DisplayWindow.BACKEND_GPU;
        }
    }

    /**
     * 宿主当下这台机器的上屏能力 —— {@link #plan} 的输入。
     *
     * @param gpuUsable 有没有可用的 GPU 上屏通路（有显卡、桥能通、页路径没被永久关掉）
     * @param pageCells GPU 一页能装多少字符格（&lt;= 0 = 不知道）
     */
    public record Capability(boolean gpuUsable, int pageCells) {

        /** 没有可用通路（没显卡 / 屏幕没了 / 页路径被关） */
        public static Capability none() {
            return new Capability(false, 0);
        }
    }

    private DisplayPresenter() {
    }

    /**
     * 决定这一帧怎么上屏。
     *
     * @param frame      要上屏的一帧（字符三平面）
     * @param gpuCapable 宿主这台机器有没有**可用的 GPU 上屏通路**（组件桥能调 gpu / bitblt）
     * @param pageCells  GPU 一页能装多少字符格（&lt;= 0 = 不知道或不支持分页 ⇒ 只能逐行）
     * @param flags      固件写在窗口里的 flags（{@link DisplayWindow#FLAG_ALLOW_GPU} / {@link DisplayWindow#FLAG_FORCE_CPU}）
     */
    public static Plan plan(DisplayWindow.Frame frame, boolean gpuCapable, int pageCells, int flags) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final int cols = frame.cols();
        final int rows = frame.rows();
        // ① 固件显式要求强制 CPU（例如它想自己控制刷新时机）—— 优先级最高
        if ((flags & DisplayWindow.FLAG_FORCE_CPU) != 0) {
            return new Plan(DisplayWindow.BACKEND_CPU, rows, cols, "固件要求强制 CPU");
        }
        // ② 没有可用的 GPU 通路（没插显卡 / 桥没通 / 显卡不支持页）
        if (!gpuCapable || pageCells <= 0) {
            return new Plan(DisplayWindow.BACKEND_CPU, rows, cols,
                    gpuCapable ? "不知道 GPU 页容量，退回逐行" : "没有可用的 GPU 上屏通路");
        }
        // ③ GPU 后端：整屏一页装得下就一次 blit，否则按带分
        final int cells = cols * rows;
        if (cells <= pageCells) {
            return new Plan(DisplayWindow.BACKEND_GPU, 1, cells, "整屏装得下一页：一次 blit 上屏");
        }
        final int bands = (cells + pageCells - 1) / pageCells;
        return new Plan(DisplayWindow.BACKEND_GPU, bands, pageCells,
                "一页装不下 " + cols + "x" + rows + "（页容量 " + pageCells + " 格）⇒ 按带分 "
                        + bands + " 次 blit");
    }
}
