package com.hdf.cryptand.neoforge.cryptandsable.api.model;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 核心自有 3D 位姿（CryptandPose3d）—— 完全独立于官方 sable 的类型。
 *
 * <p>替代官方 {@code dev.ryanhcode.sable.companion.math.Pose3d/Pose3dc}：
 * 位置 + 朝向（单位四元数）+ 绕点 + 缩放。核心物理体初始位姿/位姿跟随用。
 * JOML 自带类型，无官方依赖。
 */
public final class CryptandPose3d {

    private final Vector3d position;
    private final Quaterniond orientation;
    private final Vector3d rotationPoint;
    private final Vector3d scale;

    public CryptandPose3d(final Vector3d position, final Quaterniond orientation,
                          final Vector3d rotationPoint, final Vector3d scale) {
        this.position = position;
        this.orientation = orientation;
        this.rotationPoint = rotationPoint;
        this.scale = scale;
    }

    /** 恒等位姿（原点、无旋转、绕点在原点、缩放 1）。 */
    public CryptandPose3d() {
        this.position = new Vector3d();
        this.orientation = new Quaterniond();
        this.rotationPoint = new Vector3d();
        this.scale = new Vector3d(1.0);
    }

    public CryptandPose3d(final CryptandPose3d pose) {
        this.position = new Vector3d(pose.position);
        this.orientation = new Quaterniond(pose.orientation);
        this.rotationPoint = new Vector3d(pose.rotationPoint);
        this.scale = new Vector3d(pose.scale);
    }

    public CryptandPose3d set(final CryptandPose3d pose) {
        this.position.set(pose.position);
        this.orientation.set(pose.orientation);
        this.rotationPoint.set(pose.rotationPoint);
        this.scale.set(pose.scale);
        return this;
    }

    public Vector3d position() {
        return this.position;
    }

    public Quaterniond orientation() {
        return this.orientation;
    }

    public Vector3d rotationPoint() {
        return this.rotationPoint;
    }

    public Vector3d scale() {
        return this.scale;
    }

    /** 归一化朝向（写回本对象），确保四元数单位化。 */
    public CryptandPose3d normalizeOrientation() {
        this.orientation.normalize();
        return this;
    }
}