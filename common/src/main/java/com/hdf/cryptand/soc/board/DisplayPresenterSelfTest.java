package com.hdf.cryptand.soc.board;

/**
 * ===== 上屏决策闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>钉住三条：没有 GPU 通路 ⇒ 逐行兜底；有 GPU 且一页装得下 ⇒ **一次 blit**；
 * 超大屏一页装不下 ⇒ 按带分多次 blit（这正是"超大屏只有 GPU 刷得动"的落点）。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runDisplayPresenterTest}</p>
 */
public final class DisplayPresenterSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        final DisplayWindow.Frame small = frame(80, 25);
        final DisplayWindow.Frame large = frame(240, 80);

        // ---- 1. 没有 GPU 通路 ⇒ CPU 逐行兜底 ----
        final DisplayPresenter.Plan noGpu =
                DisplayPresenter.plan(small, false, 4096, DisplayWindow.FLAG_ALLOW_GPU);
        check("没有 GPU 通路 ⇒ CPU 后端", noGpu.backend() == DisplayWindow.BACKEND_CPU);
        check("CPU 后端按行搬（80x25 ⇒ 25 次）", noGpu.moves() == 25 && noGpu.cellsPerMove() == 80);
        check("理由写清是没有通路", noGpu.reason().contains("没有可用的 GPU"));

        // ---- 2. 有 GPU：一页装得下 ⇒ 一次 blit ----
        final DisplayPresenter.Plan onePage =
                DisplayPresenter.plan(small, true, 80 * 25, DisplayWindow.FLAG_ALLOW_GPU);
        check("一页装得下 ⇒ GPU 后端，1 次搬运", onePage.gpu() && onePage.moves() == 1);

        // ---- 3. 超大屏：一页装不下 ⇒ 按带分 ----
        final int pageCells = 80 * 25;                      // 一页 2000 格
        final DisplayPresenter.Plan banded =
                DisplayPresenter.plan(large, true, pageCells, DisplayWindow.FLAG_ALLOW_GPU);
        check("超大屏（240x80 = 19200 格）⇒ 按带分 10 次",
                banded.gpu() && banded.moves() == 10 && banded.cellsPerMove() == pageCells);
        check("理由写明页容量与带数", banded.reason().contains("按带分"));

        // ---- 4. 固件强制 CPU ⇒ 即使有 GPU 也走 CPU ----
        final DisplayPresenter.Plan forced =
                DisplayPresenter.plan(small, true, pageCells,
                        DisplayWindow.FLAG_ALLOW_GPU | DisplayWindow.FLAG_FORCE_CPU);
        check("固件强制 CPU ⇒ CPU 后端（优先级最高）",
                forced.backend() == DisplayWindow.BACKEND_CPU && forced.reason().contains("强制 CPU"));

        // ---- 5. 知道有 GPU 但不知道页容量 ⇒ 逐行（不冒进）----
        final DisplayPresenter.Plan unknownPage =
                DisplayPresenter.plan(small, true, 0, DisplayWindow.FLAG_ALLOW_GPU);
        check("不知道页容量 ⇒ 退回逐行（不冒进用页）",
                unknownPage.backend() == DisplayWindow.BACKEND_CPU
                        && unknownPage.reason().contains("不知道 GPU 页容量"));

        // ---- 6. 搬运次数与屏幕面积的关系（大屏 CPU 兜底代价高，正是要 GPU 的原因）----
        check("同屏下 GPU 搬运次数远少于 CPU",
                DisplayPresenter.plan(large, true, pageCells, 0).moves()
                        < DisplayPresenter.plan(large, false, pageCells, 0).moves());

        // ---- 7. 决策结果可回写窗口（固件据此打 UART 断言）----
        final com.hdf.cryptand.soc.device.ByteWindowDevice win =
                new com.hdf.cryptand.soc.device.ByteWindowDevice("VRAM",
                        DisplayWindow.textWindowBytes(80, 25), null);
        win.store(DisplayWindow.OFF_MAGIC, DisplayWindow.MAGIC, com.hdf.cryptand.soc.api.Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_COLS, 80, com.hdf.cryptand.soc.api.Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_ROWS, 25, com.hdf.cryptand.soc.api.Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_FORMAT, DisplayWindow.FORMAT_TEXT, com.hdf.cryptand.soc.api.Sizes.SIZE_32);
        final DisplayWindow.Scanner sc = new DisplayWindow.Scanner(win);
        win.store(DisplayWindow.OFF_FRAME_SEQ, 3, com.hdf.cryptand.soc.api.Sizes.SIZE_32);
        final DisplayWindow.Frame read = sc.scanIfDirty();
        check("扫描器能取到帧（门铃 3）", read != null && read.seq() == 3);
        sc.reportBackend(DisplayPresenter.plan(read, true, 80 * 25, 0).backend());
        check("决策结果回写 backend ⇒ 固件读得到 GPU",
                win.load(DisplayWindow.OFF_BACKEND, com.hdf.cryptand.soc.api.Sizes.SIZE_32)
                        == DisplayWindow.BACKEND_GPU);

        System.out.println("[PRESENT] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static DisplayWindow.Frame frame(int cols, int rows) {
        final int plane = cols * rows;
        return new DisplayWindow.Frame(cols, rows, DisplayWindow.FORMAT_TEXT, 1,
                new byte[plane], new byte[plane], new byte[plane]);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
