package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.fluid.ArrayBody;
import com.hdf.cryptand.fluid.FluidRegionAssembly;
import com.hdf.cryptand.waterphysics.WaterLevelField;

/**
 * 转接（新引擎旁路，阶段 2a）：一次采集得到的<b>单个 region</b>快照 → 引擎片（{@link ArrayBody}）。
 *
 * <p><b>只做取数，不做判断</b>：坐标换算 / 种类映射 / 容量 / 边界取舍全在 common 的
 * {@link FluidRegionAssembly}（纯 Java，离线闸门 {@code FluidEngineTest}），本类只是一层
 * 「RegionSnapshot 的哪个字段对应 Source 的哪个方法」的翻译，避免同一套规则在 MC 侧再写一遍。
 *
 * <p><b>数据来源</b>（全部是快照里已有的纯数据，不再碰 Level）：
 * <ul>
 *   <li>片内：{@link RegionSnapshot#field()}（{@code WaterLevelField}）的 kind / level / isSource ——
 *       它的 level 已经是「世界方块 + 侧表」按 {@code FluidLevels.resolve} 合并后的权威值
 *       （采集时就写回了侧表），所以这里不必再读一次 {@code WaterLevelStore}，也就不存在
 *       「两处口径漂移」；</li>
 *   <li>边界：{@link RegionSnapshot#known} / {@link RegionSnapshot#kindAt} / {@link RegionSnapshot#levelAt} ——
 *       region 外一格看 6 个面（{@code faceScanned / faceKind / faceLevel}），未采集的
 *       {@code known} 返回 false ⇒ 按「不可用」处理（水出不去）。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：与 {@code RegionSnapshot} 相同 —— 提交（冻结）之后只由求解线程读，
 * 全是数组读，不触碰 Level。
 */
public final class FluidBodyAdapter implements FluidRegionAssembly.Source {

    private final RegionSnapshot snapshot;
    private final WaterLevelField field;
    private final int baseX;
    private final int baseY;
    private final int baseZ;

    public FluidBodyAdapter(final RegionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不能为 null");
        }
        this.snapshot = snapshot;
        this.field = snapshot.field();
        final long key = snapshot.regionKey();
        this.baseX = SectionCursor.keyX(key) << 4;
        this.baseY = SectionCursor.keyY(key) << 4;
        this.baseZ = SectionCursor.keyZ(key) << 4;
    }

    /** 装配引擎片（片内格 + 一圈可用的边界格）。 */
    public ArrayBody assemble() {
        return FluidRegionAssembly.assemble(this);
    }

    @Override
    public int baseX() {
        return baseX;
    }

    @Override
    public int baseY() {
        return baseY;
    }

    @Override
    public int baseZ() {
        return baseZ;
    }

    @Override
    public boolean insideScanned(final int index) {
        return snapshot.known(baseX + SectionCursor.localX(index),
                baseY + SectionCursor.localY(index), baseZ + SectionCursor.localZ(index));
    }

    @Override
    public int insideKind(final int index) {
        return field.kind(index);
    }

    @Override
    public int insideLevel(final int index) {
        return field.level(index);
    }

    @Override
    public boolean insideSource(final int index) {
        return field.isSource(index);
    }

    @Override
    public boolean outsideScanned(final int x, final int y, final int z) {
        return snapshot.known(x, y, z);
    }

    @Override
    public int outsideKind(final int x, final int y, final int z) {
        return snapshot.kindAt(x, y, z);
    }

    @Override
    public int outsideLevel(final int x, final int y, final int z) {
        return snapshot.levelAt(x, y, z);
    }
}
