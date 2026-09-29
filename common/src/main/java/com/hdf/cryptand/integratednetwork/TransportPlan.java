package com.hdf.cryptand.integratednetwork;

import java.util.List;
import java.util.Map;

/**
 * 解析计划（2026-08-30 用户：解析器计算各输入口消耗多少数量/物品，各输出口
 * 被分配什么物品/数量）。核心据此产出执行表（EXTRACT/WRITE）。
 * <p>
 * 纯虚拟：只含数量映射（接口 id → 数量），物品本体仍在网络缓存/输入端缓存。
 */
public final class TransportPlan {

    /** 输入接口 id → 消耗数量 */
    public final Map<Object, Double> inputConsumes;
    /** 输出接口 id → 分配数量 */
    public final Map<Object, Double> outputAllocs;

    public TransportPlan(Map<Object, Double> inputConsumes,
                         Map<Object, Double> outputAllocs) {
        this.inputConsumes = inputConsumes == null ? Map.of() : Map.copyOf(inputConsumes);
        this.outputAllocs = outputAllocs == null ? Map.of() : Map.copyOf(outputAllocs);
    }

    public static TransportPlan empty() {
        return new TransportPlan(Map.of(), Map.of());
    }

    /** 是否无有效分配（输入消耗或输出分配为空 → 无传输） */
    public boolean isEmpty() {
        return inputConsumes.isEmpty() || outputAllocs.isEmpty();
    }

    @Override
    public String toString() {
        return "TransportPlan{consume=" + inputConsumes + " alloc=" + outputAllocs + "}";
    }
}
