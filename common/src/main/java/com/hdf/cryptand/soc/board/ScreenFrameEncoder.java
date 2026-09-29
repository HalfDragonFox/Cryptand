package com.hdf.cryptand.soc.board;

/**
 * ===== 屏幕帧编码器（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>把 GPU 侧合成的 <b>RGB565 帧</b>按目标屏<b>自己的色深</b>写进 {@link TrueColorScreen}。
 * 这是"四档真彩屏（1 / 8 / 16 / 24bpp）"的唯一编码路径。</p>
 *
 * <h3>为什么要有它（用户定案 2026-09-29）</h3>
 * <ul>
 *   <li>屏的色深<b>由屏自己声明</b>（{@code TrueScreenSettings.screenBppByTier}），
 *       不由 GPU 或 OC 的 {@code ColorDepth}（那个枚举只有 1/4/8/16，装不下 24bpp）决定；</li>
 *   <li>帧窗口协议<b>不动</b>：仍是 RGB565（C 固件 {@code excode/firmware/common/display.c}
 *       的容量校验依赖 {@link DisplayWindow#rgb565WindowBytes}）——
 *       色深差异全部在"写进 VRAM"这一步消化。</li>
 * </ul>
 *
 * <h3>三条路径</h3>
 * <ol>
 *   <li><b>16bpp</b>：RGB565 小端与设备 16bpp VRAM 逐字节同构 ⇒ 整块 {@code arraycopy}，零转换；</li>
 *   <li><b>调色板色深（1/2/4/8bpp）</b>：先 {@link #preparePalette} 装表，再逐像素走
 *       {@link ScreenImageQuantizer#nearestPaletteIndex}（全项目唯一那条距离算式）取索引；</li>
 *   <li><b>直色（24/32bpp）</b>：逐像素 {@link TrueColorScreen#setRgb} 展开。</li>
 * </ol>
 *
 * <h3>调色板约定（本类内置，唯一一份）</h3>
 * <ul>
 *   <li>1bpp：黑 / 白；</li>
 *   <li>2bpp：黑 / 白 / 红 / 蓝；</li>
 *   <li>4bpp：{@link OcPalette} 的 16 色（与文本模式索引 0..15 同一张表）；</li>
 *   <li>8bpp：6×6×6 色立方（216）+ 40 级灰阶 = 256 色。</li>
 * </ul>
 *
 * <p><b>调用纪律</b>：调用前设备必须已处于 {@link TrueColorScreen.Mode#GRAPHICS}
 * （{@code setIndex}/{@code setRgb} 会拒绝非图形面）——顺序仍是"先 configure、
 * 再 applyDerivedFace(GRAPHICS)、最后编码"，见 {@code OcComponentBus.presentImage}。</p>
 */
public final class ScreenFrameEncoder {

    /** 1bpp 单色表：黑 / 白。 */
    private static final int[] MONO = {0x000000, 0xFFFFFF};

    /** 2bpp 四色表：黑 / 白 / 红 / 蓝。 */
    private static final int[] QUAD = {0x000000, 0xFFFFFF, 0xFF0000, 0x0000FF};

    /** 8bpp 表：6×6×6 色立方 + 40 级灰阶（只构造一次）。 */
    private static final int[] EIGHT = buildEight();

    private ScreenFrameEncoder() {
    }

    /**
     * 目标色深的调色板（直色色深返回 {@code null}）。
     *
     * @throws IllegalArgumentException 色深不是 1/2/4/8/16/24/32 之一
     */
    public static int[] paletteFor(int bpp) {
        return switch (bpp) {
            case 1 -> MONO;
            case 2 -> QUAD;
            case 4 -> {
                final int[] p = new int[OcPalette.size()];
                for (int i = 0; i < p.length; i++) {
                    p[i] = OcPalette.rgb(i);
                }
                yield p;
            }
            case 8 -> EIGHT;
            case 16, 24, 32 -> null;
            default -> throw new IllegalArgumentException(
                    "不支持的色深：" + bpp + "bpp（支持 1/2/4/8/16/24/32）");
        };
    }

    /**
     * 把调色板装进设备（直色设备是空操作）。调用方在 {@code configure} 之后调一次即可
     * ——本方法不缓存任何状态，重复调用只是重写同一张表。
     */
    public static void preparePalette(TrueColorScreen device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (!device.depth().palette()) {
            return;
        }
        final int[] palette = paletteFor(device.depth().bits());
        final int size = Math.min(palette.length, device.paletteSize());
        for (int i = 0; i < size; i++) {
            device.setPaletteColor(i, palette[i]);
        }
    }

    /**
     * 把一帧 RGB565 编码进设备。
     *
     * @param device 目标设备（尺寸/色深必须已经 configure 好）
     * @param rgb565 帧像素（小端，长度 ≥ {@code width × height × 2}）
     * @param width  帧宽（像素）
     * @param height 帧高（像素）
     */
    public static void encodeInto(TrueColorScreen device, byte[] rgb565, int width, int height) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (rgb565 == null) {
            throw new IllegalArgumentException("rgb565 must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("帧尺寸非法：" + width + "x" + height);
        }
        if (device.width() != width || device.height() != height) {
            throw new IllegalArgumentException("帧尺寸与设备不符：帧 " + width + "x" + height
                    + "，设备 " + device.width() + "x" + device.height());
        }
        final int need = width * height * 2;
        if (rgb565.length < need) {
            throw new IllegalArgumentException("帧字节不足：需要 " + need + "，给了 " + rgb565.length);
        }
        if (device.depth().bits() == 16) {
            // 快路径：RGB565 小端 = 设备 16bpp VRAM（逐字节同构，零转换）
            final int copy = Math.min(need, device.vram().length);
            System.arraycopy(rgb565, 0, device.vram(), 0, copy);
            return;
        }
        final boolean indexed = device.depth().palette();
        final int[] palette = indexed ? paletteFor(device.depth().bits()) : null;
        for (int y = 0; y < height; y++) {
            final int rowAt = y * width * 2;
            for (int x = 0; x < width; x++) {
                final int at = rowAt + x * 2;
                final int rgb = decode565(rgb565[at], rgb565[at + 1]);
                if (indexed) {
                    device.setIndex(x, y, ScreenImageQuantizer.nearestPaletteIndex(rgb, palette));
                } else {
                    device.setRgb(x, y, rgb);
                }
            }
        }
    }

    /**
     * RGB565（小端两字节）→ {@code 0xRRGGBB}；位扩展与
     * {@link TrueColorScreen#rgbAt} 的 16bpp 分支<b>同一算式</b>（往返必须对称）。
     */
    public static int decode565(byte lo, byte hi) {
        final int v = (lo & 0xFF) | ((hi & 0xFF) << 8);
        final int r = (v >> 11) & 0x1F;
        final int g = (v >> 5) & 0x3F;
        final int b = v & 0x1F;
        return ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
    }

    private static int[] buildEight() {
        final int[] p = new int[256];
        int i = 0;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    p[i++] = (r * 51) << 16 | (g * 51) << 8 | (b * 51);
                }
            }
        }
        for (int k = 0; k < 40; k++) {
            final int v = k * 255 / 39;
            p[i++] = v << 16 | v << 8 | v;
        }
        return p;
    }
}
