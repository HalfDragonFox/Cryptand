package com.hdf.cryptand.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;

/**
 * 流体体（一块 region 的水）黑盒模型 —— 沿用本项目「set / compute / get」范式：
 * 外部只 set 输入、调 compute、get 结果，不直接读内部状态。
 */
public final class FluidBodyModel {

    private final WaterLevelField field = new WaterLevelField();
    private long regionKey;

    /** 绑定到一个 region（会清空水位场）。 */
    public void bind(final long regionKey) {
        this.regionKey = regionKey;
        this.field.clear();
    }

    public long regionKey() {
        return regionKey;
    }

    // ---------- set ----------

    public void setKind(final int lx, final int ly, final int lz, final int kind) {
        field.setKind(SectionCursor.linearIndex(lx, ly, lz), kind);
    }

    public void setLevel(final int lx, final int ly, final int lz, final int level) {
        field.setLevel(SectionCursor.linearIndex(lx, ly, lz), level);
    }

    // ---------- get ----------

    public int level(final int lx, final int ly, final int lz) {
        return field.level(SectionCursor.linearIndex(lx, ly, lz));
    }

    public int kind(final int lx, final int ly, final int lz) {
        return field.kind(SectionCursor.linearIndex(lx, ly, lz));
    }

    public int volume() {
        return field.volume();
    }

    public WaterLevelField field() {
        return field;
    }

    // ---------- compute ----------

    /** 一步扩散（重力 + 水平），返回变化的格子数。 */
    public int spreadStep(final FluidCellView world, final FluidWritePlan out) {
        return SpreadSolver.step(field, world, regionKey, WaterWorkSet.full(), out);
    }

    /** 均匀水位（把总量均分到所有可容纳格子）；返回变化的格子数。 */
    public int equalize() {
        final int[] cells = new int[WaterLevelField.CELLS];
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            cells[i] = i;
        }
        return LevelEqualizer.equalize(field, cells, WaterLevelField.CELLS);
    }

    /** 当前是否已达到均衡（任意两格水位差 &le; 1）。 */
    public boolean isEqualized() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (field.capacity(i) <= 0) {
                continue;
            }
            final int lv = field.level(i);
            min = Math.min(min, lv);
            max = Math.max(max, lv);
        }
        return min == Integer.MAX_VALUE || max - min <= 1;
    }
}
