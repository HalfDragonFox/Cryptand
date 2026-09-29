package com.hdf.cryptand.lod;

/**
 * ===== 2D LOD 请求（common，纯 Java 零 MC）=====
 *
 * <p>口径来自原 {@code ScreenSamplingPolicy} 的推导（已存在，不重复造）：一块 {@code sourceBlocks} 格、
 * {@code sourcePixels} 像素的源，在距离 {@code distanceBlocks} 处，每格张角 {@code sourceBlocks/d} 弧度，
 * 视口每弧度 {@code pixelsPerRadian} 像素 ⇒ 视口一像素 ≈ 源上
 * {@code sourcePixels·d/(sourceBlocks·pixelsPerRadian)} 个像素。取 2 的幂向上取整（不欠采样），夹到 [1, maxStep]。</p>
 *
 * <p>用户最新定案：这个距离由<b>客户端</b>算（不再由服务器算）。</p>
 */
public final class Lod2DRequest {

    private final int sourcePixelsX;
    private final int sourcePixelsY;
    private final int sourceBlocksX;
    private final int sourceBlocksY;
    private final double distanceBlocks;
    private final double pixelsPerRadian;
    private final int maxStep;
    private final int targetFps;
    private final boolean wantCompressed;

    public Lod2DRequest(int sourcePixelsX, int sourcePixelsY, int sourceBlocksX, int sourceBlocksY,
                        double distanceBlocks, double pixelsPerRadian, int maxStep,
                        int targetFps, boolean wantCompressed) {
        this.sourcePixelsX = sourcePixelsX;
        this.sourcePixelsY = sourcePixelsY;
        this.sourceBlocksX = Math.max(1, sourceBlocksX);
        this.sourceBlocksY = Math.max(1, sourceBlocksY);
        this.distanceBlocks = distanceBlocks;
        this.pixelsPerRadian = pixelsPerRadian;
        this.maxStep = Math.max(1, maxStep);
        this.targetFps = Math.max(0, targetFps);
        this.wantCompressed = wantCompressed;
    }

    public int sourcePixelsX() {
        return sourcePixelsX;
    }

    public int sourcePixelsY() {
        return sourcePixelsY;
    }

    public int sourceBlocksX() {
        return sourceBlocksX;
    }

    public int sourceBlocksY() {
        return sourceBlocksY;
    }

    public double distanceBlocks() {
        return distanceBlocks;
    }

    public double pixelsPerRadian() {
        return pixelsPerRadian;
    }

    public int maxStep() {
        return maxStep;
    }

    public int targetFps() {
        return targetFps;
    }

    public boolean wantCompressed() {
        return wantCompressed;
    }
}
