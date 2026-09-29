package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

/**
 * 引擎接口（Engine API）—— 计算核心与物理引擎之间的交互边界（2026-09-01 V2 架构）。
 *
 * <p><b>JNI 仅提供交互接口：</b>批量推送（多结构/虚拟体合并打包、排序、分批）全部由
 * 计算核心（{@link com.hdf.cryptand.neoforge.cryptandsable.core.allocator.SableBatchScheduler}）
 * 实现后，经本接口单次调用提交到 native。引擎不感知批处理逻辑。
 *
 * <p><b>引擎动态可更换：</b>通过 {@link EngineManager} 卸载当前 DLL → 加载新 DLL
 * （不同引擎/精度），换引擎不换上游（结构/消费层无感）。
 *
 * <p>所有方法均为"纯数据"调用：输入数组/句柄 —— 无 Level/BE 引用。
 */
public interface EngineApi {

    /** 引擎精度标识。 */
    enum Precision { F64, F32 }

    /** 引擎能力描述（供 Manager 选择/诊断）。 */
    record EngineInfo(String id, Precision precision, String nativeName) {
        @Override
        public String toString() {
            return id + "(" + precision + "/" + nativeName + ")";
        }
    }

    /** 当前引擎信息。 */
    EngineInfo info();

    /** 初始化引擎（创建 scene；子步长/重力由核心计算后传入）。 */
    void initialize(double gravityX, double gravityY, double gravityZ, double universalDrag);

    /**
     * 单次批量步进提交（批处理已由核心切分好；native 一次性执行）。
     *
     * @param sceneHandle 场景句柄（0=默认场景）
     * @param substeps    子步数
     * @param dt          子步长
     */
    void stepBatch(long sceneHandle, int substeps, double dt);

    /**
     * 批量上传位姿/速度（native 值数组；核心已排好序）。
     *
     * @param sceneHandle 场景句柄
     * @param runtimeIds  刚体 id 数组
     * @param pose        位姿数组 [idCount*7]: px,py,pz,qx,qy,qz,qw
     * @param velocities  速度数组 [idCount*6]: vx,vy,vz,wx,wy,wz
     */
    void uploadPoseBatch(long sceneHandle, int[] runtimeIds, double[] pose, double[] velocities);

    /**
     * 批量烘焙体素数据（voxel cookie 上传）。
     *
     * @param sceneHandle     场景句柄
     * @param sectionPositions chunk 位置数组 [idCount*3]
     * @param data            体素数据（按规则展开）
     */
    void bakeChunkBatch(long sceneHandle, int[] sectionPositions, int[] data);

    /**
     * 拉回状态（位姿/接触/破坏列表 —— 全数组）。一次性消费。
     *
     * @return 状态数组（native 定义布局；核心按数据包解释）
     */
    double[] fetchState(long sceneHandle);

    /**
     * 卸载/释放当前引擎（DLL 卸载前调用：保证 native 侧资源释放）。
     */
    void dispose();
}
