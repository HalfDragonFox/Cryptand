package com.hdf.cryptand.neoforge.cryptandsable.core.collision;

import org.joml.Vector3d;

/**
 * 一次碰撞接触（两体之间的接触信息，核心计算产出）。
 *
 * <p>从 {@link SableCollisionDetector} 出，被 {@link SableCollisionSolver} 消费
 * （求解响应），并作为碰撞事件（声音/粒子/破坏）发往主线程。纯数据。
 */
public record Contact(
        int bodyA,
        int bodyB,
        int sceneA,
        int sceneB,
        Vector3d pointA,   // A 上接触点（世界）
        Vector3d pointB,   // B 上接触点（世界）
        Vector3d normal,   // B→A 法线（单位）
        double penetration,// 穿透深度 [m]
        double relativeSpeed // 相对速度沿法线 [m/s]
) {
    /** 碰撞是否强烈（供破坏/特效阈值判断）。 */
    public boolean isHard(double thresholdMass) {
        return relativeSpeed > 0.5;
    }
}