package com.hdf.cryptand.soc.board;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * ===== 帧上线成本离线基准（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>一帧真正上线要过压缩。服务端现在走 MC 通道的 zlib（`CompressedPacketBuilder`），
 * 用户定案允许 `RLE / LZ4` 之类**自研压缩**。到底用哪个不是拍脑袋的事：</p>
 *
 * <ul>
 *   <li>zlib：字节层压缩，能吃掉 alpha 通道与通道间重复 ⇒ **紧**，但 2.9~14.3ms</li>
 *   <li>RLE（本仓 `FrameCompression`）：按 ARGB 整数 run 压缩 ⇒ **快**，但对逐格异色只有 ~18%</li>
 * </ul>
 *
 * <p>跑法：{@code ./gradlew :common:runScreenFrameWireBenchmark}</p>
 */
public final class ScreenFrameWireBenchmark {

    private static final int W = 400;
    private static final int H = 256;
    private static final int PIXELS = W * H;

    private ScreenFrameWireBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        final int[] terminal = terminalPixels();
        final int[] noisy = noisyPixels();

        for (int i = 0; i < 5; i++) {          // 预热
            deflate(toBytes(noisy), 6);
            FrameCompression.encodeRle(noisy, 0, noisy.length);
        }

        compare("终端画面（逐格异色）", terminal);
        compare("整行同色（真实终端背景）", rowsSameColorPixels());
        compare("随机像素（图形模式最坏）", noisy);
    }

    private static void compare(String what, int[] pixels) throws Exception {
        final byte[] rawBytes = toBytes(pixels);
        final int iterations = 20;

        long zlibTotal = 0L;
        int zlibSize = 0;
        for (int i = 0; i < iterations; i++) {
            final long t0 = System.nanoTime();
            final byte[] packed = deflate(rawBytes, 6);
            zlibTotal += System.nanoTime() - t0;
            zlibSize = packed.length;
        }

        long rleTotal = 0L;
        int rleSize = 0;
        for (int i = 0; i < iterations; i++) {
            final long t0 = System.nanoTime();
            final byte[] packed = FrameCompression.encodeRle(pixels, 0, pixels.length);
            rleTotal += System.nanoTime() - t0;
            rleSize = packed.length;
        }

        long rleDecodeTotal = 0L;
        final byte[] rleBody = FrameCompression.encodeRle(pixels, 0, pixels.length);
        for (int i = 0; i < iterations; i++) {
            final long t0 = System.nanoTime();
            FrameCompression.decodeRle(rleBody, pixels.length);
            rleDecodeTotal += System.nanoTime() - t0;
        }

        final long raw = (long) pixels.length * 4L;
        System.out.printf("[wire-bench] %s（原始 %d B）%n", what, raw);
        System.out.printf("              zlib: %7d B（%5.1f%%） 编码 %6.3f ms%n",
                zlibSize, 100.0 * zlibSize / raw, zlibTotal / 1e6 / iterations);
        System.out.printf("              RLE : %7d B（%5.1f%%） 编码 %6.3f ms  解码 %6.3f ms%n",
                rleSize, 100.0 * rleSize / raw, rleTotal / 1e6 / iterations, rleDecodeTotal / 1e6 / iterations);
    }

    private static byte[] deflate(byte[] data, int level) throws Exception {
        final Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(data);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2);
            final byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                final int n = deflater.deflate(buf);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] toBytes(int[] pixels) {
        final byte[] out = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            final int v = pixels[i];
            out[i * 4] = (byte) (v >>> 24);
            out[i * 4 + 1] = (byte) (v >>> 16);
            out[i * 4 + 2] = (byte) (v >>> 8);
            out[i * 4 + 3] = (byte) v;
        }
        return out;
    }

    /** 逐格异色（每格 8x16 一个颜色，格间不同）—— 最接近"满屏字符、颜色各异"。 */
    private static int[] terminalPixels() {
        final int[] px = new int[PIXELS];
        final Random rnd = new Random(11L);
        for (int cy = 0; cy < H; cy += TrueColorScreen.CELL_H) {
            for (int cx = 0; cx < W; cx += TrueColorScreen.CELL_W) {
                final int argb = 0xFF000000 | rnd.nextInt(0x1000000);
                for (int y = cy; y < cy + TrueColorScreen.CELL_H; y++) {
                    for (int x = cx; x < cx + TrueColorScreen.CELL_W; x++) {
                        px[y * W + x] = argb;
                    }
                }
            }
        }
        return px;
    }

    /** 整行同色（终端背景成行）—— run 很长的形态。 */
    private static int[] rowsSameColorPixels() {
        final int[] px = new int[PIXELS];
        for (int y = 0; y < H; y++) {
            java.util.Arrays.fill(px, y * W, y * W + W, 0xFF101010 | (y << 8));
        }
        // 每行撒一些"字模"像素，避免过于理想
        final Random rnd = new Random(5L);
        for (int y = 0; y < H; y += 2) {
            for (int k = 0; k < 40; k++) {
                px[y * W + rnd.nextInt(W)] = 0xFF123456;
            }
        }
        return px;
    }

    private static int[] noisyPixels() {
        final int[] px = new int[PIXELS];
        final Random rnd = new Random(7L);
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | rnd.nextInt(0x1000000);
        }
        return px;
    }
}
