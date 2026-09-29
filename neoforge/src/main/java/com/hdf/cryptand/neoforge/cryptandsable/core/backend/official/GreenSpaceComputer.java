/**
 * ===== 绿色空间计算器（GreenSpaceComputer）——【弃置区 2026-09-05 一世界一空间】 =====
 *
 * ★ 2026-09-05 用户定案【一世界一物理空间】：整个世界只有一个物理空间，不再动态
 *   合并/拆分 → 绿框重叠聚类（GreenSpaceComputer）已放弃置区，无调用方（保留备查）。
 *
 * 原用途（2026-09-05 多空间版）：ECS 风格——本类【只计算】，不关心具体是哪个 MC
 * 对象/实例——只接收传入的核心数据类（PhysicsWorldData）与其绿色全局表
 * （GreenFootprintTable），按绿框足迹重叠做连通分量聚类，产出"哪些结构应共享同一
 * 物理空间"的决策（簇列表）。决策由引擎应用（成员迁移、native 场景创建/销毁、
 * dirty 标记）。
 *
 * 原计算时机：主线程每 tick 后处理（rebalanceSpaces 调用）；数据源 = 每次结构计算
 * 完成后提交到全局表的足迹（引擎空间任务 POST_PROCESS commit）。
 *
 * 纯函数：无副作用于 native/Level；可在任意线程执行（输入表 ConcurrentHashMap 线程安全）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

public final class GreenSpaceComputer {

    private GreenSpaceComputer() {
    }

    /** 一个连通分量：应共享同一物理空间的结构 uuid 组（保持提交序；首个 = 种子）。 */
    public record Cluster(java.util.List<java.util.UUID> structureUuids) {
    }

    /**
     * ECS 纯计算：读核心数据类的绿色全局表 → 按绿框足迹 AABB 重叠分连通分量。
     * 重叠 → 同簇（同物理空间）；不重叠 → 分簇（拆分）。不修改任何表/native。
     *
     * @param world 核心数据类（每世界一个）
     * @return 簇列表（每个 = 应共享同一物理空间的结构的 uuid 列表；遍历序 = 足迹提交序）
     */
    public static java.util.List<Cluster> cluster(final PhysicsWorldData world) {
        final java.util.List<Cluster> out = new java.util.ArrayList<>();
        if (world == null) return out;
        final java.util.List<GreenFootprintTable.GreenFootprint> fps =
                new java.util.ArrayList<>(world.greenTable.all());
        final int n = fps.size();
        if (n == 0) return out;
        // union-find：绿框重叠 → 同一连通分量
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            final GreenFootprintTable.GreenFootprint a = fps.get(i);
            for (int j = i + 1; j < n; j++) {
                if (a.overlaps(fps.get(j))) union(parent, i, j);
            }
        }
        // 按根分组（LinkedHashMap 保持遍历序 → 组内第一个 = 种子）
        final java.util.Map<Integer, java.util.List<Integer>> groups =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new java.util.ArrayList<>()).add(i);
        }
        for (final java.util.List<Integer> group : groups.values()) {
            final java.util.List<java.util.UUID> uuids = new java.util.ArrayList<>(group.size());
            for (final int idx : group) uuids.add(fps.get(idx).structureUuid());
            out.add(new Cluster(uuids));
        }
        return out;
    }

    private static int find(final int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(final int[] p, int a, int b) {
        final int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }
}
