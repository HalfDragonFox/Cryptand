package com.hdf.cryptand.circuitsimulation.netgraph;

import java.util.List;

/**
 * 连续导线段（2026-08-14 用户架构：多个连续导线统一处理）。
 * <p>
 * 一段连续导线 = 网络内两个【度≠2 节点】（端点/分叉/设备端子）之间的连续
 * 导线路径。段内多条导线统一处理：
 *   - 电阻相加（Σ resistance）→ 段总电阻（带电阻元件）
 *   - 长度相加（Σ length）→ 段物理长度
 *   - 统一温度模型（段路径签名 key）→ 同段同温、同段统一烧毁
 * <p>
 * 【纯算法组件】：不依赖 Minecraft/PowerGrid。
 */
public final class WireSegment {

    /** 段内连续导线（有序：一端 → 另一端） */
    public final List<WireEdge> edges;
    /** 段一端端点（度≠2 节点） */
    public final WirePoint endA;
    /** 段另一端端点（度≠2 节点） */
    public final WirePoint endB;
    /** Σ 段内导线电阻（Ω） */
    public final double resistance;
    /** Σ 段内导线长度（格） */
    public final double length;
    /** 段路径签名（温度/烧毁统一 key） */
    public final String key;

    public WireSegment(List<WireEdge> edges, WirePoint endA, WirePoint endB,
                       double resistance, double length, String key) {
        this.edges = edges;
        this.endA = endA;
        this.endB = endB;
        this.resistance = resistance;
        this.length = length;
        this.key = key;
    }

    @Override
    public String toString() {
        return "WireSegment{" + endA.key + " -> " + endB.key
                + " edges=" + edges.size() + " R=" + String.format("%.4f", resistance)
                + " L=" + String.format("%.2f", length) + "}";
    }
}
