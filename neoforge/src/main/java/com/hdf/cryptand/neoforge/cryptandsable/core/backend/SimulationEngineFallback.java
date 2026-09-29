package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.SableSimulator;

/**
 * 纯 Java 仿真兜底引擎（SimulationEngineFallback）—— native DLL 加载失败时使用。
 *
 * <p>适配 {@link EngineApi}（JNI 仅交互接口语义）；与 {@link SableSimulator} 保持一致
 * （高频小步进自收敛）。实际步进由核心调 {@link SableSimulator} 完成，本类只作
 * EngineApi 占位（保持引擎接口统一）。
 */
final class SimulationEngineFallback implements EngineApi {

    private final EngineInfo info = new EngineInfo("simulation-java", Precision.F64, "Simulation");

    @Override
    public EngineInfo info() {
        return this.info;
    }

    @Override
    public void initialize(double gravityX, double gravityY, double gravityZ, double universalDrag) {
        // 纯 Java 仿真无需初始化 native
    }

    @Override
    public void stepBatch(long sceneHandle, int substeps, double dt) {
        // 步进由核心 SableSimulator 执行；此处无 native 操作
    }

    @Override
    public void uploadPoseBatch(long sceneHandle, int[] runtimeIds, double[] pose, double[] velocities) {
        // 无 native 上传（核心直接操作 RigidBodyState）
    }

    @Override
    public void bakeChunkBatch(long sceneHandle, int[] sectionPositions, int[] data) {
        // 无 native 烘焙（核心直接操作体素数据）
    }

    @Override
    public double[] fetchState(long sceneHandle) {
        return new double[0];
    }

    @Override
    public void dispose() {
        // 无 native 资源
    }
}
