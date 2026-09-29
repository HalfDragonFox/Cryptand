package com.hdf.cryptand.lod;

import com.hdf.cryptand.soc.board.ScreenSamplingPolicy;

/**
 * ===== 2D LOD 内置策略（common，纯 Java 零 MC）=====
 *
 * <p><b>不重复造</b>：算法内核早就在 {@link ScreenSamplingPolicy}（2026-09-28 落地，距离 ⇒ 2 的幂步长）。
 * 本类只是把它的口径接到 LOD 档位上，供内置后端与兜底路径使用。</p>
 */
public final class Lod2D {

    private Lod2D() {
    }

    /** 按请求算 2D 档位（步长各轴独立算；帧率/压缩照请求原样带出，内置策略不做限帧）。 */
    public static LodLevel level(Lod2DRequest request) {
        if (request == null) {
            return LodLevel.NONE;
        }
        final int stepX = ScreenSamplingPolicy.stepFor(request.distanceBlocks(), request.sourcePixelsX(),
                request.sourceBlocksX(), request.pixelsPerRadian(), request.maxStep());
        final int stepY = ScreenSamplingPolicy.stepFor(request.distanceBlocks(), request.sourcePixelsY(),
                request.sourceBlocksY(), request.pixelsPerRadian(), request.maxStep());
        return new LodLevel(stepX, stepY, request.targetFps(), request.wantCompressed());
    }

    /**
     * **平滑版**抽稀：每块取平均（而不是左上角点采样）。
     *
     * <p>用户 2026-09-29：「lod 核心支持**平滑** lod 阶梯…平滑默认开」。
     * 平滑有两层：{@link LodStairs} 用百分比插值让"目标分辨率"连续变化（档位之间不再突然跳）；
     * 这里用**块平均**让远处画面是"糊下去"而不是"抽成噪点" —— 点采样在文字屏上会明显丢笔画。</p>
     *
     * <p>尺寸口径与 {@link #downsample} 完全一致（同一 {@code downsampledWidth/Height}），
     * 所以两者可以按"是否平滑"互换而不动别处。</p>
     */
    public static void downsampleAveraged(int[] src, int srcW, int srcH, int stepX, int stepY, int[] dst) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("src/dst 不能为 null");
        }
        if (srcW <= 0 || srcH <= 0) {
            throw new IllegalArgumentException("源尺寸必须为正：" + srcW + "x" + srcH);
        }
        if (src.length < srcW * srcH) {
            throw new IllegalArgumentException("源缓冲太小：" + src.length + " < " + (srcW * srcH));
        }
        final int sx = Math.max(1, stepX);
        final int sy = Math.max(1, stepY);
        final int dstW = downsampledWidth(srcW, sx);
        final int dstH = downsampledHeight(srcH, sy);
        if (dst.length < dstW * dstH) {
            throw new IllegalArgumentException("目标缓冲太小：" + dst.length + " < " + (dstW * dstH));
        }
        for (int y = 0; y < dstH; y++) {
            final int y0 = y * sy;
            final int y1 = Math.min(y0 + sy, srcH);
            for (int x = 0; x < dstW; x++) {
                final int x0 = x * sx;
                final int x1 = Math.min(x0 + sx, srcW);
                long a = 0L;
                long r = 0L;
                long g = 0L;
                long b = 0L;
                int count = 0;
                for (int yy = y0; yy < y1; yy++) {
                    final int rowBase = yy * srcW;
                    for (int xx = x0; xx < x1; xx++) {
                        final int c = src[rowBase + xx];
                        a += (c >>> 24) & 0xFF;
                        r += (c >>> 16) & 0xFF;
                        g += (c >>> 8) & 0xFF;
                        b += c & 0xFF;
                        count++;
                    }
                }
                if (count <= 0) {
                    dst[y * dstW + x] = 0;
                    continue;
                }
                final int half = count / 2;
                final int aa = (int) ((a + half) / count);
                final int rr = (int) ((r + half) / count);
                final int gg = (int) ((g + half) / count);
                final int bb = (int) ((b + half) / count);
                dst[y * dstW + x] = (aa << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    /** 抽稀后的宽（调用方据此分配缓冲）。 */
    public static int downsampledWidth(int sourceWidth, int stepX) {
        return ScreenSamplingPolicy.gridSize(sourceWidth, Math.max(1, stepX));
    }

    /** 抽稀后的高（调用方据此分配缓冲）。 */
    public static int downsampledHeight(int sourceHeight, int stepY) {
        return ScreenSamplingPolicy.gridSize(sourceHeight, Math.max(1, stepY));
    }

    /**
     * 按步长抽稀一份 ARGB 缓冲 —— LOD 的"真的画小一点"（客户端 LOD 的最后一步）。
     *
     * <p><b>采样口径必须与 {@code ScreenImageComposer} 完全一致</b>（都是"每块取左上角"，
     * 见 `composeText` 里 `x = gx * stepW` / `y = gy * stepH`）：近/远档切换时如果两边口径不同，
     * 画面会在切换瞬间抖一下 —— 这种问题很难查，所以直接对齐口径而不是"看起来差不多"。</p>
     *
     * @param src  源缓冲（srcW × srcH，ARGB）
     * @param dst  目标缓冲，长度至少 {@code downsampledWidth × downsampledHeight}
     */
    public static void downsample(int[] src, int srcW, int srcH, int stepX, int stepY, int[] dst) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("src/dst 不能为 null");
        }
        if (srcW <= 0 || srcH <= 0) {
            throw new IllegalArgumentException("源尺寸必须为正：" + srcW + "x" + srcH);
        }
        if (src.length < srcW * srcH) {
            throw new IllegalArgumentException("源缓冲太小：" + src.length + " < " + (srcW * srcH));
        }
        final int sx = Math.max(1, stepX);
        final int sy = Math.max(1, stepY);
        final int dstW = downsampledWidth(srcW, sx);
        final int dstH = downsampledHeight(srcH, sy);
        if (dst.length < dstW * dstH) {
            throw new IllegalArgumentException("目标缓冲太小：" + dst.length + " < " + (dstW * dstH));
        }
        for (int y = 0; y < dstH; y++) {
            final int srcRow = Math.min(y * sy, srcH - 1) * srcW;
            final int dstRow = y * dstW;
            for (int x = 0; x < dstW; x++) {
                dst[dstRow + x] = src[srcRow + Math.min(x * sx, srcW - 1)];
            }
        }
    }
}
