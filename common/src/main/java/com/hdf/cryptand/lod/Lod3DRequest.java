package com.hdf.cryptand.lod;

/**
 * ===== 3D LOD 请求（common，纯 Java 零 MC）=====
 *
 * <p>3D 与 2D 是<b>不同层面</b>：2D 是"屏/纹理降采样"，3D 是"世界几何（体素/区块）降细节"。
 * 外部渲染后端（Voxy / Distant Horizons）就是 3D 这一侧的后端。</p>
 */
public final class Lod3DRequest {

    private final double distanceBlocks;
    private final double viewDistanceBlocks;
    private final int maxLevel;
    private final boolean wantGeometrySimplify;

    public Lod3DRequest(double distanceBlocks, double viewDistanceBlocks, int maxLevel,
                        boolean wantGeometrySimplify) {
        this.distanceBlocks = distanceBlocks;
        this.viewDistanceBlocks = viewDistanceBlocks <= 0.0 ? 1.0 : viewDistanceBlocks;
        this.maxLevel = Math.max(1, maxLevel);
        this.wantGeometrySimplify = wantGeometrySimplify;
    }

    public double distanceBlocks() {
        return distanceBlocks;
    }

    public double viewDistanceBlocks() {
        return viewDistanceBlocks;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public boolean wantGeometrySimplify() {
        return wantGeometrySimplify;
    }
}
