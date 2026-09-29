package com.hdf.cryptand.waterphysics;

/**
 * 水位几何数学（由原版水位↔高度的换算关系归纳而来，无 MC 依赖）。
 *
 * <p><b>整型/定点化</b>：原版高度公式是 binary 有理数
 * {@code height = (level / 8) * 0.9375 = level * 15 / 128}，分母 128 是 2 的幂 ⇒
 * <b>可以完全等量地用整型表示</b>（单位 1/128 格，零误差）。
 * 因此本类的全部中间量都是 int，只有最后交给渲染/碰撞时才做一次除法转 float。
 *
 * <p>唯一不能整型化的是最后那一次除法（顶点必须是浮点），以及四角平均的 /6
 * —— 这里把「分子」保持为 int，除 6 留到最后一步一起做。
 */
public final class WaterMath {

    /** 定点高度单位：1/128 格。 */
    public static final int HEIGHT_UNITS_PER_BLOCK = 128;
    /** 每级水位对应的定点高度（15/128 格）。 */
    public static final int HEIGHT_UNITS_PER_LEVEL = 15;
    /** 四角平均的分母（权重 4:1:1 之和）。 */
    public static final int CORNER_DIVISOR = 6;

    /** 满格高度（原版流体 15/16 = 0.9375）。 */
    public static final double FULL_HEIGHT = 0.9375;

    private WaterMath() {
    }

    /**
     * 水位 → 高度（定点，单位 1/128 格）。精确等量于 {@code (level/8)*0.9375}。
     *
     * @param level 0..8
     */
    public static int heightFixed(final int level) {
        if (level <= 0) {
            return 0;
        }
        return Math.min(level, FluidCellKind.MAX_LEVEL) * HEIGHT_UNITS_PER_LEVEL;
    }

    /** 定点高度 → 浮点（唯一一次除法，出口用）。 */
    public static float fixedToFloat(final int fixedHeight) {
        return fixedHeight / (float) HEIGHT_UNITS_PER_BLOCK;
    }

    public static double fixedToDouble(final int fixedHeight) {
        return fixedHeight / (double) HEIGHT_UNITS_PER_BLOCK;
    }

    /** 水位 → 世界高度（等量于原版的水位高度公式）。内部走定点，结果与原版一致。 */
    public static double heightOf(final int level) {
        return fixedToDouble(heightFixed(level));
    }

    /** 原版流体 tick 间隔的一半（下限 1）。 */
    public static int tickDelay(final int baseTickDelay) {
        return Math.max(1, baseTickDelay / 2);
    }

    /**
     * 渲染用四角高度的「分子」（顺序 NW, NE, SE, SW）。
     *
     * <p>每个角 = (中心*4 + 两个外侧邻居) / 6。为避免 1/6 的舍入，这里返回分子
     * （单位为 1/128/6 格），除法留到 {@link #cornerToFloat(int)}。
     */
    public static int[] cornerHeightsFixed(final int center, final int north, final int east,
                                           final int south, final int west) {
        final int hc = heightFixed(center);
        final int hn = heightFixed(north);
        final int he = heightFixed(east);
        final int hs = heightFixed(south);
        final int hw = heightFixed(west);
        return new int[] {
                hc * 4 + hn + hw,
                hc * 4 + hn + he,
                hc * 4 + hs + he,
                hc * 4 + hs + hw,
        };
    }

    /** 四角分子 → 浮点。 */
    public static float cornerToFloat(final int cornerNumerator) {
        return cornerNumerator / (float) (HEIGHT_UNITS_PER_BLOCK * CORNER_DIVISOR);
    }

    /** 四角分子 → 格数（double，测试用）。 */
    public static double cornerToDouble(final int cornerNumerator) {
        return cornerNumerator / (double) (HEIGHT_UNITS_PER_BLOCK * CORNER_DIVISOR);
    }

    /** 便捷：一次拿到四个角的浮点高度。 */
    public static float[] cornerHeightsFloat(final int center, final int north, final int east,
                                             final int south, final int west) {
        final int[] fixed = cornerHeightsFixed(center, north, east, south, west);
        return new float[] {
                cornerToFloat(fixed[0]),
                cornerToFloat(fixed[1]),
                cornerToFloat(fixed[2]),
                cornerToFloat(fixed[3]),
        };
    }

    /**
     * 由邻域水位差算水平流速方向（等势面下降方向），整型返回。
     *
     * <p>{@code dx = (h(west) - h(east)) / 2}、{@code dz = (h(north) - h(south)) / 2}，
     * 单位为 1/256 格；x+ = 东，z+ = 南。
     *
     * @return {dx, dz}
     */
    public static int[] flowVectorFixed(final int center, final int north, final int east,
                                        final int south, final int west) {
        final int dx = (heightFixed(west) - heightFixed(east));
        final int dz = (heightFixed(north) - heightFixed(south));
        return new int[] {dx, dz};
    }

    /** 整型流速 → 格/tick 的浮点（除以 256）。 */
    public static float flowToFloat(final int fixedFlow) {
        return fixedFlow / (float) (HEIGHT_UNITS_PER_BLOCK * 2);
    }

    /** 一组水位里的极差（判断是否已均衡）。 */
    public static int spread(final int[] levels, final int count) {
        if (count == 0) {
            return 0;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, levels[i]);
            max = Math.max(max, levels[i]);
        }
        return max - min;
    }

    /** 水位总量（守恒断言用）。 */
    public static long total(final int[] levels, final int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += levels[i];
        }
        return sum;
    }
}
