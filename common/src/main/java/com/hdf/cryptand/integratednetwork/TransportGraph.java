package com.hdf.cryptand.integratednetwork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 传输图（2026-08-26 集成网络核心）：物流/无线电网络的【纯虚拟】容器。
 * <p>
 * 只承载拓扑（节点 + 边）+ 流动状态（节点缓冲 + 在途负载）+ 路由缓存，
 * 与 MC 完全解耦（零依赖，核心线程操作）。对应铁律：核心只操作纯虚拟图，
 * 永不写回 Level / BlockEntity。
 * <p>
 * 线程说明：
 * <ul>
 *   <li>【拓扑变更】（add/remove 节点、边）应在核心操作锁内完成——经
 *       {@link IntegratedNetworkCore#submitTopology}/{@code submitDestroy} 走
 *       异步串行队列（本类内部用并发容器保证即使直接改也安全）；</li>
 *   <li>【负载注入】{@link #inject} 是线程安全轻量追加（同步缓冲），主线程
 *       任何时机可调（生产侧写入入口）；</li>
 *   <li>【流动推进】{@link TransportSimulator#step} 在核心线程执行（操作锁内），
 *       步进计数/在途队列只由执行线程读写。</li>
 * </ul>
 */
public final class TransportGraph {

    // ===== 拓扑 =====
    private final ConcurrentMap<Object, TransportNode> nodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, TransportEdge> edges = new ConcurrentHashMap<>();
    /** 邻接表：节点 id → (邻居节点, 边 id) 列表（CopyOnWrite 支持并发读改） */
    private final ConcurrentMap<Object, CopyOnWriteArrayList<Adj>> adjacency =
            new ConcurrentHashMap<>();

    // ===== 流动状态 =====
    /** 节点缓冲：节点 id → 负载缓冲（同步容器，支持主线程注入 + 执行线程消费） */
    private final ConcurrentMap<Object, PayloadBuffer> buffers = new ConcurrentHashMap<>();
    /** 在途负载：边 id → 队列（只由核心执行线程读写；移除边时由操作锁内清理） */
    private final ConcurrentMap<Long, ArrayDeque<InFlight>> inFlight = new ConcurrentHashMap<>();

    // ===== 路由/步进 =====
    /** 路由表：源节点 id → (目标节点 id → 下一跳节点 id)；拓扑变更后失效 */
    private volatile Map<Object, Map<Object, Object>> routes = Map.of();
    /** 路由是否脏（拓扑变更置 true；下次 step 自动重算） */
    private volatile boolean routesDirty = true;
    private final AtomicLong edgeSeq = new AtomicLong();
    private final AtomicLong stepCounter = new AtomicLong();

    // ===== 拓扑：节点 =====

    /** 新增节点（已存在忽略；返回是否新增） */
    public boolean addNode(TransportNode n) {
        if (n == null) return false;
        boolean added = nodes.putIfAbsent(n.id, n) == null;
        if (added) routesDirty = true;
        return added;
    }

    /** 查询节点 */
    public TransportNode node(Object id) {
        return nodes.get(id);
    }

    /** 全部节点（弱一致快照） */
    public Collection<TransportNode> nodes() {
        return nodes.values();
    }

    /** 节点数 */
    public int nodeCount() {
        return nodes.size();
    }

    /** 移除节点：连带其缓冲、邻接，以及挂接的本节点所有边（在途负载一并清理） */
    public void removeNode(Object id) {
        if (nodes.remove(id) == null) return;
        buffers.remove(id);
        adjacency.remove(id);
        for (Long eid : new ArrayList<>(edges.keySet())) {
            TransportEdge e = edges.get(eid);
            if (e != null && (id.equals(e.from) || id.equals(e.to))) removeEdge(eid);
        }
        routesDirty = true;
    }

    // ===== 拓扑：边 =====

    /** 新增边（两个端点节点必须已存在；返回是否新增） */
    public boolean addEdge(TransportEdge e) {
        if (e == null) return false;
        if (!nodes.containsKey(e.from) || !nodes.containsKey(e.to)) return false;
        if (edges.putIfAbsent(e.id, e) != null) return false;
        adjacency.computeIfAbsent(e.from, k -> new CopyOnWriteArrayList<>()).add(new Adj(e.to, e.id));
        adjacency.computeIfAbsent(e.to, k -> new CopyOnWriteArrayList<>()).add(new Adj(e.from, e.id));
        routesDirty = true;
        return true;
    }

    /** 查询边 */
    public TransportEdge edge(long id) {
        return edges.get(id);
    }

    /** 全部边（弱一致快照） */
    public Collection<TransportEdge> edges() {
        return edges.values();
    }

    /** 边数 */
    public int edgeCount() {
        return edges.size();
    }

    /** 移除边：清理两端邻接、在途队列；路由置脏 */
    public void removeEdge(long id) {
        TransportEdge e = edges.remove(id);
        if (e == null) return;
        removeAdj(e.from, e.to, id);
        removeAdj(e.to, e.from, id);
        inFlight.remove(id);
        routesDirty = true;
    }

    private void removeAdj(Object from, Object to, long eid) {
        CopyOnWriteArrayList<Adj> list = adjacency.get(from);
        if (list != null) list.removeIf(a -> a.node.equals(to) && a.edgeId == eid);
    }

    // ===== 拓扑查询 / 路由 =====

    /** 相邻边（弱一致快照） */
    public List<Adj> adjacent(Object nodeId) {
        CopyOnWriteArrayList<Adj> l = adjacency.get(nodeId);
        return l == null ? List.of() : l;
    }

    /** 从 from 到 to 的直接边（不存在返回 null） */
    public TransportEdge edgeToward(Object from, Object to) {
        for (Adj a : adjacent(from)) {
            if (a.node.equals(to)) {
                TransportEdge e = edges.get(a.edgeId);
                if (e != null) return e;
            }
        }
        return null;
    }

    /**
     * 从 nodeId 到 targetId 的下一跳节点（沿已算好的最短路径）；
     * 无表/自身/不可达返回 null（负载滞留于缓冲）。
     */
    public Object nextHop(Object nodeId, Object targetId) {
        if (nodeId == null || targetId == null || nodeId.equals(targetId)) return null;
        Map<Object, Object> table = routes.get(nodeId);
        if (table == null) return null;
        return table.get(targetId);
    }

    // ===== 负载 =====

    /** 下一个边 id（ADD_EDGE 自动分配） */
    public long nextEdgeId() {
        return edgeSeq.incrementAndGet();
    }

    /**
     * 注入负载到源节点缓冲（线程安全：主线程生产入口）。
     * 容量超限返回 false（拒绝，负载不进入网络）。
     *
     * @param sourceNodeId 注入节点 id
     * @param p            负载
     * @return 是否成功入缓冲
     */
    public boolean inject(Object sourceNodeId, TransportPayload p) {
        if (sourceNodeId == null || p == null) return false;
        PayloadBuffer b = buffers.computeIfAbsent(sourceNodeId, k -> new PayloadBuffer());
        TransportNode n = nodes.get(sourceNodeId);
        return b.offer(p, n == null ? 0 : n.capacity);
    }

    /** 节点缓冲（不存在自动建） */
    public PayloadBuffer buffer(Object nodeId) {
        return buffers.computeIfAbsent(nodeId, k -> new PayloadBuffer());
    }

    /** 当前有缓冲的节点 id 集（弱一致快照） */
    public Collection<Object> bufferKeys() {
        return buffers.keySet();
    }

    /** 边的在途队列（可能 null） */
    public ArrayDeque<InFlight> inFlight(long edgeId) {
        return inFlight.get(edgeId);
    }

    /** 把负载投入在途（核心执行线程调用） */
    public void enqueueInFlight(long edgeId, InFlight f) {
        inFlight.computeIfAbsent(edgeId, k -> new ArrayDeque<>()).addLast(f);
    }

    /** 缓冲 + 在途的负载总数 */
    public int pending() {
        int c = 0;
        for (PayloadBuffer b : buffers.values()) c += b.size();
        for (ArrayDeque<InFlight> q : inFlight.values()) c += q.size();
        return c;
    }

    // ===== 步进 / 路由状态 =====

    /** 当前绝对步号（已执行的仿真步） */
    public long step() {
        return stepCounter.get();
    }

    /** 步进计数 +1（仅核心执行线程） */
    public void incrementStep() {
        stepCounter.incrementAndGet();
    }

    /** 路由是否脏（拓扑变更置 true） */
    public boolean routesDirty() {
        return routesDirty;
    }

    /** 标记路由脏（拓扑变更时内部自动调用；外部无需） */
    public void markRoutesDirty() {
        routesDirty = true;
    }

    /** 标记路由干净（重算后调用） */
    public void markRoutesClean() {
        routesDirty = false;
    }

    /** 设置路由表（重算后替换） */
    public void setRoutes(Map<Object, Map<Object, Object>> r) {
        this.routes = r == null ? Map.of() : r;
    }

    /** 当前路由表（诊断） */
    public Map<Object, Map<Object, Object>> routes() {
        return routes;
    }

    // ===== 内部结构 =====

    /** 邻接条目（邻居节点 + 边 id） */
    public static final class Adj {
        public final Object node;
        public final long edgeId;

        Adj(Object node, long edgeId) {
            this.node = node;
            this.edgeId = edgeId;
        }

        @Override
        public String toString() {
            return "Adj{" + node + " via " + edgeId + "}";
        }
    }

    /** 在途条目（负载 + 剩余耗时） */
    public static final class InFlight {
        public final TransportPayload payload;
        public long remain;

        InFlight(TransportPayload payload, long remain) {
            this.payload = payload;
            this.remain = remain;
        }

        @Override
        public String toString() {
            return "InFlight{p=" + payload + ", remain=" + remain + "}";
        }
    }

    /**
     * 节点负载缓冲（同步容器：主线程注入 / 执行线程消费并发安全）。
     * 支持容量上限与优先级排序消费。
     */
    public static final class PayloadBuffer {

        private final ArrayDeque<TransportPayload> q = new ArrayDeque<>();

        /** 入缓冲；超过容量（&gt;0 时）拒绝 */
        synchronized boolean offer(TransportPayload p, double capacity) {
            if (capacity > 0 && unitsLocked() + p.amount > capacity + 1e-9) return false;
            q.addLast(p);
            return true;
        }

        /** 出缓冲（移除指定负载；不存在返回 false） */
        synchronized boolean remove(TransportPayload p) {
            return q.remove(p);
        }

        /** 快照（执行线程排序用；不会与消费互斥造成死锁——排序在锁外进行） */
        synchronized List<TransportPayload> snapshot() {
            return new ArrayList<>(q);
        }

        /** 当前缓冲负载数 */
        synchronized int size() {
            return q.size();
        }

        /** 当前缓冲总量（单位） */
        public synchronized double units() {
            return unitsLocked();
        }

        private double unitsLocked() {
            double s = 0;
            for (TransportPayload p : q) s += p.amount;
            return s;
        }

        /** 清空 */
        synchronized void clear() {
            q.clear();
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "PayloadBuffer{size=" + q.size() + ", units=" + unitsLocked() + "}";
            }
        }
    }

    @Override
    public String toString() {
        return "TransportGraph{nodes=" + nodes.size() + ", edges=" + edges.size()
                + ", step=" + stepCounter.get() + ", pending=" + pending() + "}";
    }
}