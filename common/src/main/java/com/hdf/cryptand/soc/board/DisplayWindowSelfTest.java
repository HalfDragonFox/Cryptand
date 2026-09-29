package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.device.ByteWindowDevice;

/**
 * ===== 显存窗口协议闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>验证"CPU 写显存、宿主扫描输出"这条链路的协议部分：</p>
 * <ol>
 *   <li>固件写控制块 + 三平面 + **门铃**；宿主扫描器取到整屏（尺寸/序号/内容都对）；</li>
 *   <li>门铃没变 ⇒ **不重复搬运**（"主机只做同步"的落点）；门铃 ++ ⇒ 取到新帧；</li>
 *   <li>宿主回写 backend（实际用了 CPU 还是 GPU）⇒ 固件读得到；宿主能读固件的 flags；</li>
 *   <li>错误路径明确报错：魔数不对（固件没初始化）、窗口装不下整屏。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runDisplayWindowTest}</p>
 */
public final class DisplayWindowSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        final int cols = 80;
        final int rows = 25;
        final int plane = cols * rows;
        final ByteWindowDevice win = new ByteWindowDevice("VRAM",
                DisplayWindow.textWindowBytes(cols, rows), null);

        // ---- 1. 固件侧：初始化窗口 + 写三平面 + 门铃 ----
        win.store(DisplayWindow.OFF_MAGIC, DisplayWindow.MAGIC, Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_COLS, cols, Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_ROWS, rows, Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_FORMAT, DisplayWindow.FORMAT_TEXT, Sizes.SIZE_32);
        win.store(DisplayWindow.OFF_FLAGS, DisplayWindow.FLAG_ALLOW_GPU, Sizes.SIZE_32);
        for (int i = 0; i < plane; i++) {
            win.store(DisplayWindow.HEADER_BYTES + i, 'A' + (i % 26), Sizes.SIZE_8);
            win.store(DisplayWindow.HEADER_BYTES + plane + i, 0x0F, Sizes.SIZE_8);
            win.store(DisplayWindow.HEADER_BYTES + 2 * plane + i, 0x00, Sizes.SIZE_8);
        }
        win.store(DisplayWindow.OFF_FRAME_SEQ, 1, Sizes.SIZE_32);

        final DisplayWindow.Scanner sc = new DisplayWindow.Scanner(win);
        check("门铃变化 ⇒ 有脏帧", sc.dirty());
        final DisplayWindow.Frame f = sc.scanIfDirty();
        check("取到整屏 80x25（seq=1）",
                f != null && f.cols() == cols && f.rows() == rows && f.seq() == 1);
        check("码点平面正确（含环绕）", f.code()[0] == 'A' && f.code()[26] == 'A');
        check("前景/背景平面正确（16 色索引）", f.fg()[0] == 0x0F && f.bg()[0] == 0x00);
        check("再扫一次不重复搬（门铃没变）",
                sc.scanIfDirty() == null && sc.scannedFrames() == 1);

        // ---- 2. 第二帧：门铃 ++ ----
        win.store(DisplayWindow.HEADER_BYTES, 'Z', Sizes.SIZE_8);
        win.store(DisplayWindow.OFF_FRAME_SEQ, 2, Sizes.SIZE_32);
        check("门铃 ++ ⇒ 又有脏帧", sc.dirty());
        final DisplayWindow.Frame f2 = sc.scanIfDirty();
        check("第二帧内容更新且计数 +1",
                f2.seq() == 2 && f2.code()[0] == 'Z' && sc.scannedFrames() == 2);

        // ---- 3. 后端回写 / flags ----
        sc.reportBackend(DisplayWindow.BACKEND_GPU);
        check("宿主回写 backend ⇒ 固件读得到",
                win.load(DisplayWindow.OFF_BACKEND, Sizes.SIZE_32) == DisplayWindow.BACKEND_GPU);
        check("宿主能读固件 flags（是否允许 GPU）",
                sc.flags() == DisplayWindow.FLAG_ALLOW_GPU);

        // ---- 4. 错误路径 ----
        final ByteWindowDevice bad = new ByteWindowDevice("BAD",
                DisplayWindow.textWindowBytes(4, 4), null);
        boolean noMagic = false;
        try {
            new DisplayWindow.Scanner(bad).scanIfDirty();
        } catch (IllegalStateException e) {
            noMagic = e.getMessage().contains("魔数");
        }
        check("魔数不对 ⇒ 明确报错（固件没初始化窗口）", noMagic);

        final ByteWindowDevice tooSmall = new ByteWindowDevice("SMALL", 64, null);
        tooSmall.store(DisplayWindow.OFF_MAGIC, DisplayWindow.MAGIC, Sizes.SIZE_32);
        tooSmall.store(DisplayWindow.OFF_COLS, cols, Sizes.SIZE_32);
        tooSmall.store(DisplayWindow.OFF_ROWS, rows, Sizes.SIZE_32);
        tooSmall.store(DisplayWindow.OFF_FORMAT, DisplayWindow.FORMAT_TEXT, Sizes.SIZE_32);
        tooSmall.store(DisplayWindow.OFF_FRAME_SEQ, 1, Sizes.SIZE_32);
        String tooSmallMsg = null;
        try {
            new DisplayWindow.Scanner(tooSmall).scanIfDirty();
        } catch (IllegalStateException e) {
            tooSmallMsg = e.getMessage();
        }
        // ⚠ 报错消息与场景必须对得上："控制块都放不下"（地址/窗口给错了）与"装不下整屏"
        //   （容量给少了）是两种完全不同的故障，混成一条断言就分不清该去查哪边
        //   （2026-09-18 实测踩过：这条断言写的是"太小"，而这里走的分支是"装不下整屏"）。
        check("窗口装不下整屏 ⇒ 明确报错（不是静默截断）",
                tooSmallMsg != null && tooSmallMsg.contains("装不下整屏"));

        String tinyMsg = null;
        try {
            DisplayWindow.parse(new byte[16]);
        } catch (IllegalStateException e) {
            tinyMsg = e.getMessage();
        }
        check("窗口连控制块都放不下 ⇒ 报「太小」", tinyMsg != null && tinyMsg.contains("太小"));

        // ---- 5. 布局换算（单一来源）----
        check("字符窗口字节数 = 32 + 3*cols*rows",
                DisplayWindow.textWindowBytes(cols, rows) == 32 + 3 * plane);
        check("RGB565 窗口字节数 = 32 + w*h*2",
                DisplayWindow.rgb565WindowBytes(160, 50) == 32 + 160 * 50 * 2);
        check("控制块偏移与 OC 的 TextBuffer 语义对齐（code/fg/bg 三平面）",
                DisplayWindow.HEADER_BYTES == 0x20 && DisplayWindow.FORMAT_TEXT == 0);

        System.out.println("[VRAM] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
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
