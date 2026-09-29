package com.hdf.cryptand.integratednetwork;

import java.util.List;

/**
 * 传输请求（2026-08-29 集成网络核心）：一次"输入→输出"传输的多对多 / 点到点指令。
 * <p>
 * 由主线程收集输入容器后组表提交：
 *   【输入方块/容器列表】→【输出方块/容器列表】→【传输内容列表】→【分配规则】
 * 核心按规则分配、算每项到达步（传输时间）、产出 EXTRACT/WRITE 建议事件
 * 并累计待写出表（{@link TransportGraph#addPendingWrite}，内存态、不存档）。
 * <p>
 * 输入/输出元素使用【图节点 id】（核心视角）；平台层负责把 BE/容器映射为节点。
 */
public final class TransportTransferRequest {

    /** 输入节点/容器键列表（图节点 id） */
    public final List<Object> inputs;
    /** 输出节点/容器键列表（图节点 id） */
    public final List<Object> outputs;
    /** 传输内容列表（amount/type/targetId；targetId 应为输出节点） */
    public final List<TransportPayload> items;
    /** 分配规则 */
    public final DistributionRule rule;
    /** 消费值（2026-09 §13：本 tick 允许处理的活跃输入上限；0 = 不限）。
     *  主线程每 tick 下发——异步只消费预算内输入，余量下 tick 轮转覆盖；
     *  主线程卡/积压 → 消费值降 → 异步放缓（背压联动）。 */
    public final int budget;

    public TransportTransferRequest(List<Object> inputs, List<Object> outputs,
                                    List<TransportPayload> items, DistributionRule rule) {
        this(inputs, outputs, items, rule, 0);
    }

    public TransportTransferRequest(List<Object> inputs, List<Object> outputs,
                                    List<TransportPayload> items, DistributionRule rule, int budget) {
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        this.items = items == null ? List.of() : List.copyOf(items);
        this.rule = rule == null ? DistributionRule.EQUALIZE : rule;
        this.budget = Math.max(0, budget);
    }

    /** 是否无传输需求（输入/输出/内容任一为空 → 空箱/空需求） */
    public boolean isEmpty() {
        return inputs.isEmpty() || outputs.isEmpty() || items.isEmpty();
    }

    @Override
    public String toString() {
        return "TransportTransferRequest{in=" + inputs.size() + " out=" + outputs.size()
                + " items=" + items.size() + " rule=" + rule + " budget=" + budget + "}";
    }
}