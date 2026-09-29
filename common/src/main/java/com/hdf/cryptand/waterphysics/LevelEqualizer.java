package com.hdf.cryptand.waterphysics;

/**
 * 连通器均衡求解（整数守恒）。
 *
 * <p>把一组连通格子的水量均分：总和不变，任意两格差值最多 1。余数按索引升序分配，
 * 保证同一输入永远得到同一输出（确定性，可离线逐字节对拍）。
 */
public final class LevelEqualizer {

    private LevelEqualizer() {
    }

    /** 收集本 region 全部可容纳格子的索引（用于整体均衡）。 */
    public static int[] holders(final WaterLevelField field) {
        final int[] cells = new int[WaterLevelField.CELLS];
        int n = 0;
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (field.capacity(i) > 0) {
                cells[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(cells, n);
    }

    /**
     * 把 levels[0..count) 中 capacity &gt; 0 的格子均分水位。
     *
     * @return 发生变化的格子数
     */
    public static int equalize(final int[] levels, final int[] capacities, final int count) {
        if (levels.length < count || capacities.length < count) {
            throw new IllegalArgumentException("arrays shorter than count");
        }
        long total = 0;
        int holderCount = 0;
        for (int i = 0; i < count; i++) {
            if (capacities[i] > 0) {
                total += levels[i];
                holderCount++;
            }
        }
        if (holderCount == 0) {
            return 0;
        }
        final int base = (int) (total / holderCount);
        int remainder = (int) (total % holderCount);
        int changed = 0;
        for (int i = 0; i < count; i++) {
            if (capacities[i] <= 0) {
                continue;
            }
            int want = base + (remainder > 0 ? 1 : 0);
            if (remainder > 0) {
                remainder--;
            }
            if (want > capacities[i]) {
                want = capacities[i];
            }
            if (levels[i] != want) {
                levels[i] = want;
                changed++;
            }
        }
        return changed;
    }

    /**
     * 对水位场里给定的一组格子做均衡。
     *
     * @return 发生变化的格子数
     */
    public static int equalize(final WaterLevelField field, final int[] cells, final int count) {
        long total = 0;
        int holderCount = 0;
        for (int i = 0; i < count; i++) {
            final int idx = cells[i];
            if (field.capacity(idx) > 0) {
                total += field.level(idx);
                holderCount++;
            }
        }
        if (holderCount == 0) {
            return 0;
        }
        final int base = (int) (total / holderCount);
        int remainder = (int) (total % holderCount);
        int changed = 0;
        for (int i = 0; i < count; i++) {
            final int idx = cells[i];
            final int capacity = field.capacity(idx);
            if (capacity <= 0) {
                continue;
            }
            int want = base + (remainder > 0 ? 1 : 0);
            if (remainder > 0) {
                remainder--;
            }
            if (want > capacity) {
                want = capacity;
            }
            if (field.level(idx) != want) {
                field.setLevel(idx, want);
                changed++;
            }
        }
        return changed;
    }

    /** 对整块 region 做均衡（只处理可容纳格子）。 */
    public static int equalizeAll(final WaterLevelField field) {
        final int[] cells = holders(field);
        return equalize(field, cells, cells.length);
    }
}
