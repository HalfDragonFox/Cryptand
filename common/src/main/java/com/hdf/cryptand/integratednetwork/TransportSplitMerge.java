package com.hdf.cryptand.integratednetwork;

/**
 * 网络拆合指令（2026-08-29 集成网络核心：类仿真引擎 SPLIT_MERGE，但独立实现）。
 * <p>
 * 单个管道 = 一个网络；管道连接 → {@link #MERGE} 把两个单管网络合并成一个
 * 大局域网；管道断开 → {@link #SPLIT} 将网络按连通分量拆成多个独立子网。
 * 最高优先级，先于一切拓扑/传输操作执行。
 */
public final class TransportSplitMerge {

    /** 拆合模式 */
    public enum Mode {
        /** 合并：把 b 所示网络的图内容并入 a 所示网络，随后注销 b */
        MERGE,
        /** 拆分：按连通分量把 a 所示网络拆成多个子网（注册为 a[0] a[1] ...） */
        SPLIT
    }

    /** 模式 */
    public final Mode mode;
    /** MERGE: 目标网 a（保留合并结果）；SPLIT: 被拆的网 a */
    public final Object a;
    /** MERGE: 并入的网 b（合并后注销）；SPLIT: null */
    public final Object b;

    public TransportSplitMerge(Mode mode, Object a, Object b) {
        this.mode = mode;
        this.a = a;
        this.b = b;
    }

    public static TransportSplitMerge merge(Object a, Object b) {
        return new TransportSplitMerge(Mode.MERGE, a, b);
    }

    public static TransportSplitMerge split(Object a) {
        return new TransportSplitMerge(Mode.SPLIT, a, null);
    }

    @Override
    public String toString() {
        return "TransportSplitMerge{" + mode + " a=" + a + " b=" + b + "}";
    }
}