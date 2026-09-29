package com.hdf.cryptand.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;

import java.util.Arrays;

/**
 * 一个区块段（region，16*16*16 = 4096 格）的水位场。
 *
 * <p>这是「水位存 side table」的载体：世界方块状态里<b>不再</b>保存 ffluid_level，
 * 水位只活在这里，方块侧只在跨 0 边界（有水/无水）时才写回。
 *
 * <p>纯数组、整数水位、增量维护总体积（守恒不变量可就地断言）。
 */
public final class WaterLevelField {

    /** 格数（= 一个 chunk section）。 */
    public static final int CELLS = SectionCursor.SECTION_CELLS;
    /** 水位上限。 */
    public static final int MAX_LEVEL = FluidCellKind.MAX_LEVEL;

    private final byte[] kind = new byte[CELLS];
    private final byte[] level = new byte[CELLS];
    private int volume;

    /**
     * 「有水格」位图（{@code bit = 该格水位 &gt; 0}），长度 {@code CELLS / 64}。
     *
     * <p>求解器（{@code SpreadSolver.equalizeLines}）靠它稀疏枚举连通体的起点，
     * 不必每次扫完整段 4096 格 —— 实机大多数 region 只有几格水。
     * 与 {@link #setLevel} / {@link #setKind} / {@link #clear} 严格同步。
     */
    private final long[] waterBits = new long[CELLS >> 6];

    /**
     * 「恒定水源格」位图（{@code bit = 该格水位锁在满格}）。
     *
     * <p>采集层按「自然水源集合」打标（{@link NaturalWaterSources}：群系命中 + 世界生成时就在，
     * 见配置 {@code naturalSourceWater} / {@code naturalSourceWaterBiomes}）：这类格
     * <b>不会减少</b>，别处可以一直从它填充 —— 求解器在让出水之后立刻把水位补回 {@link #MAX_LEVEL}。
     * 语义是<b>无限水源</b>：连通水域总量会因此增长（凭空产水），这是设计意图。
     */
    private final long[] sourceBits = new long[CELLS >> 6];

    /**
     * 「侧表有权威记录」位图（{@code bit = 这一格被显式写过水位}）。
     *
     * <p>★ 用来把「**没有记录**」和「**记录恰好是 0**」分开：旧实现用 0 表示"没记录"，
     * 于是 {@code forget} 清掉一格后，{@link FluidLevels#resolve} 会退回世界的旧投影 ——
     * 源方块的世界投影恒为 8（LEVEL=0）⇒ 侧表丢一次，水位就从任意值**跳回 8**
     * （实机症状：源格不消耗、水凭空变多）。
     */
    private final long[] presentBits = new long[CELLS >> 6];

    public WaterLevelField() {
        clear();
    }

    /** 全部重置为空气、水位 0、无恒定水源。 */
    public void clear() {
        Arrays.fill(kind, (byte) FluidCellKind.AIR);
        Arrays.fill(level, (byte) 0);
        Arrays.fill(waterBits, 0L);
        Arrays.fill(sourceBits, 0L);
        Arrays.fill(presentBits, 0L);
        volume = 0;
    }

    public static int indexOf(final int lx, final int ly, final int lz) {
        return SectionCursor.linearIndex(lx, ly, lz);
    }

    public int kind(final int index) {
        return kind[index];
    }

    public void setKind(final int index, final int newKind) {
        final int oldLevel = level[index];
        kind[index] = (byte) newKind;
        if (oldLevel != 0 && !FluidCellKind.canHold(newKind)) {
            // 变成不可容纳：水被挤掉（调用方负责把这部分水搬走或记账）
            //   ★ 这里绕过 setLevel 直写数组，必须自己补 present 位：否则「侧表已判定这格是 0」
            //   在 resolve 眼里等于「没有记录」⇒ 会退回世界的旧投影（幽灵水 / 水位回跳）。
            level[index] = 0;
            presentBits[index >> 6] |= 1L << (index & 63);
            volume -= oldLevel;
            waterBits[index >> 6] &= ~(1L << (index & 63));
        }
    }

