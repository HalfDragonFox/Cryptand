package com.hdf.cryptand.soc.board;

/**
 * ===== 屏幕采样策略（common，纯 Java 零 MC，2026-09-28）=====
 *
 * <p>用户定案：<b>服务器自己算玩家到屏的距离</b>，并按距离决定"横竖各跨多少像素采集"
 * （远处的屏不需要全分辨率）。客户端不带参数 —— 它只按收到的采样步长贴图。</p>
 *
 * <h3>推导</h3>
 * <p>一块 {@code screenBlocks} 格大小、{@code screenPixels} 像素的屏，在距离 {@code d} 格处，
 * 每格张角 {@code screenBlocks/d} 弧度；视口每弧度有 {@code pixelsPerRadian} 个像素
 * （≈ {@code 视口高 / (2·tan(fovY/2))}）。于是<b>视口上一个像素 ≈ 屏上
 * {@code screenPixels·d / (screenBlocks·pixelsPerRadian)} 个像素</b>。取这个值的
 * 2 的幂向上取整（保证不欠采样），再夹到 [{@code 1}, {@code maxStep}]。</p>
 *
 * <p>用 2 的幂是为了客户端的放大是整数倍、不出现采样相位抖动。</p>
 */
public final class ScreenSamplingPolicy {

    /** 默认视口像素/弧度（1080p、fovY=70° ⇒ 1080 / (2·tan(35°)) ≈ 771）。 */
    public static final double DEFAULT_PIXELS_PER_RADIAN = 771.0;

    /** 默认最粗采样（横竖都不超过 8 像素抽 1 个） */
    public static final int DEFAULT_MAX_STEP = 8;

    private ScreenSamplingPolicy() {
    }

    public static int stepFor(double distanceBlocks, int screenPixels, int screenBlocks, int maxStep) {
        return stepFor(distanceBlocks, screenPixels, screenBlocks, DEFAULT_PIXELS_PER_RADIAN, maxStep);
    }

    /**
     * @param distanceBlocks 玩家视点到屏中心的距离（格）；<=0 视为贴着屏
     * @param screenPixels   屏在该轴上的像素数（宽用宽、高用高）
     * @param screenBlocks   屏在该轴上的格数
     * @param pixelsPerRadian 视口像素/弧度（见 {@link #DEFAULT_PIXELS_PER_RADIAN}）
     * @param maxStep        上限（<1 视为 1）
     * @return 采样步长：2 的幂，∈ [1, max(1,maxStep)]
     */
    public static int stepFor(double distanceBlocks, int screenPixels, int screenBlocks,
                              double pixelsPerRadian, int maxStep) {
        final int cap = Math.max(1, maxStep);
        if (screenPixels <= 0 || screenBlocks <= 0 || pixelsPerRadian <= 0.0) {
            return 1;
        }
        final double d = distanceBlocks <= 0.0 ? 1.0e-6 : distanceBlocks;
        final double need = (double) screenPixels * d / ((double) screenBlocks * pixelsPerRadian);
        if (need <= 1.0) {
            return 1;
        }
        long step = 1L;
        while (step < (long) Math.ceil(need) && step < cap) {
            step <<= 1;
        }
        return (int) Math.min(step, cap);
    }

    /** 采样后的网格边长：{@code ceil(pixels / step)}，至少 1。 */
    public static int gridSize(int pixels, int step) {
        if (pixels <= 0) {
            return 1;
        }
        final int s = step < 1 ? 1 : step;
        return Math.max(1, (pixels + s - 1) / s);
    }
}
