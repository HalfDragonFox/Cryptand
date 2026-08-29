package com.hdf.cryptand.circuitsimulation.netgraph;

import java.util.Objects;

/**
 * 导线端点（2026-08-13 用户架构：自管拓扑，IE+CEE 混合）。
 * <p>
 * 世界导线拓扑中一个稳定的端点。语义 = 方块位置 + 端子索引
 * （等价于 IE 的 ConnectionPoint(BlockPos,index) / CEE 的 InWorldNode(id,pos) /
 * 本 mod TerminalRegistry 的 "pos#term" 键）。
 * <p>
 * 【纯算法组件】：不依赖 Minecraft/PowerGrid——key 是通用字符串
 * （"pos#term" 或任意稳定标识），由适配层决定如何从世界生成。
 * 核心（Cryptand-Core）只认这个稳定键，替换实体导线后端点语义不变。
 */
public final class WirePoint implements Comparable<WirePoint> {

    /** 稳定端点键（如 "B(1,2,3)#0"，由适配层生成；不可变） */
    public final String key;

    public WirePoint(String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("WirePoint key must not be empty");
        this.key = key;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WirePoint p)) return false;
        return key.equals(p.key);
    }

    @Override public int hashCode() { return key.hashCode(); }

    @Override public int compareTo(WirePoint o) { return key.compareTo(o.key); }

    @Override public String toString() { return "WP(" + key + ")"; }
}
