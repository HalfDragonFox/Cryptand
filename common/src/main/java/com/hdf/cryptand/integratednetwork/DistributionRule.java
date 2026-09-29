package com.hdf.cryptand.integratednetwork;

/**
 * 传输分配规则（2026-08-29 集成网络核心：传输指令的分配策略）。
 * <p>
 * 主线程组表（输入→输出→内容→规则）时指定；核心按规则把内容分配到输出，
 * 仿 Pipez 的分布模式（轮询/最近/最远/随机）但以数学方式一次性分配。
 */
public enum DistributionRule {

    /** 均分：总量按输出数量平均分配（仿 Pipez 优化器"数学均分"） */
    EQUALIZE,

    /** 顺序：按输出列表顺序依次填满 */
    ORDERED,

    /** 轮询：逐个输出循环分配 */
    ROUND_ROBIN,

    /** 最近优先：优先距离最近的输出 */
    NEAREST,

    /** 最远优先：优先距离最远的输出 */
    FURTHEST,

    /** 随机：随机选一个输出（全部给它） */
    RANDOM,

    /** 最快优先：优先速率最高的输出 */
    FASTEST
}