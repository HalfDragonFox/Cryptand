/**
 * ===== 力采集上下文（框架 API，2026-09-14） =====
 *
 * <p>框架在每个结构、每个 tick 构建一次，交给所有 {@link ForceSource} 复用：
 * 质心 / 质量 / 环境重力 / 局部→世界变换都已由框架反射算好（避免每个源各算一遍）。
 *
 * <p>实现者只读本接口，不需要（也不应该）触碰反射细节或官方 Sable 类型——
 * {@link #subLevel()} 返回 Object 正是为了"零编译期依赖"。
 */
package com.hdf.cryptand.neoforge.sable.force.api;

import net.minecraft.resources.ResourceLocation;

public interface ForceContext {

    /** 官方 ServerSubLevel 实例（Object：sable 为 runtimeOnly，禁止编译期依赖）。 */
    Object subLevel();

    /** 结构 runtimeId（与渲染/位姿快照同一索引）。 */
    int runtimeId();

    /** 当前是否详细模式（true → 力源应额外产出 Kind.POINT 样本）。 */
    boolean detailed();

    /** 质心（世界坐标；未知 → null）。 */
    double[] centerOfMass();

    /** 结构质量 kg（未知 → 0）。 */
    double mass();

    /** 环境重力矢量（世界坐标，m/s²；未知 → (0,-9.81,0)）。 */
    double[] gravity();

    /** 结构局部坐标 → 世界坐标（含位姿平移/旋转）。 */
    double[] toWorld(double lx, double ly, double lz);

    /** 结构局方向 → 世界方向（仅旋转）。 */
    double[] toWorldDir(double lx, double ly, double lz);

    /** 该结构是否处于"玩家可见范围"（框架已做距离剔除；力源一般无需再判）。 */
    boolean nearPlayer();

    /**
     * 产出一条力样本。
     *
     * @param id   力标识（建议与官方 ForceGroups 注册名对齐，如 {@code sable:lift}）
     * @param kind 粒度（简化用 RESULTANT，详细用 POINT）
     */
    void emit(ResourceLocation id, ForceSample.Kind kind,
              double cx, double cy, double cz,
              double fx, double fy, double fz);

    /** 便捷重载：世界坐标中心 + 世界矢量。 */
    default void emit(final ResourceLocation id, final ForceSample.Kind kind,
                      final double[] center, final double[] vector) {
        this.emit(id, kind, center[0], center[1], center[2], vector[0], vector[1], vector[2]);
    }
}
