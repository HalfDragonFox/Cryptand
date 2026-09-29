package com.hdf.cryptand.waterphysics;

/**
 * <b>自然水源的首次登记策略</b>（纯 Java，零 MC 依赖；离线闸门见
 * {@code WaterphysicsSelfTest#testNaturalWaterSources}）。
 *
 * <p>一次登记只做三件事，且<b>只在一个 section 第一次被看到时</b>做：
 * <ol>
 *   <li>看这个 section 的 16×16 列里有没有「群系命中」的列（列命中表由 MC 翻译层预先算好，
 *       见 neoforge 的 {@code NaturalWaterRegistry} —— 群系是逐列的，不是逐格的，
 *       所以这里只接受一张 {@code byte[256]}：0 未判 / 1 命中 / 2 不命中）；</li>
 *   <li>一列都没命中 ⇒ <b>什么都不做</b>：不标「已登记」、不建位图。这样非水系群系
 *       （山地、沙漠…）的区块不占存档体积，玩家在那里放的水也永远不可能是自然水源；</li>
 *   <li>有命中列 ⇒ 标「已登记」，并把命中列上<b>当前</b>存在的水源方块记进位图。
 *       这一步之后该 section 再也不会被扫 —— 玩家之后放的水（哪怕就在海洋里）
 *       落在已登记的 section 上，不会被补登记。</li>
 * </ol>
 *
 * <p><b>有意取舍</b>：老存档第一次装本模组时，玩家以前放的水与地形生成的水无法区分，
 * 会被一并登记（只有群系命中的列才登记）。这是「不逐格存生成信息」的必然代价，
 * 换来的是每格判定 O(1) 与极小的存档体积。
 */
public final class NaturalWaterScanner {

    /** 一个 section 的列数（16×16）。 */
    public static final int COLUMNS = 16 * 16;

    /** 列命中：未判（MC 侧只对真正需要判定的列填值）。 */
    public static final byte COLUMN_UNKNOWN = 0;
    /** 列命中：这一列的群系在配置列表里。 */
    public static final byte COLUMN_HIT = 1;
    /** 列不命中：这一列的群系不在配置列表里。 */
    public static final byte COLUMN_MISS = 2;

    /** 「这一格是不是水源方块」的探针（由 MC 侧实现：水方块且 {@code LEVEL == 0}）。 */
    @FunctionalInterface
    public interface SourceProbe {
        /**
         * @param localX section 内局部 x（0..15）
         * @param localY section 内局部 y（0..15）
         * @param localZ section 内局部 z（0..15）
         * @return true = 这一格是世界里已经存在的<b>水源</b>方块
         */
        boolean isSourceWater(int localX, int localY, int localZ);
    }

    private NaturalWaterScanner() {
    }

    /** (局部 x, 局部 z) → 列下标（与 {@code SectionCursor.linearIndex} 的位序一致）。 */
    public static int columnIndex(final int localX, final int localZ) {
        return (localZ << 4) | localX;
    }

    /** 这一列是不是命中配置的群系。 */
    public static boolean isColumnHit(final byte[] columnHit, final int localX, final int localZ) {
        return columnHit[columnIndex(localX, localZ)] == COLUMN_HIT;
    }

    /** 这张列命中表里有没有任何一列命中（没有 ⇒ 这个 section 根本不登记）。 */
    public static boolean anyColumnHit(final byte[] columnHit) {
        for (final byte b : columnHit) {
            if (b == COLUMN_HIT) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首次登记一个 section。
     *
     * @param sources    自然水源集合
     * @param sectionKey section 键（{@code SectionCursor.key}）
     * @param columnHit  列命中表（长度 {@link #COLUMNS}）
     * @param probe      水源方块探针（只在命中列上被调用）
     * @return true = 这一次真的登记了该 section（调用方据此标脏持久化）；
     *         false = 已经登记过，或者群系一列都没命中（没登记）
     */
    public static boolean register(final NaturalWaterSources sources, final long sectionKey,
                                   final byte[] columnHit, final SourceProbe probe) {
        if (sources.isRegistered(sectionKey)) {
            // ★ 首次见才算「世界生成时就在」。已经登记过 ⇒ 之后放的水一律不补登记。
            return false;
        }
        if (!anyColumnHit(columnHit)) {
            return false;   // 群系不在列表里：不登记、不标脏、不占存档
        }
        sources.markRegistered(sectionKey);
        for (int index = 0; index < NaturalWaterSources.CELLS; index++) {
            final int localX = index >>> 8;
            final int localZ = index & 0xF;
            if (!isColumnHit(columnHit, localX, localZ)) {
                continue;
            }
            final int localY = (index >>> 4) & 0xF;
            if (probe.isSourceWater(localX, localY, localZ)) {
                sources.markNaturalSource(sectionKey, index);
            }
        }
        return true;
    }
}
