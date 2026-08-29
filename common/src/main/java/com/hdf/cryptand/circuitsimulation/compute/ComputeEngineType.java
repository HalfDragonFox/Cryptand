package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 计算引擎类型（位掩码，可组合：CLUSTER 可包含 CPU+GPU）。
 */
public enum ComputeEngineType {
    CPU(1),
    GPU(2),
    CLUSTER(4);

    public final int bit;

    ComputeEngineType(int bit) { this.bit = bit; }

    /** 掩码是否包含某类型 */
    public static boolean contains(int mask, ComputeEngineType t) {
        return (mask & t.bit) != 0;
    }

    /** 该引擎能否执行给定掩码（引擎类型必须命中掩码中的某位） */
    public boolean matches(int engineMask) {
        return contains(engineMask, this);
    }
}
