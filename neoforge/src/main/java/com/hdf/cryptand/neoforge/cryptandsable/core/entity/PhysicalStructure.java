package com.hdf.cryptand.neoforge.cryptandsable.core.entity;

import com.hdf.cryptand.neoforge.cryptandsable.core.environment.EnvId;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.GlobalEnvTable;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.MediaProperties;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.MediaType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 物理结构 Entity 数据包（PhysicalStructure）—— 一个物理结构的所有数据（ECS 参考，2026-09-01 V2）。
 *
 * <p><b>ECS 参考（非严格）：</b>一个结构 = 一个 Entity；Component 全绑定到数据包
 * （Transform/Shape/Material/Light/Mapping/State/EnvId）。连续内存/数组布局，
 * 批量 JNI 可提交。
 *
 * <p><b>数据分类：</b>
 * <ul>
 *   <li><b>持久数据</b>：随结构存储/存档（Shape/Material/Light/Mapping/State 基础键）；</li>
 *   <li><b>缓存数据</b>：EnvId 解析结果/环境参数派生量/网格碰撞加速结构 —— 进入世界或
 *       环境变更后需更新（{@link #refreshCaches()}），退出世界不保存（加速运算）。</li>
 * </ul>
 *
 * <p>本类为纯数据（不持有 Level/BE 引用）；物理运算全部由引擎执行。
 *
 * @param runtimeId 核心 runtime id（结构唯一）
 * @param sceneId   所属 scene（独立 scene → 可并行进引擎）
 */
public final class PhysicalStructure {

    private final int runtimeId;
    private final int sceneId;
    private final Vector3d position = new Vector3d();
    private final Quaterniond orientation = new Quaterniond();
    private final Vector3d linearVelocity = new Vector3d();
    private final Vector3d angularVelocity = new Vector3d();

    private final Vector3d size = new Vector3d(1, 1, 1);      // 半尺寸（Shape）
    private double mass = 1.0;
    private final Matrix3d inertiaTensor = new Matrix3d();
    private double friction = 0.5;
    private double restitution = 0.1;
    private double damping = 0.05;

    // ===== Light 光照（完整内容） =====
    private int skyLight = 15;
    private int blockLight = 0;

    // ===== Mapping 映射 =====
    private final Vector3d anchor = new Vector3d();           // 投影锚（世界坐标）
    private final Vector3d[] voxelGrid = new Vector3d[0];      // 体素网格（MVP：占位）

    // ===== State 状态 =====
    private boolean sleeping = false;
    private boolean removed = false;
    private boolean broken = false;

    // ===== EnvId 环境引用 =====
    private int envId = EnvId.DEFAULT;

    // ===== 缓存数据（进入世界/环境变更时刷新；退出不保存） =====
    private final EnvironmentCache envCache = new EnvironmentCache();

    public PhysicalStructure(int runtimeId, int sceneId) {
        this.runtimeId = runtimeId;
        this.sceneId = sceneId;
    }

    public int runtimeId() { return this.runtimeId; }
    public int sceneId() { return this.sceneId; }

    // ===== Transform =====
    public Vector3d position() { return this.position; }
    public Quaterniond orientation() { return this.orientation; }
    public Vector3d linearVelocity() { return this.linearVelocity; }
    public Vector3d angularVelocity() { return this.angularVelocity; }

    // ===== Shape =====
    public Vector3d size() { return this.size; }
    public double mass() { return this.mass; }
    public Matrix3d inertiaTensor() { return this.inertiaTensor; }
    public void setMassProperties(double mass, Matrix3d inertia) {
        this.mass = mass;
        if (inertia != null) this.inertiaTensor.set(inertia);
    }

    // ===== Material =====
    public double friction() { return this.friction; }
    public void setFriction(double friction) { this.friction = friction; }
    public double restitution() { return this.restitution; }
    public void setRestitution(double restitution) { this.restitution = restitution; }
    public double damping() { return this.damping; }
    public void setDamping(double damping) { this.damping = damping; }

    // ===== Light =====
    public int skyLight() { return this.skyLight; }
    public void setSkyLight(int skyLight) { this.skyLight = skyLight; }
    public int blockLight() { return this.blockLight; }
    public void setBlockLight(int blockLight) { this.blockLight = blockLight; }

    // ===== Mapping =====
    public Vector3d anchor() { return this.anchor; }
    public Vector3d[] voxelGrid() { return this.voxelGrid; }

    // ===== State =====
    public boolean isSleeping() { return this.sleeping; }
    public void setSleeping(boolean sleeping) { this.sleeping = sleeping; }
    public boolean isRemoved() { return this.removed; }
    public void setRemoved(boolean removed) { this.removed = removed; }
    public boolean isBroken() { return this.broken; }
    public void setBroken(boolean broken) { this.broken = broken; }

    // ===== EnvId =====
    public int envId() { return this.envId; }
    public void setEnvId(int envId) { this.envId = envId; }

    /** 环境缓存（EnvId 解析结果 —— 运行时缓存，不保存）。 */
    public EnvironmentCache envCache() { return this.envCache; }

    /** 刷新环境缓存（进入世界/环境变更时调用；从全局环境表查 EnvId）。 */
    public void refreshCaches() {
        MediaProperties props =
                GlobalEnvTable.instance().get(this.envId);
        this.envCache.update(props, this);
    }

    /** 结构是否激活（未睡眠、未移除、未破坏）。 */
    public boolean isActive() {
        return !this.sleeping && !this.removed && !this.broken;
    }

    @Override
    public String toString() {
        return "PhysicalStructure{runtimeId=" + this.runtimeId + ", sceneId=" + this.sceneId
                + ", envId=" + this.envId + ", sleeping=" + this.sleeping + "}";
    }

    /**
     * 环境缓存（EnvironmentCache）—— EnvId 解析后的派生量。
     * 进入世界/环境变更时刷新；退出世界丢弃（不持久化）。
     */
    public static final class EnvironmentCache {
        /** 介质密度（读入结构重力/阻力公式）。 */
        private double mediumDensity = 1.225;
        /** 介质类型。 */
        private MediaType mediaType = MediaType.GROUND;
        /** 重力（从环境表读入）。 */
        private final Vector3d gravity = new Vector3d(0, -9.81, 0);
        /** 线性阻力。 */
        private double linearDrag = 0.10;
        /** 平方阻力。 */
        private double quadraticDrag = 0.001;
        /** 环境气压。 */
        private double pressure = 101325.0;
        /** 可呼吸。 */
        private boolean breathable = true;

        void update(@Nullable MediaProperties props,
                    PhysicalStructure body) {
            if (props == null) return;
            this.mediumDensity = props.mediumDensity();
            this.mediaType = props.mediaType();
            this.gravity.set(props.gravity());
            this.linearDrag = props.linearDrag();
            this.quadraticDrag = props.quadraticDrag();
            this.pressure = props.pressure();
            this.breathable = props.breathable();
        }

        public double mediumDensity() { return this.mediumDensity; }
        public MediaType mediaType() { return this.mediaType; }
        public Vector3d gravity() { return this.gravity; }
        public double linearDrag() { return this.linearDrag; }
        public double quadraticDrag() { return this.quadraticDrag; }
        public double pressure() { return this.pressure; }
        public boolean breathable() { return this.breathable; }
    }
}
