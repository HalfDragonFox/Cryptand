package com.hdf.cryptand.soc.board;

import java.util.List;

/**
 * ===== 上屏展开闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>钉住两件在真机上"画错了才看得出来"的事：</p>
 * <ol>
 *   <li><b>码页</b>：固件的 0xB0/0xB2/0xDB 必须解成 ░▒█（U+2591/U+2593/U+2588），
 *       <b>不是</b>度数符号 ° —— 按 Latin-1 直转就会这样，屏幕上全是乱码；</li>
 *   <li><b>分段</b>：同色相邻必须合并（否则 80x25 变 6000 次组件调用），
 *       且所有段加起来要正好覆盖整行（漏一格就是一行里少一个字符）。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runDisplayPainterTest}</p>
 */
public final class DisplayPainterSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ==================== 1. 码页：必须真解码，不能 Latin-1 直转 ====================
        check("ASCII 原样：0x41 -> A", "A".equals(DisplayCharset.decode(0x41)));
        check("0xB0 -> 阴影 ░（U+2591），不是 °（U+00B0）",
                "\u2591".equals(DisplayCharset.decode(0xB0)));
        check("0xB1 -> 中阴影 ▒（U+2592）", "\u2592".equals(DisplayCharset.decode(0xB1)));
        check("0xB2 -> 深阴影 ▓（U+2593）", "\u2593".equals(DisplayCharset.decode(0xB2)));
        check("0xDB -> 实心块 █（U+2588）", "\u2588".equals(DisplayCharset.decode(0xDB)));
        check("0xB3 -> 竖线 │（U+2502）", "\u2502".equals(DisplayCharset.decode(0xB3)));
        check("0xC4 -> 横线 ─（U+2500）", "\u2500".equals(DisplayCharset.decode(0xC4)));
        check("0x00 当空格（NUL 会被 OC 跳过 ⇒ 整行格子左移）",
                " ".equals(DisplayCharset.decode(0x00)));
        check("0x1F 当空格（控制码同样会被 OC 跳过）",
                " ".equals(DisplayCharset.decode(0x1F)));
        check("0x7F 当空格", " ".equals(DisplayCharset.decode(0x7F)));
        check("高位字节解出来仍是单字符（不会变成两格）",
                DisplayCharset.decode(0xDB).length() == 1);

        // ==================== 2. 分段：同色合并 ====================
        final DisplayWindow.Frame solid = frame(80, 25, 0x07, 0x00);
        final List<DisplayPainter.Run> one = DisplayPainter.runs(solid, 0);
        check("整行同色 ⇒ 只有 1 段", one.size() == 1);
        check("该段覆盖整行", one.get(0).col() == 0 && one.get(0).len() == 80);
        check("段带上颜色（fg=7 bg=0）", one.get(0).fg() == 0x07 && one.get(0).bg() == 0x00);
        check("段文本长度 = 段长", one.get(0).text().length() == 80);
        check("首字符就是解出来的文本", "A".equals(one.get(0).text().substring(0, 1)));

        // 每 10 格换一次前景色 ⇒ 8 段，每段 10
        final DisplayWindow.Frame zebra = zebra(80, 25);
        final List<DisplayPainter.Run> eight = DisplayPainter.runs(zebra, 0);
        check("每 10 格换色 ⇒ 8 段", eight.size() == 8);
        check("每段长 10", eight.stream().allMatch(r -> r.len() == 10));
        check("段起点依次 0/10/20...", eight.get(1).col() == 10 && eight.get(7).col() == 70);
        check("颜色随段变化", eight.get(0).fg() != eight.get(1).fg());

        // 覆盖性：任何一行、任何切法，段长之和必须 == 宽度（漏一格 = 少一个字符）
        boolean covers = true;
        for (int row = 0; row < zebra.rows() && covers; row++) {
            int sum = 0;
            for (final DisplayPainter.Run r : DisplayPainter.runs(zebra, row)) {
                sum += r.len();
            }
            covers = sum == zebra.cols();
        }
        check("所有行的段长之和都等于宽度（无漏格/重叠）", covers);

        // 只背景色不同也要切段（颜色是 fg+bg 两个平面）
        final DisplayWindow.Frame bgOnly = frame(40, 2, 0x0F, 0x00);
        for (int x = 20; x < 40; x++) {
            bgOnly.bg()[x] = 0x01;
        }
        check("背景色变化也会切段（0..19 / 20..39）",
                DisplayPainter.runs(bgOnly, 0).size() == 2
                        && DisplayPainter.runs(bgOnly, 0).get(1).col() == 20);

        // 边界
        check("单格范围 ⇒ 1 段长度 1", DisplayPainter.runs(solid, 0, 5, 5).size() == 1
                && DisplayPainter.runs(solid, 0, 5, 5).get(0).len() == 1);
        check("行号越界 ⇒ 空", DisplayPainter.runs(solid, -1).isEmpty()
                && DisplayPainter.runs(solid, 25).isEmpty());
        check("列范围反向 ⇒ 空", DisplayPainter.runs(solid, 0, 10, 9).isEmpty());
        check("列范围超出屏幕 ⇒ 裁剪到屏内", sumLen(DisplayPainter.runs(solid, 0, 70, 999)) == 10);
        check("整行便捷重载与显式范围一致",
                DisplayPainter.runs(solid, 3).size() == DisplayPainter.runs(solid, 3, 0, 79).size());

        // 码页在分段里生效（0x00 与 0xDB 混排）
        final DisplayWindow.Frame mixed = frame(4, 1, 0x07, 0x00);
        mixed.code()[0] = 0x00;
        mixed.code()[1] = 0x41;
        mixed.code()[2] = (byte) 0xDB;
        mixed.code()[3] = (byte) 0xB0;
        final String txt = DisplayPainter.runs(mixed, 0).get(0).text();
        check("分段文本按码页解：空格 + A + █ + ░",
                txt.equals(" A" + "\u2588" + "\u2591"));

        // ==================== 3. 搬运单元（带） ====================
        final DisplayWindow.Frame big = frame(240, 80, 0x0F, 0x00);
        final DisplayPresenter.Plan gpu = DisplayPresenter.plan(big, true, 2000,
                DisplayWindow.FLAG_ALLOW_GPU);
        final List<DisplayPainter.Band> bands = DisplayPainter.bands(gpu, big);
        check("240x80 页容量 2000 ⇒ 每带 8 行、共 10 带", bands.size() == 10);
        check("首带从第 0 行起、末带到第 79 行止",
                bands.get(0).rowFrom() == 0 && bands.get(9).rowTo() == 79);
        check("每带 8 行（整行切，绝不切半行）", bands.get(0).rows() == 8);
        boolean contiguous = true;
        for (int i = 1; i < bands.size(); i++) {
            contiguous &= bands.get(i).rowFrom() == bands.get(i - 1).rowTo() + 1;
        }
        check("带与带首尾相接、无重叠无空洞", contiguous);

        final DisplayPresenter.Plan cpu = DisplayPresenter.plan(big, false, 0,
                DisplayWindow.FLAG_ALLOW_GPU);
        check("CPU 后端 ⇒ 每行一个搬运单元（80 行 80 次）",
                DisplayPainter.bands(cpu, big).size() == 80);
        check("CPU 每带恰好一行", DisplayPainter.bands(cpu, big).get(0).rows() == 1);

        final DisplayPresenter.Plan onePage = DisplayPresenter.plan(solid, true, 80 * 25,
                DisplayWindow.FLAG_ALLOW_GPU);
        check("一页装得下 ⇒ 整屏只有 1 带（一次 blit）",
                DisplayPainter.bands(onePage, solid).size() == 1
                        && DisplayPainter.bands(onePage, solid).get(0).rows() == 25);

        check("空帧（0 行）⇒ 没有搬运单元",
                DisplayPainter.bands(onePage, frame(80, 0, 0x07, 0x00)).isEmpty());

        System.out.println("DisplayPainterSelfTest: " + passed + "/" + (passed + failed)
                + (failed == 0 ? " 全部通过" : "  ** " + failed + " 项失败 **"));
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static int sumLen(List<DisplayPainter.Run> runs) {
        int sum = 0;
        for (final DisplayPainter.Run r : runs) {
            sum += r.len();
        }
        return sum;
    }

    private static DisplayWindow.Frame frame(int cols, int rows, int fg, int bg) {
        final byte[] code = new byte[cols * rows];
        final byte[] fgPlane = new byte[cols * rows];
        final byte[] bgPlane = new byte[cols * rows];
        for (int i = 0; i < code.length; i++) {
            code[i] = 'A';
            fgPlane[i] = (byte) fg;
            bgPlane[i] = (byte) bg;
        }
        return new DisplayWindow.Frame(cols, rows, DisplayWindow.FORMAT_TEXT, 1, code, fgPlane, bgPlane);
    }

    /** 每 10 格换一次前景色（0..7 循环） */
    private static DisplayWindow.Frame zebra(int cols, int rows) {
        final DisplayWindow.Frame f = frame(cols, rows, 0x00, 0x00);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                f.fg()[y * cols + x] = (byte) ((x / 10) % 8);
            }
        }
        return f;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK] " + what);
        } else {
            failed++;
            System.out.println("  [!!] " + what);
        }
    }
}
