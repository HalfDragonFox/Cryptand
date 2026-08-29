package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.integratednetwork.TransportChange.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 核心传输执行器（2026-08-26 集成网络核心）：传输执行器的内置默认实现。
 * <p>
 * 在纯虚拟传输图（{@link TransportGraph}）上运行通用物流/无线电流动：
 *   - 拥有每个传输网的图 + 仿真器（{@link TransportSimulator}），按网隔离；
 *   - 应用拓扑变更（{@link TransportChange}）到图（增/删节点、边）；
 *   - 执行流动推进（{@link #executeTick}），缓存最近帧结果并经
 *     {@link #setListener} 在核心线程发布；
 *   - 帧结果缓存 {@code result(key)} 供主线程直接查询（消费式 |
 *     事件队列式，见监听器）。
 * <p>
 * 本类只操作纯虚拟图，绝不碰 MC 对象（核心铁律）。后续平台接入管道/无线电
 * 时可替换为平台实现（{@link TransportExecutor}），或对本类图做世界映射。
 */
public final class CoreTransportExecutor implements TransportExecutor {

    /** 传输网键 → 传输图（纯虚拟） */
    private final ConcurrentMap<Object, TransportGraph> graphs = new ConcurrentHashMap<>();
    /** 传输网键 → 仿真器 */
    private final ConcurrentMap<Object, TransportSimulator> sims = new ConcurrentHashMap<>();
    /** 传输网键 → 最近帧结果（volatile 桥：核心线程写、任意线程读） */
    private final ConcurrentMap<Object, TransportResult> results = new ConcurrentHashMap<>();

    /** 帧监听器（核心线程回调；平台层收集事件，主线程随后应用副作用） */
    private volatile TransportListener listener;

    /** 累计统计（跨步长期累计：送达/丢弃/移动量），executeTick 内累加 */
    private final ConcurrentMap<Object, Tally> tallies = new ConcurrentHashMap<>();

    /** 设置帧监听器（回调在核心线程执行，勿做 MC 主线程调用） */
    public void setListener(TransportListener l) {
        this.listener = l;
    }

    /** 当前监听器（诊断） */
    public TransportListener listener() {
        return listener;
    }

    // ===== 图访问 =====

    /** 注册/替换已建好的传输图（键不存在自动建） */
    public void register(Object key, TransportGraph g) {
        if (key == null || g == null) return;
        graphs.put(key, g);
        sims.put(key, new TransportSimulator(g));
        results.remove(key);
        tallies.remove(key);
    }

    /** 注销传输图（清空图与结果） */
    public TransportGraph unregister(Object key) {
        if (key == null) return null;
        sims.remove(key);
        results.remove(key);
        tallies.remove(key);
        return graphs.remove(key);
    }

    /** 查询传输图（未注册返回 null） */
    public TransportGraph graph(Object key) {
        return graphs.get(key);
    }

    /** 查询仿真器（未注册返回 null） */
    public TransportSimulator simulator(Object key) {
        return sims.get(key);
    }

    /** 最近一次帧结果（未运行返回 null） */
    public TransportResult result(Object key) {
        return results.get(key);
    }

    /** 累计送达负载数（跨步长期累计；未运行 0） */
    public long delivered(Object key) {
        Tally t = tallies.get(key);
        return t == null ? 0 : t.delivered.get();
    }

    /** 累计丢弃负载数（跨步长期累计；未运行 0） */
    public long dropped(Object key) {
        Tally t = tallies.get(key);
        return t == null ? 0 : t.dropped.get();
    }

    /** 累计移入在途的总量（跨步长期累计；未运行 0） */
    public double moved(Object key) {
        Tally t = tallies.get(key);
        return t == null ? 0 : t.moved();
    }

    /** 已注册传输图数量 */
    public int graphCount() {
        return graphs.size();
    }

    /** 清空全部图/仿真器/结果（世界卸载/实例释放） */
    public void clear() {
        graphs.clear();
        sims.clear();
        results.clear();
        tallies.clear();
    }

    // ===== TransportExecutor 实现 =====

    @Override
    public boolean executeDestroy(Object key, Object data) {
        TransportGraph g = graph(key);
        if (g == null) return false;
        boolean any = false;
        for (TransportChange c : changes(data)) {
            if (c == null) continue;
            if (c.kind == TransportChange.Kind.REMOVE_NODE) {
                g.removeNode(c.id);
                any = true;
            } else if (c.kind == TransportChange.Kind.REMOVE_EDGE) {
                g.removeEdge(((Number) c.id).longValue());
                any = true;
            }
        }
        return any;
    }

    @Override
    public boolean executeTopology(Object key, Object data) {
        TransportGraph g = graphs.computeIfAbsent(key, k -> new TransportGraph());
        TransportSimulator s = sims.computeIfAbsent(key, k -> new TransportSimulator(g));
        boolean any = false;
        for (TransportChange c : changes(data)) {
            if (c == null) continue;
            any |= applyChange(g, c);
        }
        // 路由在下一步自动重算（routesDirty 由图内部置位）；可显式 submitRoute
        return any;
    }

    @Override
    public void executeRoute(Object key, Object data) {
        TransportSimulator s = sims.get(key);
        if (s != null) s.computeRoutes();
    }

    @Override
    public void executeTick(Object key, Object data) {
        TransportSimulator s = sims.get(key);
        if (s == null) return;
        int frames = data instanceof Integer i ? Math.max(1, i) : 1;
        TransportListener l = listener;
        Tally tally = tallies.computeIfAbsent(key, k -> new Tally());
        TransportResult last = null;
        for (int i = 0; i < frames; i++) {
            last = s.step();
            results.put(key, last);      // 缓存帧结果（任意线程可读）
            tally.add(last);             // 累计送达/丢弃/移动量
            if (l != null) {
                try {
                    l.onFrame(key, last); // 核心线程发布
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ===== 内部 =====

    /** 把单条拓扑变更应用到图 */
    private boolean applyChange(TransportGraph g, TransportChange c) {
        switch (c.kind) {
            case ADD_NODE -> {
                double[] p = c.pos;
                return g.addNode(new TransportNode(c.id, c.type,
                        p == null ? 0 : p[0], p == null ? 0 : p[1], p == null ? 0 : p[2],
                        c.capacity));
            }
            case REMOVE_NODE -> {
                g.removeNode(c.id);
                return true;
            }
            case ADD_EDGE -> {
                return g.addEdge(new TransportEdge(g.nextEdgeId(), c.a, c.b, c.type,
                        c.throughput, c.latencyTicks, c.loss, c.cost));
            }
            case REMOVE_EDGE -> {
                g.removeEdge(((Number) c.id).longValue());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 载荷归一：单条 TransportChange 或 List<TransportChange> → 迭代 */
    private static Iterable<TransportChange> changes(Object data) {
        List<TransportChange> out = new ArrayList<>();
        if (data instanceof TransportChange c) {
            out.add(c);
        } else if (data instanceof Iterable<?> it) {
            for (Object o : it) if (o instanceof TransportChange c) out.add(c);
        }
        return out;
    }

    /** 已注册图（诊断） */
    public Map<Object, TransportGraph> graphs() {
        return graphs;
    }

    /** 累计统计（诊断） */
    public Map<Object, Tally> tallies() {
        return tallies;
    }

    /** 跨步累计统计（核心线程写、任意线程读原子量） */
    public static final class Tally {
        final java.util.concurrent.atomic.AtomicLong delivered =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong dropped =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong movedBits =
                new java.util.concurrent.atomic.AtomicLong();

        void add(TransportResult r) {
            delivered.addAndGet(r.delivered());
            dropped.addAndGet(r.dropped());
            movedBits.accumulateAndGet(Double.doubleToLongBits(r.movedUnits),
                    (a, b) -> Double.doubleToLongBits(
                            Double.longBitsToDouble(a) + Double.longBitsToDouble(b)));
        }

        double moved() {
            return Double.longBitsToDouble(movedBits.get());
        }

        @Override
        public String toString() {
            return "Tally{delivered=" + delivered.get() + ", dropped=" + dropped.get()
                    + ", moved=" + moved() + "}";
        }
    }

    @Override
    public String toString() {
        return "CoreTransportExecutor{graphs=" + graphs.size() + ", results=" + results.size() + "}";
    }
}