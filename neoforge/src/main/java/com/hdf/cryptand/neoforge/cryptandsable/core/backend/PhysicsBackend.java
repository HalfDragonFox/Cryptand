package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

/**
 * 物理后端抽象（Contextual Backend）—— 核心与底层引擎之间的边界。
 *
 * <p>核心模拟既可走【纯 Java 仿真】（MVP 自收敛模拟器），也可切到
 * 【Rapier3D native】（f64/f32 双实现）。本接口统一两种后端，使核心内部
 * 不依赖具体实现（对齐 C2：后端允许 f64 与 f32 两种；C3 核心只算）。
 *
 * <p>所有方法均为"主线程/worker 传入计算数据 → 后端执行"风格；
 * native 实现会把多体操作批量打进同一次 JNI 调用（C12）。
 */
public interface PhysicsBackend {

    /** 后端精度标识。 */
    enum Precision { F32, F64, SIMULATION }

    /** 当前后端精度。 */
    Precision precision();

    /** 初始化后端（创建 scene / native handle）。 */
    void initialize(double gravityX, double gravityY, double gravityZ, double universalDrag);

    /** 释放资源。 */
    void dispose();

    /**
     * 批量步进（C12：同 scene 多体一次步进）。
     *
     * <p>所有传入数组对齐关系由实现内部解释；MVP 纯 Java 仿真忽略 native handle。
     */
    void stepBatch(long sceneHandle, int substeps, double dt);

    /**
     * 把一批刚体位姿/速度上传到后端（批量发送）。
     *
     * @param sceneHandle     场景
     * @param runtimeIds      刚体 id 数组
     * @param pose            位姿数组 [idCount*8]: px,py,pz,qx,qy,qz,qw
     * @param velocities      速度数组 [idCount*6]: vx,vy,vz,wx,wy,wz
     */
    void uploadPoseBatch(long sceneHandle, int[] runtimeIds, double[] pose, double[] velocities);

    /**
     * 底板一批 voxel 烘焙数据上传（批量发送）。
     * data 按 section 展开 [idCount * 4096]。
     */
    void bakeChunkBatch(long sceneHandle, int[] sectionPositions, int[] data);
}