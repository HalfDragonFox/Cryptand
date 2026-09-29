/**
 * ===== 核心数据类（PhysicsWorldData，2026-09-05 用户定案 · ECS 数据容器） =====
 *
 * 把某【世界】的物理数据统一封装成一个数据包（每世界一个实例 → 世界隔离）：
 *  - {@link #worldUuid}：本数据包唯一标识（UUID 识别）
 *  - {@link #structuredTable}：结构化数据表（结构 uuid → PhysicalizedData）
 *  - {@link #spaceTable}：物理空间表（空间 uuid → PhysicalSpace）
 *  - {@link #greenTable}：绿色碰撞全局表（结构 uuid → 绿框足迹 + 重叠）
 *
 * ECS 风格：计算类（如 {@link com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.GreenSpaceComputer}）
 * 只接收本数据包做【纯计算】，不关心具体是哪个 MC 对象/实例；计算产出的决策由引擎
 * 应用（native 场景/销毁/重建）。核心数据类、物理空间、结构化数据表全部用 UUID 识别。
 *
 * ⚠ 引擎保留 int runtimeId/spaceId 快表（native 运行时快路径）；本类 UUID 表是 ECS
 *   数据权威视图（注册/注销在引擎创建/删除结构、空间时同步维护）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

public final class PhysicsWorldData {

    /** 本世界数据包唯一标识（世界隔离；UUID 识别）。 */
    public final java.util.UUID worldUuid;

    /** 结构化数据表：结构 uuid → 物理化数据（ECS 结构化数据表）。 */
    public final java.util.Map<java.util.UUID, PhysicalizedData> structuredTable =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 物理空间表：空间 uuid → PhysicalSpace（ECS 物理空间表）。 */
    public final java.util.Map<java.util.UUID, OfficialRapierEngine.PhysicalSpace> spaceTable =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** ★ 2026-09-05 【弃置区（一世界一空间：合并拆分放弃置区）】绿色碰撞全局表
     *  （绿框足迹；GreenSpaceComputer 聚类已弃置，不再消费本表）。保留字段兼容。 */
    public final GreenFootprintTable greenTable = new GreenFootprintTable(this);

    public PhysicsWorldData(final java.util.UUID worldUuid) {
        this.worldUuid = worldUuid;
    }

    /** 由结构 runtimeId 反查其 uuid（ECS 表索引；无 → null）。 */
    public java.util.UUID uuidOf(final int runtimeId) {
        for (final PhysicalizedData d : structuredTable.values()) {
            if (d.runtimeId == runtimeId) return d.uuid;
        }
        return null;
    }

    /** 由结构 uuid 反查 runtimeId（native 快路径；无 → 0）。 */
    public int runtimeIdOf(final java.util.UUID su) {
        final PhysicalizedData d = structuredTable.get(su);
        return d != null ? d.runtimeId : 0;
    }

    /** 由空间 uuid 反查 spaceId（native 快路径；无 → 0）。 */
    public int spaceIdOf(final java.util.UUID spaceUuid) {
        final OfficialRapierEngine.PhysicalSpace sp = spaceTable.get(spaceUuid);
        return sp != null ? sp.id : 0;
    }

    /** 当前结构化数据数量。 */
    public int structureCount() {
        return structuredTable.size();
    }

    /** 当前物理空间数量。 */
    public int spaceCount() {
        return spaceTable.size();
    }
}
