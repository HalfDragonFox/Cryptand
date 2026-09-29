package com.hdf.cryptand.integratednetwork;

import java.util.List;
import java.util.Map;

/**
 * 解析上下文（2026-08-30 用户：每次解析通过解析器 id 调用对应解析器）。
 * <p>
 * 网络存储：解析器 id + 输入接口列表（含 Object 类参数）+ 输出接口列表（含
 * Object 类参数）+ 管道列表；本次解析即按这些数据与缓存物品计算。
 * 全部纯虚拟（零 MC 依赖）。
 */
public final class TransportResolveContext {

    /** 解析器 id（如 "pipez:item"） */
    public final String resolverId;
    /** 输入接口 id 列表（含参数对象） */
    public final List<ResolvedInterface> inputs;
    /** 输出接口 id 列表（含参数对象） */
    public final List<ResolvedInterface> outputs;
    /** 管道/结构节点列表（网络结构信息） */
    public final List<Object> pipes;
    /** 缓存物品列表（amount/type/data + 所在输入接口 id） */
    public final List<BufferedItem> buffered;
    /** 分配规则 */
    public final DistributionRule rule;

    public TransportResolveContext(String resolverId,
                                   List<ResolvedInterface> inputs,
                                   List<ResolvedInterface> outputs,
                                   List<Object> pipes,
                                   List<BufferedItem> buffered,
                                   DistributionRule rule) {
        this.resolverId = resolverId == null ? TransportResolver.DEFAULT_ID : resolverId;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        this.pipes = pipes == null ? List.of() : List.copyOf(pipes);
        this.buffered = buffered == null ? List.of() : List.copyOf(buffered);
        this.rule = rule == null ? DistributionRule.EQUALIZE : rule;
    }

    /** 一个接口（id + 平台层 Object 参数，由解析器强转具体参数类） */
    public record ResolvedInterface(Object id, int direction, double rate, Object param) {
    }

    /** 一件已入缓存物品（amount/type/data 由解析器解读；所在输入接口 id） */
    public record BufferedItem(Object inputId, double amount, TransferType type, Object data) {
    }
}
