package com.hdf.cryptand.soc.board;

import java.util.Random;

/**
 * ===== 帧体压缩离线闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>{@code ./gradlew :common:runFrameCompressionTest}</p>
 */
public final class FrameCompressionSelfTest {

    private static int passed;
    private static int failed;

    private FrameCompressionSelfTest() {
    }

    public static void main(String[] args) {
        // 1) 全同色：RLE 最擅长的形态
        final int[] solid = new int[102400];
        java.util.Arrays.fill(solid, 0xFF102030);
        final byte[] solidEncoded = FrameCompression.encodeRle(solid, 0, solid.length);
        check("全同色 102400 像素压到 <16 字节（实得 " + solidEncoded.length + "）", solidEncoded.length < 16);
        check("全同色：解压逐像素等价", same(solid, FrameCompression.decodeRle(solidEncoded, solid.length)));
        check("全同色：算法选择 = RLE",
                FrameCompression.pickAlgorithm(solid, 0, solid.length, solidEncoded) == FrameCompression.ALGORITHM_RLE);

        // 2) 终端形态：800 个字符格，每格 8x16 同色（格与格不同色）
        final int[] terminal = new int[400 * 256];
        final Random rnd = new Random(3L);
        for (int cy = 0; cy < 256; cy += 16) {
            for (int cx = 0; cx < 400; cx += 8) {
                final int argb = 0xFF000000 | rnd.nextInt(0x1000000);
                for (int y = cy; y < cy + 16; y++) {
                    for (int x = cx; x < cx + 8; x++) {
                        terminal[y * 400 + x] = argb;
                    }
                }
            }
        }
        final byte[] terminalEncoded = FrameCompression.encodeRle(terminal, 0, terminal.length);
        final double ratio = (double) terminalEncoded.length / (terminal.length * 4L);
        // 逐格异色的终端：一行里每 8 像素换一次色 ⇒ run=8 ⇒ 6 字节/8 像素 ≈ 18% 是 RLE 的正常水平。
        // （zlib 在字节层还能吃掉 alpha 通道的重复，所以 1.2% 那种成绩是 zlib 的强项；
        //   两者定位不同：RLE 快、zlib 紧 —— 选哪个看实测，别假设。）
        check("终端形态（逐格异色）：压缩率 < 20%（实得 " + String.format("%.2f", ratio * 100) + "%）", ratio < 0.20);
        // 真实终端更常见的是"整行同背景 + 少量字模" ⇒ run 长得多，这里补一个长 run 场景
        final int[] rowsSameColor = new int[400 * 256];
        for (int y = 0; y < 256; y++) {
            java.util.Arrays.fill(rowsSameColor, y * 400, y * 400 + 400, 0xFF101010 | (y << 8));
        }
        final double rowsRatio = (double) FrameCompression.encodeRle(rowsSameColor, 0, rowsSameColor.length).length
                / (rowsSameColor.length * 4L);
        check("整行同色：压缩率 < 1%（实得 " + String.format("%.2f", rowsRatio * 100) + "%）", rowsRatio < 0.01);
        check("终端形态：解压等价", same(terminal, FrameCompression.decodeRle(terminalEncoded, terminal.length)));

        // 3) 随机：RLE 必然膨胀 ⇒ 选择器必须回落到 NONE（否则等于把带宽翻倍）
        final int[] noisy = new int[4096];
        new Random(9L).nextInt();
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] = 0xFF000000 | rnd.nextInt(0x1000000);
        }
        final byte[] noisyEncoded = FrameCompression.encodeRle(noisy, 0, noisy.length);
        check("随机像素：RLE 不划算 ⇒ 选择 NONE",
                FrameCompression.pickAlgorithm(noisy, 0, noisy.length, noisyEncoded) == FrameCompression.ALGORITHM_NONE);
        check("随机像素：内容仍可无损还原（只是不选它）",
                same(noisy, FrameCompression.decodeRle(noisyEncoded, noisy.length)));

        // 4) 边界：空、单像素、长 run 跨 varint 边界
        check("空：编码 0 字节", FrameCompression.encodeRle(new int[0], 0, 0).length == 0);
        check("空：解压为 0 像素", FrameCompression.decodeRle(new byte[0], 0).length == 0);
        final int[] one = {0xFFFFFFFF};
        check("单像素：往返等价", same(one, FrameCompression.decodeRle(FrameCompression.encodeRle(one, 0, 1), 1)));
        final int[] big = new int[70000];                 // run 长度需要 3 字节 varint
        java.util.Arrays.fill(big, 0xFF00FF00);
        check("70000 像素长 run：往返等价", same(big, FrameCompression.decodeRle(FrameCompression.encodeRle(big, 0, big.length), big.length)));

        // 5) offset 用法（帧体是"矩形块拼接"，decode 端按块顺序消费）
        final int[] src = {7, 9, 9, 9, 7};        // [1..3] = 9,9,9
        final byte[] part = FrameCompression.encodeRle(src, 1, 3);
        check("offset=1 count=3：解出 3 个 9", same(new int[]{9, 9, 9}, FrameCompression.decodeRle(part, 3)));

        // 6) 坏数据必须明确抛错（不静默补零）
        check("像素数不符 ⇒ 抛错", throwsOn(() -> FrameCompression.decodeRle(part, 5)));
        check("run 溢出 ⇒ 抛错", throwsOn(() -> FrameCompression.decodeRle(part, 2)));
        check("varint 截断 ⇒ 抛错", throwsOn(() -> FrameCompression.decodeRle(new byte[]{(byte) 0x80}, 1)));
        check("范围越界 ⇒ 抛错", throwsOn(() -> FrameCompression.encodeRle(src, 2, 9)));

        // 7) auto 三档判据（数字取自离线基准实测：终端 15.6% / 随机 125%）
        check("auto：终端形态（15.6%）⇒ RLE", FrameCompression.pickAuto(409600, 64000) == FrameCompression.ALGORITHM_RLE);
        check("auto：压不动（125%）⇒ NONE", FrameCompression.pickAuto(409600, 512000) == FrameCompression.ALGORITHM_NONE);
        check("auto：中间地带（73%）⇒ ZLIB", FrameCompression.pickAuto(409600, 300000) == FrameCompression.ALGORITHM_ZLIB);
        check("auto：raw<=0 ⇒ NONE", FrameCompression.pickAuto(0, 0) == FrameCompression.ALGORITHM_NONE);
        final byte[] realRle = FrameCompression.encodeRle(terminal, 0, terminal.length);
        check("auto：接真实终端数据 ⇒ RLE",
                FrameCompression.pickAuto(terminal.length * 4, realRle.length) == FrameCompression.ALGORITHM_RLE);
        final byte[] realNoisyRle = FrameCompression.encodeRle(noisy, 0, noisy.length);
        check("auto：接真实随机数据 ⇒ NONE",
                FrameCompression.pickAuto(noisy.length * 4, realNoisyRle.length) == FrameCompression.ALGORITHM_NONE);

        // 8) 配置解析：auto/强制后端；未知写法必须抛错（不静默降级）
        check("parse：auto ⇒ -1（交给 pickAuto）", FrameCompression.parseAlgorithm("auto") == -1);
        check("parse：rle（带空格/大写）", FrameCompression.parseAlgorithm(" RLE ") == FrameCompression.ALGORITHM_RLE);
        check("parse：zlib", FrameCompression.parseAlgorithm("zlib") == FrameCompression.ALGORITHM_ZLIB);
        check("parse：lz4（预留号）", FrameCompression.parseAlgorithm("lz4") == FrameCompression.ALGORITHM_LZ4);
        check("parse：none", FrameCompression.parseAlgorithm("none") == FrameCompression.ALGORITHM_NONE);
        check("parse：未知算法 ⇒ 抛错", throwsOn(() -> FrameCompression.parseAlgorithm("snappy")));
        check("parse：null ⇒ 抛错", throwsOn(() -> FrameCompression.parseAlgorithm(null)));
        check("算法名可读", "zlib".equals(FrameCompression.algorithmName(FrameCompression.ALGORITHM_ZLIB))
                && "rle".equals(FrameCompression.algorithmName(FrameCompression.ALGORITHM_RLE)));

        System.out.println("[frame-compression] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean same(int[] a, int[] b) {
        return java.util.Arrays.equals(a, b);
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
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
