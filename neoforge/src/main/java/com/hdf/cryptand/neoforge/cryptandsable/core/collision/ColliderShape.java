package com.hdf.cryptand.neoforge.cryptandsable.core.collision;

/**
 * 碰撞体形状（碰撞体 = 物理体的局部包围形状，独立于质量/惯量运算）。
 *
 * <p>对齐 C8：核心不区分具体结构，刚体/柔体都只是"有形状的体"。形状用
 * 局部 AABB（中心 + 半尺寸）表示，随刚核姿态变换成世界空间 OBB。
 * 柔体可用多个子形状（每个粒子一个小球），MVP 先用单个包围体 + 可选粒子球集合。
 */
public final class ColliderShape {
    /** 局部中心偏移（相对刚核 position）。 */
    public final double cx, cy, cz;
    /** 半尺寸（AABB 半径）。 */
    public final double hx, hy, hz;
    /** 可选：粒子级小球（柔体用）半尺寸；null=整体盒。 */
    public final double[] particleRadii;

    public ColliderShape(double cx, double cy, double cz,
                         double hx, double hy, double hz,
                         double[] particleRadii) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.hx = hx;
        this.hy = hy;
        this.hz = hz;
        this.particleRadii = particleRadii;
    }

    /** 从体素 bounds（局部整数坐标，含端点）构造包围盒形状。 */
    public static ColliderShape fromVoxelBounds(int minX, int minY, int minZ,
                                                int maxX, int maxY, int maxZ) {
        double cx = (minX + maxX + 1) * 0.5;
        double cy = (minY + maxY + 1) * 0.5;
        double cz = (minZ + maxZ + 1) * 0.5;
        double hx = (maxX - minX + 1) * 0.5;
        double hy = (maxY - minY + 1) * 0.5;
        double hz = (maxZ - minZ + 1) * 0.5;
        return new ColliderShape(cx, cy, cz, hx, hy, hz, null);
    }

    /** 复制一份（用于可变 PVC）。 */
    public ColliderShape copy() {
        double[] pr = particleRadii != null ? particleRadii.clone() : null;
        return new ColliderShape(cx, cy, cz, hx, hy, hz, pr);
    }
}