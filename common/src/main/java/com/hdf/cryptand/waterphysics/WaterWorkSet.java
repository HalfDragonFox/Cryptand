package com.hdf.cryptand.waterphysics;

/**
 * 一个 region 的求解工作集 —— 【实际水格 + 其相邻】中被判定要参与求解的格。
 *
 * <p>工作集语义：<b>集合之外的格一律不看</b>（既不读世界、
 * 也不参与求解）。采集侧（主线程）分帧往集合里加格，本帧额度用尽就停，
 * 游标保留、下一帧继续；<b>整个 region 采集完才提交求解</b>，绝不一半提交。
 *
 * <p>纯 Java，无 MC 依赖；闸门见 {@code WaterphysicsSelfTest#testWaterWorkSet}。
 */
public final class WaterWorkSet {

    /** 容量 = 一个 chunk section 的格数。 */
    public static final int MAX_CELLS = WaterLevelField.CELLS;

    /** section 内线性索引列表（0..4095）。 */
    private final int[] cells = new int[MAX_CELLS];
    /** 去重标记：0 = 不在集合里，1 = 在集合里。 */
    private final byte[] marks = new byte[MAX_CELLS];
    private int size;

    /** 全格工作集（离线测试与「整段重算」入口用）。 */
    public static WaterWorkSet full() {
        final WaterWorkSet set = new WaterWorkSet();
        for (int i = 0; i < MAX_CELLS; i++) {
            set.add(i);
        }
        return set;
    }

    /**
     * 加入一个 section 内线性索引。
     *
     * @return true = 本次真的加进去了；false = 已在集合里
     */
    public boolean add(final int index) {
        if (index < 0 || index >= MAX_CELLS) {
            throw new IndexOutOfBoundsException("index=" + index + " (0.." + (MAX_CELLS - 1) + ")");
        }
        if (marks[index] != 0) {
            return false;
        }
        marks[index] = 1;
        cells[size++] = index;
        return true;
    }

    public boolean contains(final int index) {
        return index >= 0 && index < MAX_CELLS && marks[index] != 0;
    }

    public int size() {
        return size;
    }

    public int get(final int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("i=" + i + " size=" + size);
        }
        return cells[i];
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** 清空并复位所有标记，实例可复用。 */
    public void clear() {
        for (int i = 0; i < size; i++) {
            marks[cells[i]] = 0;
        }
        size = 0;
    }

    @Override
    public String toString() {
        return "WaterWorkSet{size=" + size + "}";
    }
}
