package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.waterphysics.FluidWritePlan;
import com.hdf.cryptand.waterphysics.WaterLevelField;

import java.util.HashMap;
import java.util.Map;

/**
 * 水位 side table（每个 chunk section 一个 {@link WaterLevelField}）。
 *
 * <p>方块状态里不再保存水位；水位只活在这里，世界方块只在跨 0 边界（有水/无水）时才写回。
 * 只由主线程访问（读快照与写回都在主线程），因此内部用普通 HashMap，不做同步。
 *
 * <p>持久化：见 {@link WaterLevelStoreSavedData}（随维度 SavedData 落盘）。
 */
public final class WaterLevelStore {

    private final Map<Long, WaterLevelField> sections = new HashMap<>();

    /** 取（不存在则创建）某个 section 的水位场。 */
    public WaterLevelField getOrCreate(final long sectionKey) {
        return sections.computeIfAbsent(sectionKey, k -> new WaterLevelField());
    }

    /** 取某个 section 的水位场，不存在返回 null。 */
    public WaterLevelField get(final long sectionKey) {
        return sections.get(sectionKey);
    }

    public void put(final long sectionKey, final WaterLevelField field) {
        sections.put(sectionKey, field);
    }

    public void remove(final long sectionKey) {
        sections.remove(sectionKey);
    }

    /**
     * <b>忘掉某一格的水位</b>（外部把世界改了 ⇒ 侧表这一格已经过期）。
     *
     * <p>★ 少了这一步，「在已有水里放水源」不会生效：
     * {@link com.hdf.cryptand.waterphysics.FluidLevels#resolve} 的规则是
     * 「世界有水 &amp;&amp; 侧表非 0 ⇒ 取侧表」，于是新放的水源（世界里是满水 8）会被侧表的旧值
     * （比如一片水位 3 的水）盖住 —— 求解器看到的还是 3，和邻居一样平，一步都不动。
     * 清掉之后侧表这一格归零，resolve 就会取世界的值（外部注入生效）。
     */
    public void forget(final long sectionKey, final int index) {
        final WaterLevelField field = sections.get(sectionKey);
        if (field != null) {
            field.forget(index);          // 值 + present 一起清（否则 resolve 会退回世界的旧投影）
        }
    }

    public int sectionCount() {
        return sections.size();
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public void clear() {
        sections.clear();
    }

    /** 已加载的全部 section 键（存档与统计用）。 */
    public java.util.Set<Long> sectionKeys() {
        return java.util.Collections.unmodifiableSet(sections.keySet());
    }

    // ---------- 坐标换算 ----------

    /** 打包的方块坐标 → section 键。 */
    public static long sectionKeyOf(final long packedBlockPos) {
        return SectionCursor.key(FluidWritePlan.unpackX(packedBlockPos) >> 4,
                FluidWritePlan.unpackY(packedBlockPos) >> 4,
                FluidWritePlan.unpackZ(packedBlockPos) >> 4);
    }

    /** 打包的方块坐标 → section 内线性索引。 */
    public static int localIndexOf(final long packedBlockPos) {
        return SectionCursor.linearIndex(FluidWritePlan.unpackX(packedBlockPos) & 15,
                FluidWritePlan.unpackY(packedBlockPos) & 15,
                FluidWritePlan.unpackZ(packedBlockPos) & 15);
    }
}
