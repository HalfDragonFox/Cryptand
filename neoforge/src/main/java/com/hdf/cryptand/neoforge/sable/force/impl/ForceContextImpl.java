/**
 * ===== 力采集上下文实现（框架内部，2026-09-14） =====
 *
 * <p>每个结构每 tick 构建一次：质心/质量/重力/位姿变换由框架反射预算好，供所有力源复用。
 * 采集结果累积在 {@link #samples()}（由调用方取走后丢弃本实例）。
 *
 * <p>线程：仅服务端主线程使用；临时 JOML 向量为实例字段（非静态），不跨结构共享。
 */
package com.hdf.cryptand.neoforge.sable.force.impl;

import com.hdf.cryptand.neoforge.sable.force.api.ForceContext;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSample;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

public final class ForceContextImpl implements ForceContext {

    private final Object subLevel;
    private final Object pose;
    private final int key;
    private final boolean detailed;
    private final boolean nearPlayer;
    private final double mass;
    private final double[] gravity;
    private final double[] comLocal;
    private final double[] comWorld;
    private final List<ForceSample> samples = new ArrayList<>();

    /** 采集上限（防单结构爆炸；由 collector 设置，emit 超出后丢弃）。 */
    private final int maxSamples;
    private int emitted = 0;

    public ForceContextImpl(final Object subLevel, final boolean detailed, final boolean nearPlayer,
                            final int maxSamples) {
        this.subLevel = subLevel;
        this.pose = ForceReflect.logicalPose(subLevel);
        this.key = ForceReflect.structureKey(subLevel);
        this.detailed = detailed;
        this.nearPlayer = nearPlayer;
        this.maxSamples = maxSamples;
        this.mass = ForceReflect.mass(subLevel);

        final Vector3dc com = ForceReflect.centerOfMass(subLevel);
        this.comLocal = com == null ? null : new double[]{com.x(), com.y(), com.z()};
        this.comWorld = com == null ? null : ForceReflect.toWorld(this.pose, com.x(), com.y(), com.z());

        // 重力由 collector 注入（需要 Level）；默认主世界重力
        this.gravity = new double[]{0.0, -9.81, 0.0};
    }

    public void setGravity(final double[] g) {
        if (g != null && g.length == 3) {
            this.gravity[0] = g[0];
            this.gravity[1] = g[1];
            this.gravity[2] = g[2];
        }
    }

    public List<ForceSample> samples() {
        return this.samples;
    }

    /** 实际产出条数（用于诊断/限流）。 */
    public int emittedCount() {
        return this.emitted;
    }

    // ===== ForceContext =====

    @Override
    public Object subLevel() {
        return this.subLevel;
    }

    @Override
    public int runtimeId() {
        return this.key;
    }

    @Override
    public boolean detailed() {
        return this.detailed;
    }

    @Override
    public double[] centerOfMass() {
        return this.comWorld == null ? null : this.comWorld.clone();
    }

    /** 质心（结构局部坐标；供力源自己变换时复用）。 */
    public double[] centerOfMassLocal() {
        return this.comLocal == null ? null : this.comLocal.clone();
    }

    @Override
    public double mass() {
        return this.mass;
    }

    @Override
    public double[] gravity() {
        return this.gravity.clone();
    }

    @Override
    public double[] toWorld(final double lx, final double ly, final double lz) {
        return ForceReflect.toWorld(this.pose, lx, ly, lz);
    }

    @Override
    public double[] toWorldDir(final double lx, final double ly, final double lz) {
        return ForceReflect.toWorldDir(this.pose, lx, ly, lz);
    }

    @Override
    public boolean nearPlayer() {
        return this.nearPlayer;
    }

    @Override
    public void emit(final ResourceLocation id, final ForceSample.Kind kind,
                     final double cx, final double cy, final double cz,
                     final double fx, final double fy, final double fz) {
        if (this.emitted >= this.maxSamples) return;
        this.emitted++;
        this.samples.add(new ForceSample(id, kind, cx, cy, cz, fx, fy, fz));
    }
}
