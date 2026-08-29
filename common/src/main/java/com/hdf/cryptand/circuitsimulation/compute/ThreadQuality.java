package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 线程质量等级 —— 任务携带的“最低线程质量等级”，
 * 调度器据此决定是否可以用单核/多核/GPU/集群执行。
 */
public enum ThreadQuality {
    MINIMAL(0, "仅需单线程，最低要求"),
    SINGLE_CORE(1, "单核"),
    MULTI_CORE(2, "多核"),
    GPU(3, "需要 GPU"),
    CLUSTER(4, "需要集群"),
    MAXIMUM(5, "最高质量");

    public final int level;
    public final String description;

    ThreadQuality(int level, String description) {
        this.level = level;
        this.description = description;
    }

    /** 该质量是否满足最低要求 */
    public boolean atLeast(ThreadQuality min) {
        return this.level >= min.level;
    }

    public static ThreadQuality of(int level) {
        for (ThreadQuality q : values()) if (q.level == level) return q;
        return MINIMAL;
    }
}
