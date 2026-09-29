package com.hdf.cryptand.neoforge.cryptandsable.core.simulator;

import com.hdf.cryptand.neoforge.cryptandsable.api.physics.BodyParams;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 统一物理体状态（RigidBodyState，历史名保留）。
 *
 * <p>核心持有（数据归属矩阵：pose/vel/质量/惯量全在核心）。主线程只有只读快照。
 * 物理体不区分具体结构（对齐 C8）。【同一套结构】表达刚体/柔体：
 * <ul>
 *   <li>刚体 = 刚核（四元数+惯量）+ 0 粒子 → 姿态积分（6DOF）</li>
 *   <li>柔体 = 粒子集 + 约束网络（可带刚核做锚点）；stiffness=1 即刚性柔体</li>
 * </ul>
 * 仅由 {@link BodyParams} 参数分隔，积分入口统一（{@link #integrate}）。
 *
 * <p>高频小步进自收敛（C14）：半隐式欧拉，靠更小 dt 多步推进自然收敛。
 */
public final class RigidBodyState {
    public final int runtimeId;
    public final int sceneId;

    /** 物理体参数（刚/柔分隔）。 */
    private BodyParams params = BodyParams.rigid(1.0);

    // ===== 刚核（RIGID：姿态积分） =====
    // 位姿（世界）
    public final Vector3d position = new Vector3d();
    public final Quaterniond orientation = new Quaterniond();
    // 速度（世界）
    public final Vector3d linearVelocity = new Vector3d();
    public final Vector3d angularVelocity = new Vector3d();
    // 受力累积（每 step 清零）
    private final Vector3d forceAccum = new Vector3d();
    private final Vector3d torqueAccum = new Vector3d();
    // 质量属性（核心自算，C5）
    private double mass = 1.0;
    private double invMass = 1.0;
    private final Matrix3d invInertiaWorld = new Matrix3d();

    // ===== 柔体（SOFT：粒子+约束） =====
    private Vector3d[] softParticles;
    private Vector3d[] softVelocities;
    private boolean softInitialized = false;

    // 辅助惰性对象
    private final Vector3d tmpVec = new Vector3d();

    // 控制
    private boolean sleeping = false;
    private boolean removed = false;

    /** ★ 地表支撑（2026-09-01）：所在列地形最高实心方块顶的 Y（结构下方地面）。
     *  Double.NaN = 未知/无（体 → 落体不贴地）；由主线程按体位置采样填。 */
    private double groundY = Double.NaN;

    public RigidBodyState(int runtimeId, int sceneId) {
        this.runtimeId = runtimeId;
        this.sceneId = sceneId;
        orientation.identity();
    }

    /** 设置物理体参数（刚/柔模式）。柔体时惰性初始化粒子布局。 */
    public void setParams(BodyParams p) {
        this.params = p;
        if (p.isSoft()) {
            initSoftParticles();
        }
    }

    public BodyParams params() { return params; }
    public boolean isSoft() { return params.isSoft(); }
    public Vector3d[] softParticles() { return softParticles; }
    public Vector3d[] softVelocities() { return softVelocities; }
    public boolean softInitialized() { return softInitialized; }

    private void initSoftParticles() {
        int n = params.particleCount;
        if (n <= 0) { softInitialized = false; return; }
        softParticles = new Vector3d[n];
        softVelocities = new Vector3d[n];
        double step = params.restLength / Math.max(1, n - 1);
        double offset = -(n - 1) * 0.5 * step;
        for (int i = 0; i < n; i++) {
            softParticles[i] = new Vector3d(position).add(offset + i * step, 0, 0);
            softVelocities[i] = new Vector3d();
        }
        softInitialized = true;
    }

    public void setMassProperties(double newMass, Matrix3d localInertia) {
        this.mass = newMass;
        this.invMass = newMass > 0.0 ? 1.0 / newMass : 0.0;
        // 世界惯性 = R * I_local * R^T 的逆 = R * I_local^-1 * R^T
        Matrix3d localInv = new Matrix3d(localInertia).invert();
        invInertiaWorld.zero();
        invInertiaWorld.set(localInv).rotate(orientation);
    }

    public void applyForce(Vector3dc force) {
        forceAccum.add(force);
    }

    public void applyForceAtWorld(Vector3dc point, Vector3dc force) {
        forceAccum.add(force);
        // τ = r × F（r = point - position）
        tmpVec.set(point).sub(position);
        torqueAccum.add(tmpVec.cross(force, new Vector3d()));
    }

    public void applyImpulse(Vector3dc impulse) {
        linearVelocity.add(impulse.x() * invMass, impulse.y() * invMass, impulse.z() * invMass);
    }

    public void applyTorque(Vector3dc torque) {
        torqueAccum.add(torque);
    }

    /**
     * 统一积分入口（刚/柔同一套，由 params 分隔）。
     *
     * <p>刚体：半隐式欧拉姿态积分（a=F/m → v → x；α=I⁻¹τ → ω → q）。
     * 柔体：粒子速度积分 + 位置约束投影（distance/rope/spring，stiffness 缩放投影量）。
     *
     * @param dt 小步时长 [s]（通常 1/20/substeps 或更高频）
     */
    public void integrate(double dt, Vector3dc gravity) {
        if (sleeping || removed) return;

        if (isSoft()) {
            integrateSoft(dt, gravity);
        } else {
            integrateRigid(dt, gravity);
        }
    }

    /** 刚体姿态积分（半隐式欧拉）。 */
    private void integrateRigid(double dt, Vector3dc gravity) {
        // 线速度
        double ax = forceAccum.x * invMass + gravity.x();
        double ay = forceAccum.y * invMass + gravity.y();
        double az = forceAccum.z * invMass + gravity.z();
        linearVelocity.add(ax * dt, ay * dt, az * dt);

        // 角速度（简化：α = I^-1 * τ）
        if (torqueAccum.lengthSquared() > 1e-12) {
            tmpVec.set(torqueAccum).mul(invInertiaWorld, new Vector3d());
            angularVelocity.add(tmpVec.mul(dt));
        }

        // 位置
        position.fma(dt, linearVelocity);

        // 朝向（q += 0.5 * Ω * q * dt）
        Quaterniond qd = new Quaterniond();
        qd.set(0, angularVelocity.x, angularVelocity.y, angularVelocity.z)
                .mul(orientation)
                .mul(0.5 * dt);
        orientation.add(qd);
        orientation.normalize();

        // 清力
        forceAccum.zero();
        torqueAccum.zero();
    }

    /**
     * 柔体积分：粒子含重力自由积分 + 距离约束投影（PBD 式，迭代 2 次）。
     * stiffness=1 → 完全刚性（等价刚体形态）；stiffness→0 → 软绳。
     */
    private void integrateSoft(double dt, Vector3dc gravity) {
        if (!softInitialized) return;
        int n = softParticles.length;
        double pmass = mass / Math.max(1, n);
        double invPm = n > 0 ? 1.0 / (mass / n) : 0.0;

        // 1) 自由积分（外力=重力；柔体不读 forceAccum——由约束主导）
        for (int i = 0; i < n; i++) {
            Vector3d v = softVelocities[i];
            v.add(gravity.x() * dt, gravity.y() * dt, gravity.z() * dt);
            softParticles[i].fma(dt, v);
        }

        // 2) 位置约束投影（最近邻 distance/rope/spring）
        double stiffness = params.constraintStiffness;
        int type = params.constraintType;
        int iterations = 2;
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < n - 1; i++) {
                Vector3d a = softParticles[i];
                Vector3d b = softParticles[i + 1];
                Vector3d delta = new Vector3d(b).sub(a);
                double dist = delta.length();
                double rest = params.restLength / Math.max(1, n - 1);
                if (dist < 1e-9) continue;

                double correction = 0.0;
                if (type == BodyParams.CONSTRAINT_ROPE) {
                    // 只拉不推：dist>rest 才收紧
                    if (dist > rest) correction = (dist - rest) / dist * stiffness;
                } else if (type == BodyParams.CONSTRAINT_RIGID_LINK) {
                    correction = (dist - rest) / dist * stiffness;
                } else {
                    // spring（双向，可用 restLength 目标）
                    correction = (dist - rest) / dist * stiffness;
                }

                if (correction == 0.0) continue;
                delta.mul(0.5 * correction);
                a.add(delta);
                b.sub(delta);
            }
        }

        // 3) 合成体姿（柔体的"位置" = 粒子质心，供快照用）
        position.zero();
        for (Vector3d p : softParticles) position.add(p);
        position.mul(1.0 / n);
        // 线速度 = 质心速度（简化）
        linearVelocity.zero();
        for (int i = 0; i < n; i++) {
            linearVelocity.add(softVelocities[i]);
        }
        linearVelocity.mul(1.0 / n);

        forceAccum.zero();
        torqueAccum.zero();
    }

    public double mass() {
        return mass;
    }

    public double invMass() {
        return invMass;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public void setSleeping(boolean s) {
        this.sleeping = s;
    }

    /** 地表支撑（所在列地形最高实心方块顶 Y；NaN=无/未知）。 */
    public double groundY() {
        return groundY;
    }

    public void setGroundY(double y) {
        this.groundY = y;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void markRemoved() {
        this.removed = true;
    }
}