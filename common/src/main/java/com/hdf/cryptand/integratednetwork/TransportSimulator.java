package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.integratednetwork.TransportEvent.Kind;
import com.hdf.cryptand.integratednetwork.TransportGraph.InFlight;
import com.hdf.cryptand.integratednetwork.TransportGraph.PayloadBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 传输仿真器（2026-08-26 集成网络核心）：在纯虚拟传输图上推进物流/信号流动。
 * <p>
 * 【完全后台异步】——{@link #step()} 在核心线程执行（普通模式虚拟线程），主线程
 * 只投递 TICK 消息，所有吞吐 / 延迟 / 丢包 / 路由计算都在此处完成。这就是"替代
 * 其他 mod 严格主线程卡顿"的关键：管道 / 无线电逻辑每帧批量运算，甚至可慢于
 * 20Hz 节拍，不影响 MC 主线程。
 * <p>
 * 每步（{@link #step()}）流程：
 * <ol>
 *   <li>【交付】在途负载剩余耗时递减，到 0 到达——目标节点 → 送达；中转节点 →
 *       入缓冲（满则丢弃）；</li>
 *   <li>【路由】路由表脏（拓扑变更后）→ 重算全图最短路径（每源 Dijkstra）；</li>
 *   <li>【移动】按优先级消费节点缓冲，压入下一段边（受边吞吐量上限 + 丢包率
 *       约束），生成在途条目。</li>
 * </ol>
 * 结果以帧（{@link TransportResult}）返回并缓存；可经 {@link TransportListener}
 * 在核心线程发布。
 */
public final class TransportSimulator {

    private final TransportGraph g;
    private final Random rng = new Random();

    public TransportSimulator(TransportGraph g) {
        this.g = Objects.requireNonNull(g, "graph");
    }

    /** 传输图（诊断） */
    public TransportGraph graph() {
        return g;
    }

    /** 推进一个仿真步 → 帧结果。仅核心线程调用（对应网络的传输操作锁内）。 */
    public TransportResult step() {
        long t0 = System.nanoTime();
        long tick = g.step() + 1;
        List<TransportEvent> events = new ArrayList<>();
        double moved = 0;

        // ---- 1) 交付：在途负载剩余耗时递减；到 0 到达 ----
        for (TransportEdge e : g.edges()) {
            ArrayDeque<TransportGraph.InFlight> q = g.inFlight(e.id);
            if (q == null || q.isEmpty()) continue;
            Iterator<TransportGraph.InFlight> it = q.iterator();
            while (it.hasNext()) {
                TransportGraph.InFlight f = it.next();
                if (--f.remain > 0) continue;
                it.remove();
                TransportPayload p = f.payload;
                Object nodeId = e.to;
                if (p.targetId == null || nodeId.equals(p.targetId)) {
                    events.add(new TransportEvent(TransportEvent.Kind.DELIVERED, nodeId, p, tick, null));
                } else if (g.inject(nodeId, p)) {
                    events.add(new TransportEvent(TransportEvent.Kind.ARRIVED, nodeId, p, tick, null));
                } else {
                    events.add(new TransportEvent(TransportEvent.Kind.DROPPED, nodeId, p, tick, "capacity"));
                }
            }
        }

        // ---- 2) 路由：按需（懒）——nextHop 首次访问自动失效/重算，无需每帧全对全 Dijkstra ----
        //（只对实际请求的 (源,目标) 对算一次并缓存，避免 O(N²) 路由表内存）

        // ---- 3) 移动：按优先级把缓冲负载压入下一段边（吞吐/丢包约束） ----
        Map<Long, Double> edgeMoved = new HashMap<>();
        for (Object nodeId : new ArrayList<>(g.bufferKeys())) {
            TransportGraph.PayloadBuffer b = g.buffer(nodeId);
            List<TransportPayload> items = b.snapshot();
            if (items.isEmpty()) continue;
            // 稳定消费：优先级降序（同优先级 = 快照顺序 先入先出）
            items.sort(Comparator.comparingInt((TransportPayload p) -> p.priority).reversed());
            for (TransportPayload p : items) {
                Object next = g.nextHop(nodeId, p.targetId);
                if (next == null) continue;              // 无路由 → 滞留缓冲
                TransportEdge e = g.edgeToward(nodeId, next);
                if (e == null || !e.accepts(p.type)) continue;
                double used = edgeMoved.getOrDefault(e.id, 0.0);
                if (e.throughput > 0 && used + p.amount > e.throughput + 1e-9) continue;
                if (!b.remove(p)) continue;              // 可能已被并发注入/消费
                if (e.loss > 0 && rng.nextDouble() < e.loss) {
                    events.add(new TransportEvent(TransportEvent.Kind.DROPPED, nodeId, p, tick, "loss"));
                    continue;
                }
                g.enqueueInFlight(e.id, new TransportGraph.InFlight(p, e.latencyTicks));
                edgeMoved.put(e.id, used + p.amount);
                moved += p.amount;
            }
        }

        g.incrementStep();
        return new TransportResult(tick, events, moved, g.pending(), System.nanoTime() - t0);
    }

    /**
     * 显式刷新路由（按需路由下：清空懒缓存，使下次 {@code nextHop} 重新按需计算；
     * 不推进流动）。拓扑变更后无需显式调用——nextHop 首次访问自动失效重算。
     */
    public void computeRoutes() {
        g.markRoutesDirty();
        g.clearLazyRoutes();
        g.markRoutesClean();
    }
}