package com.hdf.cryptand.neoforge.cryptandsable.core.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理结构注册表（StructureRegistry）—— 物理结构数据包集合（2026-09-01 V2）。
 *
 * <p>按 runtimeId 索引物理结构 Entity 数据包；支持批量取数组（供
 * {@code SableBatchScheduler} 切分提交 / {@code EngineApi} 批量接口）。
 * 线程安全（计算核心 worker 读写）。
 */
public final class StructureRegistry {

    private final Map<Integer, PhysicalStructure> structures = new ConcurrentHashMap<>();

    public StructureRegistry() {
    }

    /** 登记（覆盖已存在）。 */
    public void register(PhysicalStructure structure) {
        if (structure != null) {
            this.structures.put(structure.runtimeId(), structure);
        }
    }

    /** 按 runtimeId 取。 */
    public PhysicalStructure get(int runtimeId) {
        return this.structures.get(runtimeId);
    }

    /** 移除。 */
    public PhysicalStructure remove(int runtimeId) {
        return this.structures.remove(runtimeId);
    }

    /** 清空。 */
    public void clear() {
        this.structures.clear();
    }

    /** 全部结构（快照列表）。 */
    public List<PhysicalStructure> all() {
        return new ArrayList<>(this.structures.values());
    }

    /** 全部 runtimeId（批量索引）。 */
    public int[] runtimeIds() {
        return this.structures.keySet().stream()
                .mapToInt(Integer::intValue).toArray();
    }

    /** 全部 sceneId（与 {@link #runtimeIds()} 对齐）。 */
    public int[] sceneIds() {
        return this.structures.values().stream()
                .mapToInt(PhysicalStructure::sceneId).toArray();
    }

    /** 活跃（非 sleep/removed/broken）结构数。 */
    public int activeCount() {
        int n = 0;
        for (PhysicalStructure s : this.structures.values()) {
            if (s.isActive()) n++;
        }
        return n;
    }

    public int size() {
        return this.structures.size();
    }
}
