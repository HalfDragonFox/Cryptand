package com.hdf.cryptand.integratednetwork;

import java.util.List;

/**
 * 传输执行表（2026-08-30 集成网络核心）：核心【模拟操作完成】后，向主线程
 * 发送的 IO 执行指令表。主线程收到后按表对对应容器【直接输入/输出】——
 * <ul>
 *   <li>{@link #inputs}  = 输入端条目：要从这些容器【移除】的内容；</li>
 *   <li>{@link #outputs} = 输出端条目：要向这些容器【写入】的内容。</li>
 * </ul>
 * 每个条目 = 容器键 + 数量 + 内容类型 +（可选）负载引用 +（可选）到达帧。
 * <p>
 * 核心只产出建议表并缓存（{@link IntegratedNetworkCore#executionTable}），
 * 绝不直接执行 IO（铁律：核心不写 Level/BE）；表为内存态，不存档。
 *
 * <p><b>延迟交付（2026-08-30 用户：时间到了才从缓存发到目的地）</b>：
 * 每条输出 Entry 的 {@link Entry#arriveAt} = 结算步 + 传输距离×段耗时。
 * {@link #dueAt(long)} 按当前步过滤——未到帧的条目暂留核心，时间到才发布，
 * 主线程才能拿到并写目的地。
 */
public final class TransferExecutionTable {

    /** 执行条目：一个容器的一次移动（输入端移除 / 输出端写入） */
    public static final class Entry {

        /** 容器键（平台层映射：如 BlockPos 编码） */
        public final Object containerKey;
        /** 数量（移动单位：物品个数 / mB / FE 等） */
        public final double amount;
        /** 内容类型 */
        public final TransferType type;
        /** 负载引用（可 null） */
        public final Object data;
        /** 到达帧（绝对步；0 = 立即到；&gt;0 = 该帧才可交付） */
        public final long arriveAt;

        public Entry(Object containerKey, double amount, TransferType type, Object data) {
            this(containerKey, amount, type, data, 0);
        }

        public Entry(Object containerKey, double amount, TransferType type, Object data,
                     long arriveAt) {
            this.containerKey = containerKey;
            this.amount = Math.max(0.0, amount);
            this.type = type == null ? TransferType.GENERIC : type;
            this.data = data;
            this.arriveAt = Math.max(0, arriveAt);
        }

        @Override
        public String toString() {
            return "Entry{" + containerKey + " x" + amount + " " + type
                    + (arriveAt > 0 ? " @" + arriveAt : "") + "}";
        }
    }

    /** 结算步号（模拟完成的绝对步） */
    public final long step;
    /** 输入端条目（从这些容器移除） */
    public final List<Entry> inputs;
    /** 输出端条目（向这些容器写入） */
    public final List<Entry> outputs;
    /** 本表所有输出的最晚到达帧（0 = 全部立即） */
    public final long dueFrame;

    public TransferExecutionTable(long step, List<Entry> inputs, List<Entry> outputs) {
        this.step = step;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        long due = 0;
        for (Entry e : this.outputs) due = Math.max(due, e.arriveAt);
        this.dueFrame = due;
    }

    /** 是否空表（无 IO 需求） */
    public boolean isEmpty() {
        return inputs.isEmpty() && outputs.isEmpty();
    }

    /** 按当前步过滤：返回本表中【全部已到期】的输出条目（未到期略过）。
     *  2026-08-30 延迟交付：时间没到的输出不返回（核心仍持有，到帧后发布）。 */
    public List<Entry> dueOutputs(long currentStep) {
        java.util.ArrayList<Entry> due = new java.util.ArrayList<>();
        for (Entry e : outputs) {
            if (e.arriveAt <= currentStep) due.add(e);
        }
        return due;
    }

    @Override
    public String toString() {
        return "TransferExecutionTable{step=" + step + " in=" + inputs.size()
                + " out=" + outputs.size() + " due=" + dueFrame + "}";
    }
}