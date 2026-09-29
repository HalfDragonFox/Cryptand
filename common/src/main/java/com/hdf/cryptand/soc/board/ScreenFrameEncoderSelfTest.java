package com.hdf.cryptand.soc.board;

import java.util.Arrays;

/**
 * ===== 帧编码器闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>钉死 {@link ScreenFrameEncoder} 的四条路径与全部输入边界：</p>
 * <ol>
 *   <li><b>16bpp 快路径</b>：RGB565 小端必须与设备 VRAM <b>逐字节相同</b>（零转换是性能前提）；</li>
 *   <li><b>调色板色深</b>：1bpp 黑白、4bpp 精确色、8bpp 色立方内精确色，都必须落到正确索引；</li>
 *   <li><b>直色</b>：24bpp 往返（写进去再 {@code rgbAt} 读出来）必须等于参考色；</li>
 *   <li><b>边界</b>：尺寸不符 / 字节不足 / 非法色深都必须明确抛错，不静默兜底。</li>
 * </ol>
 *
 * <p>跑法（离线闸门）：{@code java -cp <common classes> com.hdf.cryptand.soc.board.ScreenFrameEncoderSelfTest}
 * （gradle 任务 {@code :common:runScreenFrameEncoderTest}）。</p>
 */
public final class ScreenFrameEncoderSelfTest {

    private static int passed;
    private static int failed;

    private ScreenFrameEncoderSelfTest() {
    }

