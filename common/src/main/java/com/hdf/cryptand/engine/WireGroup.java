package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 导线组（2026-08-30 用户：网络从存储导线改成导线组） =====
 *
 * 导线组 = 一个导线组装器：包含【多段导线的绑定信息】（每段电阻值）+ 一个
 * 【总电阻】+ 一个【温度模型】（共享——统一计算温度后统一赋值）。
 *
 * 拓扑：导线加入网络时 → 计算（连通判断——与组内任一段共享端点）→ 加入现有
 * 组（连通）或新建组；总电阻根据每个导线电阻设置（ΣR）。
 *
 * 求解：每组建 WireComposite（总 R + 共享温度）→ NetworkSolver 统一推进温度。
 */
public final class WireGroup {

    /** 段绑定（每段：两端节点 + 电阻值） */
    public record Segment(int nodeA, int nodeB, double resistance) {}

    private final java.util.List<Segment> segments = new java.util.ArrayList<>();
    private double totalResistance = 0;
    private final ThermalModel thermal;   // 共享温度模型（统一）
    private final double ratedCurrent;    // 额定电流（A）

    public WireGroup(double ratedCurrent, ThermalModel thermal) {
        this.ratedCurrent = ratedCurrent > 0 ? ratedCurrent : 80.0;
        this.thermal = thermal;
    }

    /** 加入段（拓扑连通段）——总电阻按每个导线电阻设置（ΣR） */
    public void addSegment(int nodeA, int nodeB, double resistance) {
        if (nodeA < 0 || nodeB < 0 || nodeA == nodeB || resistance <= 0) return;
        segments.add(new Segment(nodeA, nodeB, resistance));
        totalResistance += resistance; // 总电阻 = Σ 各段电阻
    }

    /** 拓扑连通判断：段（a-b）是否与本组任一段共享端点（连通 → 可加入） */
    public boolean connects(int a, int b) {
        for (Segment s : segments) {
            if (s.nodeA == a || s.nodeA == b || s.nodeB == a || s.nodeB == b) return true;
        }
        return false;
    }

    /** 段数 */
    public int segmentCount() { return segments.size(); }
    /** 全部段（绑定信息） */
    public java.util.List<Segment> segments() { return java.util.Collections.unmodifiableList(segments); }
    /** 总电阻（Ω——Σ 各段） */
    public double totalResistance() { return totalResistance; }
    /** 共享温度模型（统一计算——计算后统一赋值） */
    public ThermalModel thermal() { return thermal; }
    /** 额定电流（A） */
    public double ratedCurrent() { return ratedCurrent; }
}
