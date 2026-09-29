package com.hdf.cryptand.neoforge.cryptandsable.core.worker;

import com.hdf.cryptand.neoforge.cryptandsable.core.entity.PhysicalStructure;
import com.hdf.cryptand.neoforge.cryptandsable.core.entity.StructureRegistry;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.EnvironmentSnapshot;
import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sable 模拟上下文（SableSimulationContext）。
 *
 * <p>worker 线程独占的物理状态集合：<b>核心为单一权威源</b>（数据归属矩阵）。
 * 持有：
 * <ul>
 *   <li>刚体注册表：runtimeId → 刚体模拟状态</li>
 *   <li>物理结构 Entity 数据包注册表（ECS 参考 V2：StructureRegistry）</li>
 *   <li>scene 组织：结构 → sceneId（多结构独立 scene，支持并行）</li>
 *   <li>质量/质心结果缓存（核心自算，C5）</li>
 *   <li>环境快照：主线程产出的最终环境属性（重力/介质密度），worker 消费</li>
 * </ul>
 *
 * <p>只应被 {@link SableWorker} 线程访问/修改；主线程绝不相识此结构
 * （环境快照例外——经 volatile 只读更新）。
 */
public final class SableSimulationContext {

    /** 刚体状态注册表（runtimeId → 状态）。 */
    private final Map<Integer, RigidBodyState> rigidBodies = new ConcurrentHashMap<>();

    /** 物理结构 Entity 数据包注册表（V2 ECS 参考；StructureRegistry）。 */
    private final StructureRegistry structures = new StructureRegistry();

    /** 结构所在 scene 映射（runtimeId → sceneId）。 */
    private final Map<Integer, Integer> bodyScene = new ConcurrentHashMap<>();

    /** 已分配的 scene 计数（原子单调递增分配器；主线程分配、worker 消费）。 */
    private final java.util.concurrent.atomic.AtomicInteger nextSceneId = new java.util.concurrent.atomic.AtomicInteger(1);

    /** 已分配的 runtimeId 计数（原子；主线程分配、worker 消费——防多装配器共享 runtimeId）。 */
    private final java.util.concurrent.atomic.AtomicInteger nextRuntimeId = new java.util.concurrent.atomic.AtomicInteger(1);

    /** 环境快照（volatile：主线程写，worker 读）。 */
    private volatile EnvironmentSnapshot environment = EnvironmentSnapshot.DEFAULT;

    public int allocateRuntimeId() {
        return nextRuntimeId.getAndIncrement();
    }

    public int allocateSceneId() {
        return nextSceneId.getAndIncrement();
    }

    /** 主线程更新环境快照（世界加载/心跳时）。 */
    public void setEnvironment(EnvironmentSnapshot snap) {
        if (snap != null) {
            this.environment = snap;
        }
    }

    /** worker 读取当前环境快照。 */
    public EnvironmentSnapshot environment() {
        return environment;
    }

    public void registerBody(int runtimeId, int sceneId, RigidBodyState state) {
        rigidBodies.put(runtimeId, state);
        bodyScene.put(runtimeId, sceneId);
    }

    public void removeBody(int runtimeId) {
        rigidBodies.remove(runtimeId);
        bodyScene.remove(runtimeId);
    }

    public RigidBodyState getBody(int runtimeId) {
        return rigidBodies.get(runtimeId);
    }

    public Map<Integer, RigidBodyState> bodies() {
        return rigidBodies;
    }

    /** 物理结构 Entity 数据包注册表（V2）。 */
    public StructureRegistry structures() {
        return this.structures;
    }

    /** 登记物理结构（含环境缓存刷新）。 */
    public void registerStructure(PhysicalStructure structure) {
        if (structure == null) return;
        structure.refreshCaches();
        this.structures.register(structure);
    }

    public int sceneOf(int runtimeId) {
        Integer s = bodyScene.get(runtimeId);
        return s != null ? s : -1;
    }

    public void clear() {
        rigidBodies.clear();
        bodyScene.clear();
        nextSceneId.set(1);
        nextRuntimeId.set(1);
        environment = EnvironmentSnapshot.DEFAULT;
    }
}