/**
 * ===== 网络破坏计划（2026-08-22 用户架构：主线程检测类产出，异步核心执行） =====
 *
 * 设备方块被破坏 / 导线被剪切后，主线程 {@link NetworkDestructionDetector} 检测
 * 该网络的【单端子/无端子悬空导线】，整合需要破坏的内容为一份计划发给异步
 * 核心执行删除。计划包含：
 *   - {@link #removedBlocks}  被破坏/删除的 BE 方块（端子全部删除）
 *   - {@link #danglingPoints} 悬空端子（无导线连接 / 端点方块已消失）
 *   - {@link #danglingEdges}  悬空导线（一端或两端悬空）
 *
 * 异步线程（CryptandNetOpExecutor.executeDestroy）按此计划执行 removeEdge /
 * removePoint，随后拆合（SPLIT_MERGE）+ 重建（REBUILD）。
 *
 * 线程：主线程构建（只读图 + 发消息），异步线程消费（写图）。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class NetworkDestructionPlan {

    /** 被破坏/删除的 BE 方块（端子全部删除） */
    public final List<BlockPos> removedBlocks;
    /** 悬空端子（无导线连接 / 端点方块已消失；要删除的 WirePoint） */
    public final List<WirePoint> danglingPoints;
    /** 悬空导线（一端或两端悬空；要删除的 WireEdge） */
    public final List<WireEdge> danglingEdges;

    public NetworkDestructionPlan(List<BlockPos> removedBlocks,
                                  List<WirePoint> danglingPoints,
                                  List<WireEdge> danglingEdges) {
        this.removedBlocks = removedBlocks == null ? List.of() : removedBlocks;
        this.danglingPoints = danglingPoints == null ? List.of() : danglingPoints;
        this.danglingEdges = danglingEdges == null ? List.of() : danglingEdges;
    }

    /** 是否有待破坏内容 */
    public boolean isEmpty() {
        return removedBlocks.isEmpty() && danglingPoints.isEmpty() && danglingEdges.isEmpty();
    }

    @Override
    public String toString() {
        return "NetworkDestructionPlan{blocks=" + removedBlocks.size()
                + ", points=" + danglingPoints.size() + ", edges=" + danglingEdges.size() + "}";
    }
}
