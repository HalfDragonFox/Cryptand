package com.hdf.cryptand.neoforge.cryptandsable.api.physics.soft;

import org.joml.Vector3dc;

/**
 * 柔体句柄（CryptandSable API 层）。
 *
 * <p>柔体 = 可形变/可随动/非刚性整体的物理体（绳索、悬挂、流体袋、可展结构等）。
 * 核心对柔体同样只做"粒子/约束/积分"，不区分具体结构（对齐 C8）。
 *
 * <p>MVP 实现颗粒度：每个柔体 = 一组质量粒子 + 一组内部约束；本接口提供粒子/约束级操作。
 */
public interface SoftBodyHandle {
    /** 运行时唯一 id。 */
    int runtimeId();

    /** 所属 scene id。 */
    int sceneId();

    /** 粒子数量。 */
    int particleCount();

    /** 读取某粒子当前位置（局部/世界按实现约定）。 */
    Vector3dc particlePosition(int index, Vector3dc dest);

    /** 对某粒子施加力。 */
    void applyParticleForce(int index, Vector3dc force);

    /** 是否被移除。 */
    boolean isRemoved();
}