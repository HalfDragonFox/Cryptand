package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.lang.reflect.Method;

/**
 * Rapier3D native 后端（RapierBackend）—— 通过反射调用官方/自编 Rapier3D（JNI）。
 *
 * <p>官方 sable 是 runtimeOnly 依赖（不进 compile classpath）→ 必须用反射访问
 * {@code dev.ryanhcode.sable.physics.impl.rapier.Rapier3D} 的 static native 方法。
 * scene handle 由 pipeline 经 getSceneHandle 提供。
 *
 * <p>2026-09-01 V2：实现 {@link EngineApi}（JNI 仅交互接口 · 无批处理逻辑 ——
 * 批处理推送由计算核心 {@code SableBatchScheduler} 实现后调用本接口）。
 * single-shot 不再用：所有【批量】语义见 stepBatch/uploadPoseBatch/bakeChunkBatch——
 * MVP 反射逐体调用，批量 JNI（Rust fork：stepBatch/uploadPoseBatch/bakeChunkBatch）
 * 后续在 f64 DLL 内实现后在 batch 方法内复用同签名。
 */
public abstract class RapierBackend implements EngineApi {

    private final Precision precision;
    protected long sceneHandle = 0L;
    private Method mStep;
    private Method mGetPose;
    private Method mSetCenterOfMass;
    private Method mSetLocalBounds;
    private Method mAddChunk;
    private Method mSetKinematicTransform;
    private Method mClearCollisions;
    private Method mCreateSubLevel;
    private Method mRemoveSubLevel;

    protected RapierBackend(Precision precision) {
        this.precision = precision;
    }

    @Override
    public EngineInfo info() {
        return new EngineInfo(
                precision == Precision.F64 ? "rapier-f64" : "rapier-f32",
                precision,
                precision == Precision.F64 ? "Rapier3D-f64" : "Rapier3D-f32");
    }

    /**
     * 初始化后端（创建 native scene）。
     * <p>经反射调 {@code Rapier3D.initialize(gravity...) } 拿到 scene handle 存 {@link #sceneHandle}。
     * 批量 JNI 的 scene 复用此 handle。
     */
    @Override
    public void initialize(double gravityX, double gravityY, double gravityZ, double universalDrag) {
        try {
            Method init = rapierClass().getDeclaredMethod(
                    "initialize", double.class, double.class, double.class, double.class);
            init.setAccessible(true);
            Object h = init.invoke(null, gravityX, gravityY, gravityZ, universalDrag);
            this.sceneHandle = h instanceof Long ? (Long) h : 0L;
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] backend initialize failed: {}", t);
            this.sceneHandle = 0L;
        }
    }

    /** 子类负责先加载对应 DLL（f64=f64 native；f32=官方）。 */
    protected abstract Class<?> rapierClass();

    private Method find(String name, Class<?>... params) {
        try {
            Method m = rapierClass().getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null; // 反射失败 → 该能力不可用
        }
    }

    private void resolveMethods() {
        if (mStep != null) return;
        mStep = find("step", long.class, double.class);
        mGetPose = find("getPose", long.class, int.class, double[].class);
        mSetCenterOfMass = find("setCenterOfMass", long.class, int.class, double.class, double.class, double.class);
        mSetLocalBounds = find("setLocalBounds", long.class, int.class,
                int.class, int.class, int.class, int.class, int.class, int.class);
        mAddChunk = find("addChunk", long.class, int.class, int.class, int.class, int[].class, boolean.class, int.class);
        mClearCollisions = find("clearCollisions", long.class);
        mSetKinematicTransform = find("setKinematicContraptionTransform", long.class,
                int.class, double[].class, double[].class, double[].class);
        mCreateSubLevel = find("createSubLevel", long.class, int.class, double[].class, boolean.class);
        mRemoveSubLevel = find("removeSubLevel", long.class, int.class);
    }

    /** 反射调用 step（单步；批量则循环，见 stepBatch）。 */
    @Override
    public void stepBatch(long sceneHandle, int substeps, double dt) {
        resolveMethods();
        if (mStep == null) return;
        try {
            for (int i = 0; i < substeps; i++) {
                mStep.invoke(null, sceneHandle, dt);
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] backend step failed: {}", t);
        }
    }

    @Override
    public void uploadPoseBatch(long sceneHandle, int[] runtimeIds, double[] pose, double[] velocities) {
        resolveMethods();
        if (mSetKinematicTransform == null || runtimeIds == null) return;
        try {
            int n = runtimeIds.length;
            for (int i = 0; i < n; i++) {
                double[] p = new double[7];
                double[] v = new double[6];
                System.arraycopy(pose, i * 7, p, 0, 7);
                System.arraycopy(velocities, i * 6, v, 0, 6);
                double[] com = new double[] {0, 0, 0}; // 质心偏移 MVP
                mSetKinematicTransform.invoke(null, sceneHandle, runtimeIds[i], com, p, v);
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] backend pose upload failed: {}", t);
        }
    }

    @Override
    public void bakeChunkBatch(long sceneHandle, int[] sectionPositions, int[] data) {
        resolveMethods();
        if (mAddChunk == null) return;
        // MVP 桩：批量 bake 待 fork 提供（Java 侧暂不逐体调 addChunk，语义预留）
    }

    /** 拉回状态（EngineApi: fetchState）—— 复用 clearCollisions（碰撞缓冲一次性消费）。 */
    @Override
    public double[] fetchState(long sceneHandle) {
        return this.clearCollisions(sceneHandle);
    }

    /** 清空碰撞缓冲（postPhysics 用）。 */
    public double[] clearCollisions(long handle) {
        resolveMethods();
        if (mClearCollisions == null) return new double[0];
        try {
            return (double[]) mClearCollisions.invoke(null, handle);
        } catch (Throwable t) {
            return new double[0];
        }
    }

    @Override
    public void dispose() {
        if (sceneHandle != 0L) {
            try {
                java.lang.reflect.Method d = find("dispose", long.class);
                if (d != null) d.invoke(null, sceneHandle);
            } catch (Throwable ignored) {
            }
            sceneHandle = 0L;
        }
    }
}