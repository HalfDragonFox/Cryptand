package com.hdf.cryptand.neoforge.cryptandsable.api.physics.rigid;

import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/**
 * Rust-引擎无关的刚体句柄（CryptandSable 物理核心 Rust 无关 API 层）。
 *
 * <p>供其他 mod（CEE/Aeronautics/Create/第三方）以"接口不碰实现"方式操作刚体：
 * 施加力/冲量、查询位姿/速度、唤醒/睡眠。实现位于 core 侧，本接口只做数据传递。
 *
 * <p>刚体 = 一个可独立运动/被碰撞/被破坏的物理体（对齐 C8：核心不分具体结构，只有刚/柔体）。
 */
public interface RigidBodyHandle {
    /** 运行时唯一 id（核心分配）。 */
    int runtimeId();

    /** 所属 scene id（多结构独立 scene）。 */
    int sceneId();

    /**
     * 对刚体施加一个力（world 坐标；持续到下次 step 被积分）。
     *
     * @param position 施加点位置（世界坐标）
     * @param force    力 [N]
     */
    void applyForce(Vector3dc position, Vector3dc force);

    /** 施加冲量（瞬时速度变更）。 */
    void applyImpulse(Vector3dc position, Vector3dc impulse);

    /** 施加局部力矩 [Nm]。 */
    void applyTorque(Vector3dc torque);

    /** 读取当前线速度（写入 dest）。 */
    Vector3dc linearVelocity(Vector3dc dest);

    /** 读取当前角速度。 */
    Vector3dc angularVelocity(Vector3dc dest);

    /** 读取当前位置。 */
    Vector3dc position(Vector3dc dest);

    /** 读取当前朝向。 */
    Quaterniondc orientation(Quaterniondc dest);

    /** 唤醒（恢复模拟）。 */
    void wakeUp();

    /** 是否被移除/失效。 */
    boolean isRemoved();
}