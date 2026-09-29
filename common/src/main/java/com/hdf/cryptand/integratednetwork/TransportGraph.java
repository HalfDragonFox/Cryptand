package com.hdf.cryptand.integratednetwork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    // ===== 拓扑（2026-08-29 P1 紧凑化：邻接用扁平 long[]；2026-09 §14.1 整数化：
    //       节点 id → 索引（idToIndex 唯一映射），节点/邻接存索引数组——消除
    //       nodes/adjacency/reverseAdjacency 三个 CHM 的桶+条目开销（String 只保留
    //       在 idToIndex key；遍历/邻接全走 int 索引，10 万节点图结构 ~72MB → ~32MB） =====
    /** 节点 id → 索引（唯一映射） */
    private final ConcurrentMap<Object, Integer> idToIndex = new ConcurrentHashMap<>();
    /** 节点槽（索引 → 节点；删除置 null） */
    private TransportNode[] nodeSlots = new TransportNode[16];
    /** 索引 → 节点 id（与 nodeSlots 对齐；遍历用） */
    private final java.util.List<Object> indexToId = new java.util.ArrayList<>(16);
    /** 已分配索引上限（单调递增；含空洞） */
    private int indexSize = 0;
    /** 当前活动节点数（nodeCount()） */
    private int activeNodeCount = 0;
    /** 回收索引池（删除节点归还，复用避免空洞） */
    private final java.util.ArrayDeque<Integer> freeIdx = new java.util.ArrayDeque<>();
    private final ConcurrentMap<Long, TransportEdge> edges = new ConcurrentHashMap<>();
    /** 邻接表（索引版）：节点 index → 边 id 数组（扁平；度数小复制式增删） */
    private long[][] adjacency = new long[16][];
    /** 反向邻接表（索引版）：节点 index → 入边 id 数组（与 adjacency 对称维护；
     *  反向 BFS/输出驱动从目标沿入边扩散找源） */
    private long[][] reverseAdjacency = new long[16][];
    /** 拓扑版本号（任何 add/remove 节点/边自增；2026-09 S1——分配器版本屏障与
     *  稳定拓扑分配缓存复用用，无锁读检测） */
    private final AtomicLong versionCounter = new AtomicLong();

    /** 拓扑版本号（任何 add/remove 节点/边自增；分片分配版本屏障用，无锁读） */
    public long version() {
        return versionCounter.get();
    }

    /** 分配轮转指针（2026-09 §13 消费值联动：每次截断从指针起取 budget 个活跃输入，
     *  指针前进 → 多 tick 轮转覆盖全量；核心网锁内串行访问） */
    private final AtomicLong allocCursor = new AtomicLong();

    /**
     * 按消费值取本 tick 的活跃输入窗口（从轮转指针起环形取 budget 个，指针前进）。
     * budget<=0 或全量可容纳 → 返回全部并归零指针。
     */
    public List<Object> nextAllocWindow(List<Object> ins, int budget) {
        if (ins == null || ins.isEmpty()) return ins == null ? List.of() : ins;
        int n = ins.size();
        if (budget <= 0 || n <= budget) {
            allocCursor.set(0);
            return ins;
        }
        int start = (int) Math.floorMod(allocCursor.get(), n);
        List<Object> win = new ArrayList<>(budget);
        for (int i = 0; i < budget; i++) win.add(ins.get((start + i) % n));
        allocCursor.set((start + budget) % n);
        return win;
    }

    // ===== 网络能力（单网络单能力，2026-08-29） =====
    private volatile TransferType capability = TransferType.GENERIC;

    // ===== 解析器/参数（2026-08-30 用户：网络存储解析器 id + 管道列表） =====
    /** 解析器 id（null = 未设置 → 传统规则分配；neoforge syncNetworkMeta 设置
     *  "pipez:item" 等时启用解析器）。2026-08-30 修复：默认 null——
     *  之前默认 DEFAULT_ID 导致所有测试/网络被 PipezItemResolver 劫持。 */
    private volatile String resolverId = null;
    private volatile java.util.List<Object> pipes = java.util.List.of();

    public String resolverId() {
        return resolverId;
    }

    public void setResolverId(String id) {
        this.resolverId = id == null ? null : id;
    }

    public java.util.List<Object> pipes() {
        return pipes;
    }

    public void setPipes(java.util.List<Object> pipes) {
        this.pipes = pipes == null ? java.util.List.of() : java.util.List.copyOf(pipes);
    }

    // ===== 接口 / 容器信息（2026-08-29：一条「接口→容器列表」） =====
    private final ConcurrentMap<Object, NetworkInterface> interfaces = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, ContainerInfo> containers = new ConcurrentHashMap<>();
    /** 接口 → 容器 id 数组（省内存用 Object[] 不用 List） */
    private final ConcurrentMap<Object, Object[]> ifaceContainers = new ConcurrentHashMap<>();

    // ===== 传输中内容表（内存态，不存档；P2：primitive 计数省装箱）+ 空箱等待 =====
    private final ObjectDoubleMap pendingWrites = new ObjectDoubleMap();
    private volatile long waitUntilStep = 0;
    private volatile int waitTicks = 0;

    // ===== 流动状态 =====
    /** 节点缓冲：节点 id → 负载缓冲（同步容器，支持主线程注入 + 执行线程消费） */
    private final ConcurrentMap<Object, PayloadBuffer> buffers = new ConcurrentHashMap<>();
    /** 在途负载：边 id → 队列（只由核心执行线程读写；移除边时由操作锁内清理） */
    private final ConcurrentMap<Long, ArrayDeque<InFlight>> inFlight = new ConcurrentHashMap<>();

    // ===== 路由/步进（2026-08-29：按需路由，不预建全对全表，只缓存实际请求过的 (源,目标) 对） =====
    /** 懒路由缓存：源节点 id → (目标 id → 下一跳)；只含被实际请求过的对（避免 O(N²) 内存） */
    private final ConcurrentMap<Object, ConcurrentMap<Object, Object>> lazyRoutes =
            new ConcurrentHashMap<>();
    /** 不可达目标的缓存哨兵（ConcurrentHashMap 禁止 null 值） */
    private static final Object NULL_HOP = new Object();
    /** 路由是否脏（拓扑变更置 true；nextHop 首次访问时懒清空缓存） */
    private volatile boolean routesDirty = true;
    private final AtomicLong edgeSeq = new AtomicLong();
    private final AtomicLong stepCounter = new AtomicLong();

    // ===== 构造 =====

    /** 默认图（能力 GENERIC = 不限类型） */
    public TransportGraph() {
        this(TransferType.GENERIC);
    }

    /** 指定能力的网（单网络单能力；GENERIC 不限） */
    public TransportGraph(TransferType capability) {
        this.capability = capability == null ? TransferType.GENERIC : capability;
    }

    // ===== 拓扑：节点 =====

    /** 新增节点（已存在忽略；返回是否新增）。整数化：分配索引（回收池复用/递增扩容） */
    public boolean addNode(TransportNode n) {
        if (n == null) return false;
        if (!capabilityCompatible(n.type)) return false;
        if (idToIndex.containsKey(n.id)) return false;
        int idx;
        if (!freeIdx.isEmpty()) {
            idx = freeIdx.pop();
        } else {
            idx = indexSize++;
            if (idx >= nodeSlots.length) growSlots();
        }
        idToIndex.put(n.id, idx);
        nodeSlots[idx] = n;
        if (indexToId.size() <= idx) indexToId.add(n.id);
        else indexToId.set(idx, n.id);
        adjacency[idx] = null;
        reverseAdjacency[idx] = null;
        activeNodeCount++;
        routesDirty = true;
        versionCounter.incrementAndGet(); // 2026-09 S1 拓扑版本
        return true;
    }

    /** 节点/邻接槽扩容（翻倍） */
    private void growSlots() {
        int cap = nodeSlots.length * 2;
        nodeSlots = java.util.Arrays.copyOf(nodeSlots, cap);
        adjacency = java.util.Arrays.copyOf(adjacency, cap);
        reverseAdjacency = java.util.Arrays.copyOf(reverseAdjacency, cap);
    }

    /** 查询节点（id → 索引 → 槽） */
    public TransportNode node(Object id) {
        Integer idx = idToIndex.get(id);
        return idx == null ? null : nodeSlots[idx];
    }

    /** 全部节点（弱一致快照：遍历索引槽，跳过空洞） */
    public Collection<TransportNode> nodes() {
        List<TransportNode> out = new ArrayList<>(activeNodeCount);
        for (int i = 0; i < indexSize; i++) {
            TransportNode n = nodeSlots[i];
            if (n != null) out.add(n);
        }
        return out;
    }

    /** 节点数（当前活动数） */
    public int nodeCount() {
        return activeNodeCount;
    }

    /** 移除节点：连带其缓冲、邻接，以及挂接的本节点所有边（在途负载一并清理） */
    public void removeNode(Object id) {
        Integer idx = idToIndex.remove(id);
        if (idx == null) return;
        nodeSlots[idx] = null;
        adjacency[idx] = null;
        reverseAdjacency[idx] = null; // 2026-09 S1 反向邻接同步
        freeIdx.push(idx);
        activeNodeCount--;
        buffers.remove(id);
        // ★ 2026-09 P1-4 根因修复：节点（接口/容器）移除即清其待写欠款——
        //   否则容器拆除后 pendingWrites 永久残留（账实漂移）。
        pendingWrites.remove(id, Double.MAX_VALUE);
        for (Long eid : new ArrayList<>(edges.keySet())) {
            TransportEdge e = edges.get(eid);
            if (e != null && (id.equals(e.from) || id.equals(e.to))) removeEdge(eid);
        }
        routesDirty = true;
        versionCounter.incrementAndGet(); // 2026-09 S1 拓扑版本
    }

    // ===== 拓扑：边 =====

    /** 新增边（两个端点节点必须已存在；返回是否新增） */
    public boolean addEdge(TransportEdge e) {
        if (e == null) return false;
        if (!capabilityCompatible(e.type)) return false;
        Integer fi = idToIndex.get(e.from);
        Integer ti = idToIndex.get(e.to);
        if (fi == null || ti == null) return false;
        if (edges.putIfAbsent(e.id, e) != null) return false;
        adjacency[fi] = appendAdj(adjacency[fi], e.id);
        adjacency[ti] = appendAdj(adjacency[ti], e.id);
        reverseAdjacency[ti] = appendAdj(reverseAdjacency[ti], e.id); // 2026-09 S1
        routesDirty = true;
        versionCounter.incrementAndGet(); // 2026-09 S1 拓扑版本
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

    // ===== 距离（2026-09 S1 图法改造：弃 O(N²) 距离矩阵，改反向 BFS 按需） =====

    /**
     * 两节点间最短距离（管道段数/BFS 跳数；2026-09 S1 弃 Floyd-Warshall 矩阵——
     * O(N²) 内存/一次 O(N³) 在 10 万节点不可行；改为【反向 BFS 按需】：
     * 从 b 沿入边扩散早停到 a，距离 = 层数 = 段数，语义与旧矩阵一致）。
     * 不可达返回 {@link Integer#MAX_VALUE}（调用方按"无限远"处理）。
     */
    public int shortestDistance(Object a, Object b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        if (a.equals(b)) return 0;
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        java.util.Map<Object, Integer> dist = new java.util.HashMap<>();
        queue.add(b);
        dist.put(b, 0);
        while (!queue.isEmpty()) {
            Object cur = queue.poll();
            int d = dist.get(cur);
            if (cur.equals(a)) return d;
            for (long eid : reverseAdjacent(cur)) {
                TransportEdge e = edges.get(eid);
                if (e == null) continue;
                Object prev = e.from; // 反向：入边 from -> to(=cur)
                if (prev == null || dist.containsKey(prev)) continue;
                dist.put(prev, d + 1);
                queue.add(prev);
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 反向相邻边 id 数组（节点 → 入边；弱一致快照，调用方只读勿改；索引版） */
    public long[] reverseAdjacent(Object nodeId) {
        if (nodeId == null) return new long[0];
        Integer idx = idToIndex.get(nodeId);
        if (idx == null || idx >= reverseAdjacency.length) return new long[0];
        long[] a = reverseAdjacency[idx];
        return a == null ? new long[0] : a;
    }

    /** 移除边：清理两端邻接、在途队列；路由置脏 */
    public void removeEdge(long id) {
        TransportEdge e = edges.remove(id);
        if (e == null) return;
        Integer fi = idToIndex.get(e.from);
        Integer ti = idToIndex.get(e.to);
        if (fi != null) adjacency[fi] = removeAdjFrom(adjacency[fi], e.id);
        if (ti != null) {
            adjacency[ti] = removeAdjFrom(adjacency[ti], e.id);
            reverseAdjacency[ti] = removeAdjFrom(reverseAdjacency[ti], e.id); // 2026-09 S1
        }
        inFlight.remove(id);
        routesDirty = true;
        versionCounter.incrementAndGet(); // 2026-09 S1 拓扑版本
    }

    /** 邻接数组追加边 id（复制式；度数小开销可忽略，省去每边双对象） */
    private static long[] appendAdj(long[] cur, long eid) {
        if (cur == null) return new long[]{eid};
        int n = cur.length;
        long[] next = java.util.Arrays.copyOf(cur, n + 1);
        next[n] = eid;
        return next;
    }

    /** 邻接数组剔除边 id（复制式；未找到返回原数组） */
    private static long[] removeAdjFrom(long[] cur, long eid) {
        if (cur == null || cur.length == 0) return cur;
        long[] next = new long[cur.length];
        int w = 0;
        for (long x : cur) {
            if (x == eid) continue;
            next[w++] = x;
        }
        if (w == cur.length) return cur;
        return w == 0 ? new long[0] : java.util.Arrays.copyOf(next, w);
    }

    // ===== 拓扑查询 / 路由 =====

    /** 相邻边 id 数组（扁平；弱一致快照，调用方只读勿改；索引版） */
    public long[] adjacent(Object nodeId) {
        if (nodeId == null) return new long[0];
        Integer idx = idToIndex.get(nodeId);
        if (idx == null || idx >= adjacency.length) return new long[0];
        long[] a = adjacency[idx];
        return a == null ? new long[0] : a;
    }

    /** 从 from 到 to 的直接边（不存在返回 null） */
    public TransportEdge edgeToward(Object from, Object to) {
        for (long eid : adjacent(from)) {
            TransportEdge e = edges.get(eid);
            if (e != null && (from.equals(e.from) ? e.to.equals(to) : e.from.equals(to))) return e;
        }
        return null;
    }

    /**
     * 从 nodeId 到 targetId 的下一跳节点（按需最短路径；只对实际请求的对做一次
     * Dijkstra 并缓存；自身/不可达返回 null → 负载滞留缓冲）。
     */
    public Object nextHop(Object nodeId, Object targetId) {
        if (nodeId == null || targetId == null || nodeId.equals(targetId)) return null;
        if (routesDirty) {          // 拓扑变更后首次访问 → 清缓存强制按需重算
            lazyRoutes.clear();
            routesDirty = false;
        }
        ConcurrentMap<Object, Object> table =
                lazyRoutes.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>());
        Object cached = table.get(targetId);
        if (cached != null) return cached == NULL_HOP ? null : cached;
        Object hop = firstHop(nodeId, targetId);
        table.put(targetId, hop == null ? NULL_HOP : hop);
        return hop;
    }

    /** 从 src 到 target 的最短路径【第一跳】（早停 Dijkstra；target 出队即最短路确定） */
    private Object firstHop(Object src, Object target) {
        Map<Object, Double> dist = new HashMap<>();
        Map<Object, Object> first = new HashMap<>();
        PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingDouble(e -> e.dist));
        dist.put(src, 0.0);
        pq.add(new Entry(src, 0.0));
        while (!pq.isEmpty()) {
            Entry top = pq.poll();
            Object u = top.id;
            double d = top.dist;
            if (d > dist.getOrDefault(u, Double.POSITIVE_INFINITY) + 1e-9) continue;
            if (u.equals(target)) return first.get(target); // 最短路已确定（早停）
            for (long eid : adjacent(u)) {
                TransportEdge e = edge(eid);
                if (e == null) continue;
                Object v = u.equals(e.from) ? e.to : e.from;
                double nd = d + Math.max(0.001, e.cost);
                if (nd < dist.getOrDefault(v, Double.POSITIVE_INFINITY) - 1e-9) {
                    Object hop = u.equals(src) ? v : first.getOrDefault(u, v);
                    dist.put(v, nd);
                    first.put(v, hop);
                    pq.add(new Entry(v, nd));
                }
            }
        }
        return first.get(target); // 不可达
    }

    /** Dijkstra 队列条目 */
    private static final class Entry {
        final Object id;
        final double dist;

        Entry(Object id, double dist) {
            this.id = id;
            this.dist = dist;
        }
    }

    /** 清空懒路由缓存（显式 ROUTE 请求时调用；下次访问自动按需重算） */
    public void clearLazyRoutes() {
        lazyRoutes.clear();
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
        TransportNode n = node(sourceNodeId);
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

    // ===== 网络能力 / 单网络单能力 =====

    /** 网络标注的支持能力（GENERIC = 不限；其余类型一网一能力） */
    public TransferType capability() {
        return capability;
    }

    /** 设置能力（仅在 GENERIC 时可设一次；非 GENERIC 不可改） */
    public boolean setCapability(TransferType t) {
        if (capability != TransferType.GENERIC) return false;
        if (t != null && t != TransferType.GENERIC) {
            capability = t;
            return true;
        }
        return false;
    }

    private boolean capabilityCompatible(TransferType t) {
        if (capability == TransferType.GENERIC) return true;
        if (t == null || t == TransferType.GENERIC) return true;
        return capability == t;
    }

    // ===== 接口 / 容器（2026-08-29：一条「接口→容器信息」） =====

    /** 注册接口 */
    public void addInterface(NetworkInterface i) {
        if (i != null) interfaces.put(i.id, i);
    }

    /** 移除接口（连带其接口→容器关联） */
    public void removeInterface(Object id) {
        if (id != null) {
            interfaces.remove(id);
            ifaceContainers.remove(id);
        }
    }

    /** 查询接口 */
    public NetworkInterface interfaceAt(Object id) {
        return id == null ? null : interfaces.get(id);
    }

    /** 全部接口（弱一致快照） */
    public Collection<NetworkInterface> interfaces() {
        return interfaces.values();
    }

    /** 接口数 */
    public int interfaceCount() {
        return interfaces.size();
    }

    /** 注册容器信息 */
    public void addContainer(ContainerInfo c) {
        if (c != null) containers.put(c.id, c);
    }

    /** 移除容器信息 */
    public void removeContainer(Object id) {
        if (id != null) containers.remove(id);
    }

    /** 查询容器信息 */
    public ContainerInfo container(Object id) {
        return id == null ? null : containers.get(id);
    }

    /** 全部容器信息（弱一致快照） */
    public Collection<ContainerInfo> containers() {
        return containers.values();
    }

    /** 容器信息数 */
    public int containerCount() {
        return containers.size();
    }

    /** 建立「接口 → 容器列表」关联（一对一常用；一对多传多个容器 id） */
    public void linkInterface(Object ifaceId, Object... containerIds) {
        if (ifaceId == null) return;
        if (containerIds == null || containerIds.length == 0) {
            ifaceContainers.remove(ifaceId);
        } else {
            ifaceContainers.put(ifaceId, containerIds);
        }
    }

    /** 接口关联的容器 id 数组（无则空数组） */
    public Object[] containersOf(Object ifaceId) {
        Object[] c = ifaceId == null ? null : ifaceContainers.get(ifaceId);
        return c == null ? new Object[0] : c;
    }

    // ===== 传输中内容表（内存态，不存档）+ 空箱等待（背压） =====

    /** 累加待写出数量（核心执行线程；内存态，不存档） */
    public void addPendingWrite(Object containerId, double amount) {
        if (containerId == null || amount <= 0) return;
        pendingWrites.add(containerId, amount);
    }

    /** 查询待写出数量（0 = 无） */
    public double pendingWrite(Object containerId) {
        return pendingWrites.get(containerId);
    }

    /** 累计待写出总量 */
    public double pendingWriteTotal() {
        return pendingWrites.total();
    }

    /** 待写出容器键集合 */
    public Collection<Object> pendingWriteKeys() {
        return pendingWrites.keys();
    }

    /** 消费待写出（主线程 IO 完成回执后减量） */
    public void consumePendingWrite(Object containerId, double amount) {
        if (containerId == null || amount <= 0) return;
        pendingWrites.remove(containerId, amount);
    }

    /** 清除某容器的全部待写欠款（容器/节点从图移除时调用，P1-4 记账闭环） */
    public void removePendingWrite(Object containerId) {
        if (containerId == null) return;
        pendingWrites.remove(containerId, Double.MAX_VALUE);
    }

    /** 设置空箱/背压等待：从 currentStep 起再等 ticks 帧 */
    public void setWait(long currentStep, int ticks) {
        this.waitTicks = Math.max(0, ticks);
        this.waitUntilStep = Math.max(0, currentStep) + this.waitTicks;
    }

    /** 是否在等待期（此期间跳过流动推进，背压） */
    public boolean waiting(long step) {
        return step < waitUntilStep;
    }

    /** 等待结束的绝对步 */
    public long waitUntilStep() {
        return waitUntilStep;
    }

    /** 等待帧数 */
    public int waitTicks() {
        return waitTicks;
    }

    // ===== 网络拆分 / 合并（2026-08-29：类仿真引擎 SPLIT_MERGE，独立实现） =====

    /**
     * 把 other 图的全部内容并入本图（合并两网；本图保留，other 内容搬入，
     * 边 id 重新分配避免冲突）。
     */
    public void mergeFrom(TransportGraph other) {
        if (other == null || other == this) return;
        for (TransportNode n : other.nodes()) addNode(n);
        for (TransportEdge e : other.edges()) {
            if (node(e.from) != null && node(e.to) != null) {
                addEdge(new TransportEdge(nextEdgeId(), e.from, e.to, e.type,
                        e.throughput, e.latencyTicks, e.loss, e.cost));
            }
        }
        for (NetworkInterface i : other.interfaces()) addInterface(i);
        for (ContainerInfo c : other.containers()) addContainer(c);
        for (Map.Entry<Object, Object[]> en : other.ifaceContainers.entrySet()) {
            linkInterface(en.getKey(), en.getValue());
        }
        other.pendingWrites.forEachSafe(this::addPendingWrite);
        routesDirty = true;
    }

    /**
     * 按连通分量拆分成多个独立子网（原图只读不变；每个分量含其节点/边/接口/容器）。
     * 至少返回一个分量（节点非空时）。接口 id 约定 = 其挂接的节点 id（用于归属判定）。
     */
    public List<TransportGraph> split() {
        List<TransportGraph> out = new ArrayList<>();
        java.util.Set<Object> visited = new java.util.HashSet<>();
        for (TransportNode start : nodes()) {
            if (visited.contains(start.id)) continue;
            TransportGraph part = new TransportGraph(capability);
            java.util.ArrayDeque<Object> stackDesc = new java.util.ArrayDeque<>();
            stackDesc.push(start.id);
            visited.add(start.id);
            while (!stackDesc.isEmpty()) {
                Object u = stackDesc.pop();
                TransportNode un = node(u);
                if (un != null) part.addNode(un);
                for (long eid : adjacent(u)) {
                    TransportEdge e = edge(eid);
                    if (e == null) continue;
                    Object nei = u.equals(e.from) ? e.to : e.from;
                    if (!visited.contains(nei)) {
                        visited.add(nei);
                        stackDesc.push(nei);
                    }
                }
            }
            for (TransportEdge e : edges()) {
                if (part.node(e.from) != null && part.node(e.to) != null) {
                    part.addEdge(new TransportEdge(part.nextEdgeId(), e.from, e.to, e.type,
                            e.throughput, e.latencyTicks, e.loss, e.cost));
                }
            }
            for (NetworkInterface i : interfaces()) {
                if (part.node(i.id) != null) part.addInterface(i);
            }
            for (ContainerInfo c : containers()) {
                if (part.node(c.id) != null) part.addContainer(c);
            }
            for (Map.Entry<Object, Object[]> en : ifaceContainers.entrySet()) {
                if (part.interfaceAt(en.getKey()) != null) part.linkInterface(en.getKey(), en.getValue());
            }
            out.add(part);
        }
        return out;
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

    /** 标记路由脏（拓扑变更时内部自动调用；外部无需）。按需路由下：下次 nextHop 清缓存重算 */
    public void markRoutesDirty() {
        routesDirty = true;
    }

    /** 标记路由干净（按需路由下 no-op） */
    public void markRoutesClean() {
        routesDirty = false;
    }

    /** 设置路由表（兼容旧接口；按需路由下等价清空 + 可选预填） */
    public void setRoutes(Map<Object, Map<Object, Object>> r) {
        lazyRoutes.clear();
        if (r != null) {
            for (Map.Entry<Object, Map<Object, Object>> en : r.entrySet()) {
                if (en.getValue() == null) continue;
                lazyRoutes.computeIfAbsent(en.getKey(), k -> new ConcurrentHashMap<>())
                        .putAll(en.getValue());
            }
        }
        routesDirty = false;
    }

    /** 当前懒路由缓存（诊断；只含实际请求过的对，远小于全对全表） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<Object, Map<Object, Object>> routes() {
        return (Map) lazyRoutes;
    }

    // ===== 内部结构 =====

    /**
     * 紧凑 Object→double 计数表（2026-08-29 P2：待写出计数去掉 Double 装箱；
     * 线性探测 + 自动扩容；同一网锁内串行写、读加同步，竞争极低）。
     */
    private static final class ObjectDoubleMap {
        private Object[] keys = new Object[16];
        private double[] vals = new double[16];
        private int size;

        synchronized void add(Object k, double v) {
            if (k == null || v <= 0) return;
            int i = slot(k);
            while (keys[i] != null) {
                if (keys[i].equals(k)) {
                    vals[i] += v;
                    return;
                }
                i = (i + 1) & (keys.length - 1);
            }
            keys[i] = k;
            vals[i] = v;
            size++;
            if (size > (keys.length * 3) / 4) grow();
        }

        synchronized double get(Object k) {
            if (k == null) return 0;
            int i = slot(k);
            while (keys[i] != null) {
                if (keys[i].equals(k)) return vals[i];
                i = (i + 1) & (keys.length - 1);
            }
            return 0;
        }

        synchronized void remove(Object k, double amount) {
            if (k == null || amount <= 0) return;
            int i = slot(k);
            while (keys[i] != null) {
                if (keys[i].equals(k)) {
                    double nv = vals[i] - amount;
                    if (nv <= 1e-9) {
                        keys[i] = null;
                        size--;
                    } else {
                        vals[i] = nv;
                    }
                    return;
                }
                i = (i + 1) & (keys.length - 1);
            }
        }

        synchronized double total() {
            double s = 0;
            for (int i = 0; i < keys.length; i++) if (keys[i] != null) s += vals[i];
            return s;
        }

        synchronized Collection<Object> keys() {
            List<Object> out = new ArrayList<>(size);
            for (Object o : keys) if (o != null) out.add(o);
            return out;
        }

        synchronized int count() {
            return size;
        }

        synchronized void forEachSafe(java.util.function.BiConsumer<Object, Double> c) {
            for (Object o : keys) if (o != null) c.accept(o, get(o));
        }

        private int slot(Object k) {
            int h = k.hashCode();
            h ^= (h >>> 16);
            return h & (keys.length - 1);
        }

        private void grow() {
            Object[] ok = keys;
            double[] ov = vals;
            keys = new Object[ok.length * 2];
            vals = new double[ov.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != null) add(ok[i], ov[i]);
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
        return "TransportGraph{nodes=" + activeNodeCount + ", edges=" + edges.size()
                + ", step=" + stepCounter.get() + ", pending=" + pending() + "}";
    }
}