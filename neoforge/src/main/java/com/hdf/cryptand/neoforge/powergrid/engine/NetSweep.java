package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.List;

/**
 * ===== 一轮 round 的网络遍历协议（组合件）=====
 *
 * <p>后台 round 把结果打包成 `List<Object[]>`，每个元素形如
 * `{ctx, result, freq, batchHash?}`。四个 `compute*Unified` 过去各自写一遍
 * 「跳过 null / 跳过不足 3 元素 / 拆包 ctx·result·freq」的样板，规则一旦分叉
 * 就会出现"N 个入口对残缺项的处理不一致"。
 *
 * <p>本件把这层协议收成**一处**：调用方只提供"拿到三元组之后做什么"，
 * 用组合（回调）替代四份重复的遍历骨架。
 *
 * <p>注意：回调里的 `return` 等价于原来的 `continue`（跳过本条网络）。
 */
public final class NetSweep {

    private NetSweep() {}

    /** 单条网络快照的消费者。 */
    @FunctionalInterface
    interface Visitor {
        void visit(PhasorNetworkContext ctx, SolveResult res, double freq, long batchHash);
    }

    /** 遍历一轮 round 的全部网络快照（统一残缺项过滤与三元组解包）。 */
    static void sweep(List<Object[]> nets, Visitor v) {
        if (nets == null || v == null) return;
        for (Object[] o : nets) {
            if (o == null || o.length < 3) continue;
            PhasorNetworkContext ctx = (PhasorNetworkContext) o[0];
            if (ctx == null) continue;
            SolveResult res = (SolveResult) o[1];
            double freq = (Double) o[2];
            long batchHash = (o.length >= 4 && o[3] instanceof Long l) ? l : 0;
            v.visit(ctx, res, freq, batchHash);
        }
    }
}
