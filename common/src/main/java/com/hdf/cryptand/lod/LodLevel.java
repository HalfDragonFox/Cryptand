package com.hdf.cryptand.lod;

/**
 * ===== LOD 档位（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户定案：LOD 抽象为 <b>2D 与 3D 两种形式</b>，共用一个核心壳。档位就是"按观察条件降多少"的
 * 唯一答案 —— 它<b>不含任何渲染实现</b>（渲染后端在 MC 侧），2D/3D 都返回它。</p>
 *
 * <ul>
 *   <li>{@code stepX}/{@code stepY}：2D 采样步长（2 的幂；1 = 全分辨率）。3D 用 {@code stepX} 表示几何简化级别。</li>
 *   <li>{@code maxFps}：帧率上限（0 = 不限）。</li>
 *   <li>{@code compressed}：是否要求压缩帧（如 PNG）下发。</li>
 * </ul>
 */
public final class LodLevel {

    /** 不降质：全分辨率、不限帧、不压缩。 */
    public static final LodLevel NONE = new LodLevel(1, 1, 0, false);

    private final int stepX;
    private final int stepY;
    private final int maxFps;
    private final boolean compressed;

    public LodLevel(int stepX, int stepY, int maxFps, boolean compressed) {
        this.stepX = Math.max(1, stepX);
        this.stepY = Math.max(1, stepY);
        this.maxFps = Math.max(0, maxFps);
        this.compressed = compressed;
    }

    public int stepX() {
        return stepX;
    }

    public int stepY() {
        return stepY;
    }

    public int maxFps() {
        return maxFps;
    }

    public boolean compressed() {
        return compressed;
    }

    /** 是否就是"不降质"。 */
    public boolean isFull() {
        return stepX == 1 && stepY == 1 && maxFps == 0 && !compressed;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LodLevel)) {
            return false;
        }
        final LodLevel l = (LodLevel) other;
        return stepX == l.stepX && stepY == l.stepY && maxFps == l.maxFps && compressed == l.compressed;
    }

    @Override
    public int hashCode() {
        return ((stepX * 31 + stepY) * 31 + maxFps) * 31 + (compressed ? 1 : 0);
    }

    @Override
    public String toString() {
        return "LodLevel[step=" + stepX + "x" + stepY + ", maxFps=" + maxFps + ", compressed=" + compressed + "]";
    }
}
