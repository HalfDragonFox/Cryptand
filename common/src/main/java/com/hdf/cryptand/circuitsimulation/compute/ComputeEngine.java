package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 计算引擎抽象。
 * 实现：本地 CPU 线程池、C++ 原生（GPU/OpenBLAS）、远程集群。
 */
public interface ComputeEngine extends AutoCloseable {
    /** 引擎类型（CPU / GPU / CLUSTER） */
    ComputeEngineType type();

    /** 引擎提供的线程质量等级 */
    ThreadQuality quality();

    /** 该引擎是否可执行此任务（类型掩码 + 最低质量 + 自身能力） */
    boolean canExecute(ComputeTask task);

    /** 同步执行任务并返回结果。 */
    ComputeResult execute(ComputeTask task);

    @Override
    void close();
}