    public static void main(String[] args) {
        fastPath16();
        mono1();
        exact4();
        exact8();
        direct24();
        paletteTable();
        boundaries();

        System.out.println("ScreenFrameEncoderSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 16bpp：整块搬运，VRAM 必须与帧逐字节一致。 */
    private static void fastPath16() {
        final TrueColorScreen d = graphics(2, 2, 16);
        final byte[] frame = {(byte) 0x00, (byte) 0xF8, (byte) 0xE0, (byte) 0x07,
                (byte) 0x1F, (byte) 0x00, (byte) 0xFF, (byte) 0xFF};
        ScreenFrameEncoder.encodeInto(d, frame, 2, 2);
        check(Arrays.equals(d.vram(), frame), "16bpp 快路径逐字节一致");
    }

    /** 1bpp：黑 → 索引 0，白 → 索引 1。 */
    private static void mono1() {
        final TrueColorScreen d = graphics(2, 1, 1);
        ScreenFrameEncoder.preparePalette(d);
        check(d.paletteColor(0) == 0x000000 && d.paletteColor(1) == 0xFFFFFF,
                "1bpp 调色板 = 黑/白");
        final byte[] frame = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};
        ScreenFrameEncoder.encodeInto(d, frame, 2, 1);
        check(d.rawAt(0, 0) == 0 && d.rawAt(1, 0) == 1, "1bpp 黑白落到正确索引");
    }

    /** 4bpp：OC 16 色板内的精确色必须命中自己那一格。 */
    private static void exact4() {
        final TrueColorScreen d = graphics(1, 1, 4);
        ScreenFrameEncoder.preparePalette(d);
        // ⚠ OC 调色板是 MC 染料色（纯红 0xFF0000 **不在表内**，最近的是索引 14 = 0xB02E26），
        //   所以这里验证的是**最近性**：测试内独立遍历表算距离（不调用被测的
        //   nearestPaletteIndex），再与设备实际落下的索引对拍。
        final byte[] red = {(byte) 0x00, (byte) 0xF8}; // RGB565 纯红 → 0xFF0000
        ScreenFrameEncoder.encodeInto(d, red, 1, 1);
        final int want = ScreenFrameEncoder.decode565(red[0], red[1]);
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < OcPalette.size(); i++) {
            final long dist = dist2(OcPalette.rgb(i), want);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        final int idx = d.rawAt(0, 0);
        check(d.paletteColor(idx) == OcPalette.rgb(best),
                "4bpp 纯红落到最近调色板色（索引 " + idx + " = "
                        + Integer.toHexString(d.paletteColor(idx)) + "，独立对拍）");
    }

    /** 8bpp：色立方内精确色必须命中自己那一格。 */
    private static void exact8() {
        final TrueColorScreen d = graphics(1, 1, 8);
        ScreenFrameEncoder.preparePalette(d);
        final byte[] white = {(byte) 0xFF, (byte) 0xFF};
        ScreenFrameEncoder.encodeInto(d, white, 1, 1);
        check(d.paletteColor(d.rawAt(0, 0)) == 0xFFFFFF, "8bpp 纯白命中色立方顶点");
        final byte[] black = {0x00, 0x00};
        ScreenFrameEncoder.encodeInto(d, black, 1, 1);
        check(d.paletteColor(d.rawAt(0, 0)) == 0x000000, "8bpp 纯黑命中色立方原点");
    }

    /** 24bpp 直色：写进去再读出来必须等于参考色（往返对称）。 */
    private static void direct24() {
        final TrueColorScreen d = graphics(2, 1, 24);
        final byte[] frame = {(byte) 0x00, (byte) 0xF8, (byte) 0xFF, (byte) 0xFF};
        ScreenFrameEncoder.encodeInto(d, frame, 2, 1);
        check(d.rgbAt(0, 0) == ScreenFrameEncoder.decode565(frame[0], frame[1]),
                "24bpp 纯红往返一致");
        check(d.rgbAt(1, 0) == 0xFFFFFF, "24bpp 纯白往返一致");
    }

    /** 调色板表本身：直色色深没有调色板。 */
    private static void paletteTable() {
        check(ScreenFrameEncoder.paletteFor(1).length == 2, "1bpp 表 2 色");
        check(ScreenFrameEncoder.paletteFor(2).length == 4, "2bpp 表 4 色");
        check(ScreenFrameEncoder.paletteFor(4).length == 16, "4bpp 表 16 色");
        check(ScreenFrameEncoder.paletteFor(8).length == 256, "8bpp 表 256 色");
        check(ScreenFrameEncoder.paletteFor(16) == null, "16bpp 是直色（无调色板）");
        check(ScreenFrameEncoder.paletteFor(24) == null, "24bpp 是直色（无调色板）");
        check(ScreenFrameEncoder.paletteFor(32) == null, "32bpp 是直色（无调色板）");
    }

    /** 边界：一律明确抛错。 */
    private static void boundaries() {
        final TrueColorScreen d = graphics(2, 2, 16);
        final byte[] ok = new byte[8];
        checkThrows(() -> ScreenFrameEncoder.encodeInto(d, ok, 3, 2), "尺寸不符要抛错");
        checkThrows(() -> ScreenFrameEncoder.encodeInto(d, new byte[4], 2, 2), "字节不足要抛错");
        checkThrows(() -> ScreenFrameEncoder.encodeInto(null, ok, 2, 2), "device=null 要抛错");
        checkThrows(() -> ScreenFrameEncoder.encodeInto(d, null, 2, 2), "帧=null 要抛错");
        checkThrows(() -> ScreenFrameEncoder.encodeInto(d, ok, 0, 0), "尺寸非法要抛错");
        checkThrows(() -> ScreenFrameEncoder.paletteFor(3), "3bpp 不在支持集（要抛错）");
    }

    /** 欧氏距离平方（只取低 24 位）——测试侧独立实现，用于对拍最近性。 */
    private static long dist2(int a, int b) {
        final long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        final long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        final long db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static TrueColorScreen graphics(int w, int h, int bpp) {
        final TrueColorScreen d = TrueColorScreen.of(w, h, bpp);
        d.setMode(TrueColorScreen.Mode.GRAPHICS);
        return d;
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static void checkThrows(Runnable body, String what) {
        try {
            body.run();
            failed++;
            System.out.println("  FAIL " + what + "（没有抛错）");
        } catch (RuntimeException expected) {
            passed++;
            System.out.println("  ok   " + what + "（" + expected.getClass().getSimpleName() + "）");
        }
    }
}
