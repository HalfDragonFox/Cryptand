/**
 * ===== 绿色碰撞全局表（GreenFootprintTable）——【弃置区 2026-09-05 一世界一空间】 =====
 *
 * ★ 2026-09-05 用户定案【一世界一物理空间】：不再按绿框重叠合并/拆分 → 本表已放弃
 *   置区，无消费方（GreenSpaceComputer 同样弃置；保留结构备查）。
 *
 * 原用途（多空间版）：记录每个物理化结构的【绿色足迹】（= 物理结构扫描区域 =
 * 当前位姿 ± (自身半尺寸+radius)）。作用：物理空间合并/拆分（绿框重叠 → 同空间；
 * 不重叠 → 拆分）的纯数据源。
 *
 * 原提交时机：每次【物理化结构计算完成后】提交更新（引擎空间任务 POST_PROCESS →
 * readSpacePoses 后 commitFootprint）。
 * 原计算时机：每异步线程 tick 后处理（主线程 tick → GreenSpaceComputer.rebalance 读本表
 * 做重叠聚类；结果应用回物理空间表）。
 *
 * 纯数据（零 MC 依赖）；每世界一个实例（PhysicsWorldData.greenTable → 世界隔离）。
 * ConcurrentHashMap 线程安全：引擎（计算线程）写、ECS 计算（主线程 tick）读。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

public final class GreenFootprintTable {

    /** 单结构绿框足迹（不可变快照）。 */
    public record GreenFootprint(
            java.util.UUID structureUuid,
            java.util.UUID spaceUuid,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ
    ) {
        public boolean overlaps(final GreenFootprint o) {
            return minX <= o.maxX && maxX >= o.minX
                    && minY <= o.maxY && maxY >= o.minY
                    && minZ <= o.maxZ && maxZ >= o.minZ;
        }
    }

    private final PhysicsWorldData world;
    private final java.util.Map<java.util.UUID, GreenFootprint> footprints =
            new java.util.concurrent.ConcurrentHashMap<>();

    public GreenFootprintTable(final PhysicsWorldData world) {
        this.world = world;
    }

    /** 所属核心数据类。 */
    public PhysicsWorldData world() {
        return world;
    }

    /** 提交/更新某结构绿框足迹（结构计算完成后调用；幂等覆盖）。 */
    public void commit(final java.util.UUID structureUuid, final java.util.UUID spaceUuid,
                       final int minX, final int minY, final int minZ,
                       final int maxX, final int maxY, final int maxZ) {
        if (structureUuid == null) return;
        footprints.put(structureUuid,
                new GreenFootprint(structureUuid, spaceUuid,
                        minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** 移除结构足迹（结构删除时；幂等）。 */
    public void remove(final java.util.UUID structureUuid) {
        if (structureUuid != null) footprints.remove(structureUuid);
    }

    /** 取某结构足迹（无 → null）。 */
    public GreenFootprint of(final java.util.UUID structureUuid) {
        return structureUuid == null ? null : footprints.get(structureUuid);
    }

    /** 全部足迹（ECS 计算只读迭代）。 */
    public java.util.Collection<GreenFootprint> all() {
        return footprints.values();
    }

    /** 当前足迹数量。 */
    public int size() {
        return footprints.size();
    }

    /** 清空（世界卸载）。 */
    public void clear() {
        footprints.clear();
    }
}
