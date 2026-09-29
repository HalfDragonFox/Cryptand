package com.hdf.cryptand.lod;

/**
 * ===== 3D LOD 内置策略（common，纯 Java 零 MC）=====
 *
 * <p>内置版只做<b>距离分段</b>：离视点越远，几何简化级别越高（2 的幂，夹到 {@code maxLevel}）。
 * 真要做体素/区块几何简化（以及接 Voxy / Distant Horizons）属于 3D 后端的事 —— 那时由后端实现返回，
 * 本类退为兜底。</p>
 */
public final class Lod3D {

    private Lod3D() {
    }

    /** 距离 ⇒ 级别：{@code 2^n}，n 由"距离占视距的比例 × maxLevel"给出。 */
    public static LodLevel level(Lod3DRequest request) {
        if (request == null) {
            return LodLevel.NONE;
        }
        final double ratio = request.distanceBlocks() / request.viewDistanceBlocks();
        if (ratio <= 0.0 || request.maxLevel() <= 1) {
            return new LodLevel(1, 1, 0, false);
        }
        final double raw = Math.min(1.0, ratio) * request.maxLevel();
        int level = 1;
        int n = 0;
        while (n < (int) raw && level < request.maxLevel()) {
            level <<= 1;
            n++;
        }
        return new LodLevel(level, level, 0, false);
    }
}
