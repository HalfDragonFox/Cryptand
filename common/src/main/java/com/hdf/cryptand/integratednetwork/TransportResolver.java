package com.hdf.cryptand.integratednetwork;

import java.util.List;

/**
 * 传输解析器（2026-08-30 用户：自定义解析器注册进网络集成核心；网络存储
 * 解析器 id + 输入接口列表(Object 参数) + 输出接口列表(Object 参数) + 管道列表；
 * 每次解析通过调用对应 id 的解析器，解析器实现具体 Object 参数类（如过滤表/
 * 长度等）并在计算时强制转换为对应参数类）。
 * <p>
 * 纯虚拟（零 MC 依赖）：解析器只针对已入缓存的 {@link TransportPayload} 列表与
 * 接口参数做计算，产出 {@link TransportPlan}（各输入消耗 / 各输出分配）。
 */
public interface TransportResolver {

    /** 解析器 id（如 "pipez:item" 通用管道物品解析器；自定义可注册新 id） */
    String id();

    /**
     * 解析一次传输：输入缓存物品列表（含所在输入接口 id）→ 按接口参数（过滤/
     * 白名单等）与分配规则，计算各输入口消耗量与各输出口分配量。
     *
     * @param ctx 解析上下文（输入/输出接口列表及其 Object 参数、管道列表、缓存物品）
     * @return 解析计划（输入消耗表 + 输出分配表）；实现方自行参数强转
     */
    TransportPlan resolve(TransportResolveContext ctx);

    /** 便捷：默认解析器 id 常量 */
    String DEFAULT_ID = "pipez:item";
}
