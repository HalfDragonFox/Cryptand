package com.hdf.cryptand.circuitsimulation.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 原生（C++）引擎 —— 当前为占位/桩实现。
 *
 * 目标：把 ComputeTask.toByteArray() 的字节流通过 JNI / JNA / 共享内存 发给 C++，
 * C++ 端反序列化后用 OpenBLAS / CUDA / 集群求解，再把结果回传。
 *
 * 本类目前只做 ①导出任务字节流 ②模拟调用 ③抛出未实现异常，
 * 供上层 ComputeScheduler 走通“任务→引擎→结果”的整条链路。
 */
public class NativeComputeEngine implements ComputeEngine {
    private final String nativeId;   // 如 "GPU-0" / "cluster-1"
    private final ComputeEngineType type;
    private final ThreadQuality quality;

    public NativeComputeEngine(String nativeId, ComputeEngineType type, ThreadQuality quality) {
        this.nativeId = nativeId;
        this.type = type;
        this.quality = quality;
    }

    @Override
    public ComputeEngineType type() { return type; }

    @Override
    public ThreadQuality quality() { return quality; }

    @Override
    public boolean canExecute(ComputeTask task) {
        return task.minQuality.level <= quality.level && type.matches(task.engineMask);
    }

    @Override
    public ComputeResult execute(ComputeTask task) {
        byte[] payload = task.toByteArray();
        // TODO(JNI): nativeSolve(payload) → 返回 voltages / converged / iterations / solveNanos
        // 这里预留与 C++ 的二进制协议：
        //   请求 = ComputeTask.toByteArray()（见 ComputeTask）
        //   响应 = int32 converged(0/1), int32 iterations, int64 solveNanos,
        //          int32 nodeCount, double[nodeCount] voltages
        throw new UnsupportedOperationException(
                "NativeComputeEngine '" + nativeId + "' not linked to C++ yet. "
                + "task=" + task.id + ", payload=" + payload.length + " bytes");
    }

    @Override
    public void close() {
        // 释放原生句柄
    }

    /** 结果回包解析（C++ 响应 → ComputeResult）—— 供未来 JNI 回调使用 */
    public static ComputeResult parseNativeResponse(long taskId, byte[] response, SolveModeAlias mode) {
        ByteBuffer b = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        boolean converged = b.getInt() != 0;
        int iterations = b.getInt();
        long solveNanos = b.getLong();
        int nodeCount = b.getInt();
        double[] voltages = new double[nodeCount];
        for (int i = 0; i < nodeCount; i++) voltages[i] = b.getDouble();
        return new ComputeResult(taskId, voltages, converged, iterations, solveNanos,
                mode == SolveModeAlias.COMPLEX_AC ? com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC
                                                  : com.hdf.cryptand.circuitsimulation.solver.SolveMode.REAL_DC,
                "native");
    }

    public enum SolveModeAlias { REAL_DC, COMPLEX_AC }
}