    public int level(final int index) {
        return level[index];
    }

    /**
     * 设置水位（0..{@link #MAX_LEVEL}）。
     *
     * @return 之前的旧水位
     */
    public int setLevel(final int index, final int newLevel) {
        if (newLevel < 0 || newLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("level out of range 0.." + MAX_LEVEL + ": " + newLevel);
        }
        final int old = level[index];
        if (old == newLevel) {
            return old;
        }
        if (newLevel != 0 && !FluidCellKind.canHold(kind[index])) {
            throw new IllegalStateException("cell " + index + " kind=" + FluidCellKind.name(kind[index])
                    + " cannot hold water");
        }
        level[index] = (byte) newLevel;
        presentBits[index >> 6] |= 1L << (index & 63);      // 显式写过 ⇒ 这一格「有权威记录」
        volume += newLevel - old;
        if (newLevel == 0) {
            waterBits[index >> 6] &= ~(1L << (index & 63));
        } else {
            waterBits[index >> 6] |= 1L << (index & 63);
        }
        return old;
    }

    /**
     * 求解期的压力场：这一格所在水域的目标水面（1/8 格为单位）。
     *
     * <p>由 {@code SpreadSolver.equalizeLines} 每轮用邻域松弛算出来，随解算生命周期存在；
     * {@code stepCell} 用它判断「哪些水在水面之上、可以往外流」。
     */
    private final int[] surface = new int[CELLS];

    public int surface(final int index) {
        return surface[index];
    }

    public void setSurface(final int index, final int value) {
        surface[index] = value;
    }

    /** 有水格位图（求解器只读；{@code bit = level &gt; 0}）。 */
    public long[] waterBits() {
        return waterBits;
    }

    /** 位图与水位的自检：任何绕过 {@link #setLevel} 的写法都会在这里露出来（离线闸门用）。 */
    public boolean waterBitsConsistent() {
        for (int i = 0; i < CELLS; i++) {
            final boolean has = (waterBits[i >> 6] & (1L << (i & 63))) != 0;
            if (has != (level[i] > 0)) {
                return false;
            }
        }
        return true;
    }

    /** 侧表里这一格有没有「权威水位」记录（与「水位恰好为 0」区分开）。 */
    public boolean isPresent(final int index) {
        return (presentBits[index >> 6] & (1L << (index & 63))) != 0;
    }

    /** 抹掉这一格的水位记录（值 + present 一起清）：外部改过世界、侧表这格已过期时用。 */
    public void forget(final int index) {
        setLevel(index, 0);
        presentBits[index >> 6] &= ~(1L << (index & 63));
    }

    /** 这一格是不是「恒定水源」（水位锁在满格：让出去的水立刻补回，自己不减少）。 */
    public boolean isSource(final int index) {
        return (sourceBits[index >> 6] & (1L << (index & 63))) != 0;
    }

    /** 打/清「恒定水源」标记（只有采集层会调用，见 RegionSnapshot 的群系判定）。 */
    public void setSource(final int index, final boolean on) {
        if (on) {
            sourceBits[index >> 6] |= 1L << (index & 63);
        } else {
            sourceBits[index >> 6] &= ~(1L << (index & 63));
        }
    }

    public int capacity(final int index) {
        return FluidCellKind.capacity(kind[index]);
    }

    /** 当前总水量（增量维护）。 */
    public int volume() {
        return volume;
    }

    public boolean hasWater() {
        return volume > 0;
    }

    /** 重算总体积（校验用）。 */
    public int recomputeVolume() {
        int sum = 0;
        for (final byte b : level) {
            sum += b;
        }
        volume = sum;
        return sum;
    }

    /** 把水位数组复制出来（离线测试与快照用）。 */
    public int[] copyLevels() {
        final int[] out = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            out[i] = level[i];
        }
        return out;
    }

    @Override
    public String toString() {
        return "WaterLevelField{volume=" + volume + "}";
    }
}