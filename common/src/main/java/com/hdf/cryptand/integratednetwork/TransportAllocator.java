package com.hdf.cryptand.integratednetwork;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 图法分配引擎（2026-09 S2：方案 architecture/inc-graph-allocator-scale-design.md §5）。
 * <p>
 * 【输入驱动】每个输入节点按【自己的规则】（均分/最近/最远/轮询/随机/顺序）把输入槽
 * 数量分发给「接受它的输出」——语义对齐 Pipez 原版「每个抽取面独立决策分发」。
 * <ul>
 *   <li>时间值 = 输入→输出 的最短路径总时间（Dijkstra，权重 = 每段传输时间
 *       latencyTicks；无权重时退化为跳数），NEAREST/FURTHEST 按时间值排序；</li>
 *   <li>双槽模型（§4.4）：输入 = 输入槽当前数量（supply，总量守恒 Σ分配 ≤ supply）；
 *       输出 = 输出槽（无限容量，无约束——多片分配零竞争）；</li>
 *   <li>过滤在调用方完成剪枝（候选输出已按黑/白名单剔除），本类只做数量转账；</li>
 *   <li>纯 Java、只读图（ConcurrentHashMap 弱一致），无锁——并行入口由分片任务调用。</li>
 * </ul>
 */
public final class TransportAllocator {

    private final Random rng = new Random();

    /** 输入接口：id、供应量（输入槽当前数量）、物品类型、本输入分发规则、输入侧过滤 */
    public record In(Object id, double supply, Object itemType,
                     DistributionRule rule, Object filterIn) {
    }

    /** 输出接口：id、容量（输出槽无限 = Double.MAX_VALUE）、输出侧过滤参数 */
    public record Out(Object id, double capacity, Object filterOut) {
    }

    /** 分配计划：输出分配量 + 输入消耗量 + 输出执行条目（带 arriveAt） */
    public record Plan(Map<Object, Double> outputAllocs, Map<Object, Double> inputConsumes,
                       List<TransferExecutionTable.Entry> outEntries) {

        public static final Plan EMPTY = new Plan(Map.of(), Map.of(), List.of());

        public boolean isEmpty() {
            return outputAllocs.isEmpty();
        }
    }

    /**
     * 输入 → 各候选输出的最短总时间（Dijkstra；权重 = 边 latencyTicks = 段时间）。
     * 早停：全部候选输出确定后即止。不可达输出不在结果中。
     *
     * @return 输出节点 id → 最短总时间（tick；段时间之和）
     */
    public Map<Object, Integer> forwardTimes(TransportGraph g, Object src, Set<Object> targets) {
        Map<Object, Integer> dist = new java.util.HashMap<>();
        if (src == null || targets == null || targets.isEmpty()) return dist;
        java.util.PriorityQueue<Object[]> pq = new java.util.PriorityQueue<>(
                (x, y) -> Long.compare(((Number) x[0]).longValue(), ((Number) y[0]).longValue()));
        pq.add(new Object[]{0L, src});
        dist.put(src, 0);
        int totalTargets = targets.size();
        int found = 0;
        // ★ 2026-09 S4：早停用【计数 + contains】而非复制目标集——10 万×10 万场景
        //   下复制 HashSet 是 O(N²)（每输入复制 10 万元素）；contains 查询共享引用零复制。
        while (!pq.isEmpty() && found < totalTargets) {
            Object[] top = pq.poll();
            long d = ((Number) top[0]).longValue();
            Object u = top[1];
            int du = dist.getOrDefault(u, Integer.MAX_VALUE);
            if (du < d) continue; // 过期条目（dist 已被更新）
            if (targets.contains(u)) found++;
            for (long eid : g.adjacent(u)) {
                TransportEdge e = g.edge(eid);
                if (e == null) continue;
                Object v = u.equals(e.from) ? e.to : e.from;
                if (v == null) continue;
                int nd = du + Math.max(0, e.latencyTicks); // 段时间 = 边 latencyTicks
                if (nd < dist.getOrDefault(v, Integer.MAX_VALUE)) {
                    dist.put(v, nd);
                    pq.add(new Object[]{(long) nd, v});
                }
            }
        }
        return dist;
    }

    /**
     * 为单个输入做分配（数量转账：输入槽 → 候选输出槽）。
     * 候选输出已由调用方按输出侧过滤/未满剪枝（本类不重复剪枝）。
     *
     * @param g        传输图（只读）
     * @param in       输入（supply = 输入槽当前数量）
     * @param cands    候选输出（已剪枝）
     * @param times    候选输出 id → 时间值（forwardTimes 结果；缺失 = 不可达 → 排除）
     * @param step     当前图步号（轮询/顺序种子、arriveAt 基准）
     * @return 该输入的分配计划（空 = 无可分配候选）
     */
    public Plan allocateForInput(TransportGraph g, In in, List<Out> cands,
                                 Map<Object, Integer> times, long step) {
        double supply = in.supply();
        if (supply <= 0 || cands == null || cands.isEmpty()) return Plan.EMPTY;
        // 剔除不可达候选（times 缺失）
        List<Out> reachable = new ArrayList<>();
        for (Out o : cands) {
            if (times != null && times.containsKey(o.id())) reachable.add(o);
        }
        if (reachable.isEmpty()) return Plan.EMPTY;
        int n = reachable.size();
        Map<Object, Double> alloc = new LinkedHashMap<>();
        DistributionRule rule = in.rule() == null ? DistributionRule.EQUALIZE : in.rule();
        switch (rule) {
            case NEAREST -> alloc.put(minTime(reachable, times).id(), supply);
            case FURTHEST -> alloc.put(maxTime(reachable, times).id(), supply);
            case EQUALIZE -> {
                double per = supply / n;
                for (Out o : reachable) alloc.put(o.id(), per);
            }
            case ROUND_ROBIN -> alloc.put(reachable.get((int) (Math.floorMod(step, n))).id(), supply);
            case ORDERED -> alloc.put(reachable.get(0).id(), supply);
            case RANDOM -> alloc.put(reachable.get(rng.nextInt(n)).id(), supply);
            case FASTEST -> alloc.put(minTime(reachable, times).id(), supply); // 最快 = 时间最小
            default -> {
                double per = supply / n;
                for (Out o : reachable) alloc.put(o.id(), per);
            }
        }
        // 执行条目（输出槽分配 + arriveAt = step + 时间值）
        List<TransferExecutionTable.Entry> entries = new ArrayList<>();
        for (Map.Entry<Object, Double> en : alloc.entrySet()) {
            int t = times.getOrDefault(en.getKey(), 0);
            entries.add(new TransferExecutionTable.Entry(en.getKey(), en.getValue(),
                    TransferType.GENERIC, null, step + Math.max(0, t)));
        }
        Map<Object, Double> consumes = Map.of(in.id(), supply);
        return new Plan(alloc, consumes, entries);
    }

    private static Out minTime(List<Out> cands, Map<Object, Integer> times) {
        return cands.stream().min(Comparator.comparingInt(o -> times.getOrDefault(o.id(), Integer.MAX_VALUE))).orElse(cands.get(0));
    }

    private static Out maxTime(List<Out> cands, Map<Object, Integer> times) {
        return cands.stream().max(Comparator.comparingInt(o -> times.getOrDefault(o.id(), 0))).orElse(cands.get(cands.size() - 1));
    }
}
