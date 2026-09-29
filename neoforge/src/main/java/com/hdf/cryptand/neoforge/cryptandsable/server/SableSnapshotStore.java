package com.hdf.cryptand.neoforge.cryptandsable.server;

import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只读位姿快照镜像（SableSnapshotStore）。
 *
 * <p>主线程 Server 侧持有核心位姿快照的【只读镜像】（数据归属矩阵：核心是权威源，
 * Server 侧只缓存镜像供渲染/查询/破坏应用）。由 ServerBridge 消费出站快照填充本存储。
 *
 * <p>线程约束：本存储只被主线程（Server）读写；worker 绝不触碰（纯镜像）。
 */
public final class SableSnapshotStore {
    private final Map<Integer, SableMessages.PoseSnapshot> snapshots = new ConcurrentHashMap<>();

    /** 更新/覆盖某个体的快照（主线程）。 */
    public void put(SableMessages.PoseSnapshot ps) {
        if (ps != null) snapshots.put(ps.runtimeId(), ps);
    }

    /** 取某个体快照；无则 null。 */
    public SableMessages.PoseSnapshot get(int runtimeId) {
        return snapshots.get(runtimeId);
    }

    /** 取全部快照（不可变访问，供渲染遍历）。 */
    public Iterable<SableMessages.PoseSnapshot> all() {
        return snapshots.values();
    }

    /** 某 id 是否已镜像。 */
    public boolean has(int runtimeId) {
        return snapshots.containsKey(runtimeId);
    }

    /** 清空（世界卸载）。 */
    public void clear() {
        snapshots.clear();
    }

    public int size() {
        return snapshots.size();
    }
}