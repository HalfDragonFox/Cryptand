package com.hdf.cryptand.circuitsimulation.netgraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 导线网络对象（2026-08-14 用户架构：IE 风格——导线交由各自的网络管理，
 * 不采用统一图）。
 * <p>
 * 每个【物理连通分量】 = 一个 {@code WireNetwork} 实例（等价 IE 的
 * LocalWireNetwork）：
 * <ul>
 *   <li>各自管理自己的【精确端点】集合（WirePoint，端子级精确）+ 导线集合</li>
 *   <li>接线 → 两端网络 {@link #merge(WireNetwork)} 合并；拆线 →
 *       {@link #removeEdgeSplit(WirePoint, WirePoint)} 分裂（返回分裂出的新分量）</li>
 *   <li>网络内提供【连续段识别】 {@link #segments()}：度≠2 节点之间的连续导线
 *       路径 = 一段（Σ 电阻 / Σ 长度 / 统一温度 key / 统一烧毁）</li>
 *   <li>version：网络内结构版本（任何变更 +1）</li>
 * </ul>
 * 全局视图（渲染材质/同步/存档）由适配层全局管理类持有各网络引用——网络
 * 自管拓扑，全局类只管索引。
 * <p>
 * 【纯算法组件】：不依赖 Minecraft/PowerGrid。线程安全：{@link
 * ReentrantReadWriteLock}（主线程写，求解/渲染/存档线程读）。
 */
public final class WireNetwork {

    /** 全局唯一网络 id（由管理器分配） */
    public final long id;

    /** 节点 → 邻接边（LinkedHashSet：插入序稳定） */
    private final Map<WirePoint, Set<WireEdge>> adjacency = new LinkedHashMap<>();
    /** 全量边（无向去重） */
    private final Set<WireEdge> edges = new LinkedHashSet<>();
    /** 网络内结构版本 */
    private long version;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public WireNetwork(long id) {
        this.id = id;
    }

    /** 网络内结构版本（变更自增） */
    public long version() {
        lock.readLock().lock();
        try { return version; } finally { lock.readLock().unlock(); }
    }

    // ==================== 查询 ====================

    public boolean contains(WirePoint p) {
        lock.readLock().lock();
        try { return adjacency.containsKey(p); } finally { lock.readLock().unlock(); }
    }

    public int nodeCount() {
        lock.readLock().lock();
        try { return adjacency.size(); } finally { lock.readLock().unlock(); }
    }

    public int edgeCount() {
        lock.readLock().lock();
        try { return edges.size(); } finally { lock.readLock().unlock(); }
    }

    public Set<WirePoint> points() {
        lock.readLock().lock();
        // ⚠ 2026-08-30 审计 U14 根因：返回【副本】而非不可变视图——视图在
        // 读锁释放后仍指向底层集合，调用方锁外迭代时写线程 addEdge/merge/
        // removeNode 修改底层 → ConcurrentModificationException/撕裂数据。
        // 与 NetworkGraphStore.edgeList() 的既有正确做法一致。
        try { return new LinkedHashSet<>(adjacency.keySet()); } finally { lock.readLock().unlock(); }
    }

    public Set<WireEdge> edges() {
        lock.readLock().lock();
        try { return new LinkedHashSet<>(edges); } finally { lock.readLock().unlock(); }
    }

    /** 某节点的邻接边（副本；节点不存在返回空集）——⚠ 2026-08-30 审计 U14：
     *  返回副本而非不可变视图（读锁释放后视图指向底层集合，锁外迭代与写线程
     *  修改竞态）。 */
    public Set<WireEdge> adjacent(WirePoint p) {
        lock.readLock().lock();
        try {
            Set<WireEdge> s = adjacency.get(p);
            return s == null ? Collections.emptySet() : new LinkedHashSet<>(s);
        } finally { lock.readLock().unlock(); }
    }

    /** 节点度数 */
    public int degree(WirePoint p) {
        lock.readLock().lock();
        try {
            Set<WireEdge> s = adjacency.get(p);
            return s == null ? 0 : s.size();
        } finally { lock.readLock().unlock(); }
    }

    /** 是否存在 a-b 边 */
    public boolean connected(WirePoint a, WirePoint b) {
        lock.readLock().lock();
        try {
            Set<WireEdge> s = adjacency.get(a);
            if (s == null) return false;
            for (WireEdge e : s) if (e.other(a).equals(b)) return true;
            return false;
        } finally { lock.readLock().unlock(); }
    }

    // ==================== 变更（写锁） ====================

    /** 加入节点（孤立点，设备悬空端子）。幂等。 */
    public void addPoint(WirePoint p) {
        lock.writeLock().lock();
        try {
            if (adjacency.putIfAbsent(p, new LinkedHashSet<>()) == null) version++;
        } finally { lock.writeLock().unlock(); }
    }

    /** 加入导线（自动补端点）。重复边忽略。返回：是否真实加入（结构变化）。 */
    public boolean addEdge(WireEdge e) {
        lock.writeLock().lock();
        try {
            return addEdgeUnlocked(e);
        } finally { lock.writeLock().unlock(); }
    }

    private boolean addEdgeUnlocked(WireEdge e) {
        adjacency.computeIfAbsent(e.a, k -> new LinkedHashSet<>());
        adjacency.computeIfAbsent(e.b, k -> new LinkedHashSet<>());
        if (edges.add(e)) {
            adjacency.get(e.a).add(e);
            adjacency.get(e.b).add(e);
            version++;
            return true;
        }
        return false;
    }

    /** 移除导线（按端点对）。边不存在忽略。 */
    public void removeEdge(WirePoint a, WirePoint b) {
        lock.writeLock().lock();
        try {
            WireEdge toRemove = null;
            Set<WireEdge> s = adjacency.get(a);
            if (s != null) {
                for (WireEdge e : s) if (e.other(a).equals(b)) { toRemove = e; break; }
            }
            if (toRemove != null) removeEdgeUnlocked(toRemove);
        } finally { lock.writeLock().unlock(); }
    }

    private void removeEdgeUnlocked(WireEdge e) {
        if (edges.remove(e)) {
            Set<WireEdge> sa = adjacency.get(e.a);
            Set<WireEdge> sb = adjacency.get(e.b);
            if (sa != null) sa.remove(e);
            if (sb != null) sb.remove(e);
            version++;
        }
    }

    /** 移除节点及其全部邻接边（设备拆除）。返回被移除的边。 */
    public List<WireEdge> removeNode(WirePoint p) {
        lock.writeLock().lock();
        try {
            Set<WireEdge> s = adjacency.remove(p);
            if (s == null) return Collections.emptyList();
            List<WireEdge> removed = new ArrayList<>(s);
            for (WireEdge e : removed) {
                edges.remove(e);
                Set<WireEdge> other = adjacency.get(e.other(p));
                if (other != null) other.remove(e);
            }
            version++;
            return removed;
        } finally { lock.writeLock().unlock(); }
    }

    /** 是否已空（无节点） */
    public boolean isEmpty() {
        lock.readLock().lock();
        try { return adjacency.isEmpty(); } finally { lock.readLock().unlock(); }
    }

    /** 清空（网络废弃前调用） */
    public void clear() {
        lock.writeLock().lock();
        try {
            adjacency.clear();
            edges.clear();
            version++;
        } finally { lock.writeLock().unlock(); }
    }

    // ==================== IE 分裂/合并 ====================

    /** 模拟移除 a-b 边后，b 侧是否与 a 侧分裂（排除该边的 DFS）。 */
    public boolean wouldSplit(WirePoint a, WirePoint b) {
        lock.readLock().lock();
        try {
            if (!adjacency.containsKey(a) || !adjacency.containsKey(b)) return false;
            return !dfsReachableExcluding(a, b, a, b);
        } finally { lock.readLock().unlock(); }
    }

    /**
     * 移除 a-b 边；若网络分裂，返回【b 侧】新分量节点集合（a 侧保留在本网络，
     * 调用方把返回集合的节点/边移到新网络）。未分裂（b 仍有其他路径到 a）→
     * 返回空集。无该边 → 空集。
     * <p>IE LocalWireNetwork.split 语义：先移除导线，再检查连通性分裂。
     * <p>⚠ 2026-08-16 修复：原实现无条件返回 b 出发的可达集——非桥边（移除后
     * b 仍可达 a）也返回非空 → 调用方把 b 侧全部节点错误移到新网络 → 图被
     * 错误分裂成多网络对象（用户拆/替换端子方块后 4 边分 2 网络 → 构建漏
     * 导线 → 开路无电流）。现在仅【真分裂】（a 不在 bSide）才返回 bSide。
     */
    public Set<WirePoint> removeEdgeSplit(WirePoint a, WirePoint b) {
        lock.writeLock().lock();
        try {
            WireEdge toRemove = null;
            Set<WireEdge> sa = adjacency.get(a);
            if (sa != null) {
                for (WireEdge e : sa) if (e.other(a).equals(b)) { toRemove = e; break; }
            }
            if (toRemove == null) return Collections.emptySet();
            // 先计算移除后 b 侧可达集合（排除 a-b 边）
            Set<WirePoint> bSide = dfsExcludingSet(b, a, toRemove);
            removeEdgeUnlocked(toRemove);
            // 未分裂（b 仍可达 a）→ 返回空集（不移动节点）
            return bSide.contains(a) ? Collections.emptySet() : bSide;
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * 把另一个网络的全部节点/边并入本网络（other 被清空）。
     * IE merge 语义：接线连接两个分量 → 合并成一个网络。
     */
    public void merge(WireNetwork other) {
        if (other == null || other == this) return;
        lock.writeLock().lock();
        try {
            other.lock.writeLock().lock();
            try {
                for (WireEdge e : other.edges) addEdgeUnlocked(e);
                // ⚠ 2026-08-21 修复"合并后设备没网络"：原只迁移【边】，other 的
                // 【孤立点】（addDevice 建的悬空端子，无导线）不迁移 → byPoint
                // 已指向本网络（调用方更新）但本网络 adjacency 不含该孤立端子
                // → components()/points() 漏它 → 构建时设备缺端子（如电容负极）
                // → 不建模 → 无法测量（"合并问题"根因）。补齐：迁移 all 孤立点。
                for (WirePoint p : other.adjacency.keySet()) {
                    adjacency.computeIfAbsent(p, k -> new LinkedHashSet<>());
                }
                other.edges.clear();
                other.adjacency.clear();
                other.version++;
            } finally { other.lock.writeLock().unlock(); }
        } finally { lock.writeLock().unlock(); }
    }

    // ==================== 网络内算法 ====================

    /** 从 seed 出发 DFS 收集本网络内连通节点（本网络即一个分量） */
    public Set<WirePoint> connectedPoints(WirePoint seed) {
        lock.readLock().lock();
        try {
            Set<WirePoint> out = new LinkedHashSet<>();
            if (seed == null || !adjacency.containsKey(seed)) return out;
            java.util.Deque<WirePoint> stack = new java.util.ArrayDeque<>();
            stack.push(seed);
            while (!stack.isEmpty()) {
                WirePoint cur = stack.pop();
                if (!out.add(cur)) continue;
                Set<WireEdge> s = adjacency.get(cur);
                if (s == null) continue;
                for (WireEdge e : s) {
                    WirePoint o = e.other(cur);
                    if (!out.contains(o)) stack.push(o);
                }
            }
            return out;
        } finally { lock.readLock().unlock(); }
    }

    /** 分量内全部边（无向去重） */
    public List<WireEdge> edgesOf(Collection<WirePoint> comp) {
        lock.readLock().lock();
        try {
            Set<WireEdge> out = new LinkedHashSet<>();
            for (WirePoint p : comp) {
                Set<WireEdge> s = adjacency.get(p);
                if (s == null) continue;
                for (WireEdge e : s) {
                    if (comp.contains(e.other(p))) out.add(e);
                }
            }
            return new ArrayList<>(out);
        } finally { lock.readLock().unlock(); }
    }

    /**
     * 连续段识别（2026-08-14 用户要求：多个连续导线统一处理——一段连续导线 =
     * 段端点（度≠2 节点 或 边界节点）之间的连续路径，Σ 电阻 / Σ 长度 / 统一
     * 温度 key / 统一烧毁）。孤立点（度 0）不成段。
     */
    public List<WireSegment> segments() {
        return segments(null);
    }

    /**
     * 连续段识别（带边界判断）：boundary 返回 true 的节点（即使度 2，如设备
     * 端子/接线端子）也作为段端点——设备端子不能被连续段吞掉（否则元件端口
     * 孤立）。全环（所有节点度 2 且非边界）兜底成一段。
     */
    public List<WireSegment> segments(java.util.function.Predicate<WirePoint> boundary) {
        lock.readLock().lock();
        try {
            List<WireSegment> out = new ArrayList<>();
            Set<WireEdge> visited = new HashSet<>();
            // 起点 = 度≠2 节点，或边界节点（即使度 2），或【度 2 但两端导线
            // 材质/温度/每米电阻不一致】（2026-08-19：不一致的连续导线必须各自
            // 成段——同段统一电阻/温度模型，混材质/电阻会算错电阻和温升）
            for (WirePoint p : adjacency.keySet()) {
                Set<WireEdge> adj = adjacency.get(p);
                if (adj == null || adj.isEmpty()) continue;
                boolean isStart = adj.size() != 2
                        || (boundary != null && boundary.test(p));
                if (!isStart && adj.size() == 2) {
                    java.util.Iterator<WireEdge> it = adj.iterator();
                    WireEdge e1 = it.next();
                    WireEdge e2 = it.next();
                    if (!materialConsistent(e1, e2)) isStart = true;
                }
                if (!isStart) continue;
                for (WireEdge start : adj) {
                    if (!visited.add(start)) continue;
                    out.add(walkSegment(p, start, boundary, visited));
                }
            }
            // 全环兜底：剩余未访问边（所有节点度 2 的环路）
            for (WireEdge start : edges) {
                if (!visited.add(start)) continue;
                out.add(walkSegment(start.a, start, boundary, visited));
            }
            return out;
        } finally { lock.readLock().unlock(); }
    }

    /** 导线材质 key（itemId 优先，回退渲染器 id；null=未知） */
    private static String materialKeyOf(WireEdge e) {
        if (e == null) return null;
        if (e.itemId != null) return e.itemId;
        return e.rendererId;
    }

    /** 导线每米电阻（Ω/m，2026-08-19 "1m 状态下电阻值"；长度未知/零 → NaN） */
    private static double resistancePerMeter(WireEdge e) {
        if (e == null || e.length <= 1e-9) return Double.NaN;
        return Math.max(e.resistance, 0) / e.length;
    }

    /**
     * 两段导线能否并入同一连续段（2026-08-19 用户要求：材质/温升/电阻一致才
     * 可一起计算）：
     * <ol>
     *   <li><b>材质一致</b>：itemId 优先，回退渲染器 id（任一有值且不等 → 不一致）</li>
     *   <li><b>温度状态一致</b>：携带的段温度 key（temperatureKey）不同 →
     *       分属不同温度模型 → 不一致（null 视为一致/未知）</li>
     *   <li><b>电阻一致（1m 状态电阻值）</b>：每米电阻相同 → 段内单位长度
     *       发热相同 → 温升均匀。长度可归一化时按相对容差 1e-6 比较；
     *       任一侧长度未知（NaN）→ 该项跳过（靠材质判断）</li>
     * </ol>
     * 任一项不一致 → 断段（各自成段，独立电阻/温度模型，独立烧毁）。
     */
    private static boolean materialConsistent(WireEdge a, WireEdge b) {
        if (a == null || b == null) return true;
        // 1) 材质一致
        String ma = materialKeyOf(a);
        String mb = materialKeyOf(b);
        if (ma != null && mb != null && !ma.equals(mb)) return false;
        // 2) 温度状态一致
        if (a.temperatureKey != null && b.temperatureKey != null
                && !a.temperatureKey.equals(b.temperatureKey)) return false;
        // 3) 电阻一致（1m 状态电阻值；能归一化时比较）
        double ra = resistancePerMeter(a);
        double rb = resistancePerMeter(b);
        if (!Double.isNaN(ra) && !Double.isNaN(rb)) {
            double denom = Math.max(1e-9, Math.max(Math.abs(ra), Math.abs(rb)));
            if (Math.abs(ra - rb) > 1e-6 * denom) return false;
        }
        return true;
    }

    /** 从 p 沿 start 延伸（度2 且非边界则继续），构造段（读锁内调用）。
     *  2026-08-19：延伸时要求下一根导线【材质一致 + 温度状态一致 + 每米电阻
     *  一致】——不一致则断段（下一根留给新段，不标记 visited 以免丢失）。 */
    private WireSegment walkSegment(WirePoint p, WireEdge start,
                                    java.util.function.Predicate<WirePoint> boundary,
                                    Set<WireEdge> visited) {
        List<WireEdge> path = new ArrayList<>();
        path.add(start);
        WirePoint cur = start.other(p);
        WireEdge curEdge = start;
        while (true) {
            Set<WireEdge> adjCur = adjacency.get(cur);
            boolean isBoundary = boundary != null && boundary.test(cur);
            if (adjCur == null || adjCur.size() != 2 || isBoundary) break;
            WireEdge next = null;
            for (WireEdge x : adjCur) {
                if (!x.equals(curEdge)) { next = x; break; }
            }
            if (next == null) break;
            // 材质/温度不一致 → 断段（next 未标记 visited → 由 segments() 下一轮
            // 起点检测（度2 两端不一致 → 是起点）重新建段）
            if (!materialConsistent(curEdge, next)) break;
            if (!visited.add(next)) break;
            path.add(next);
            curEdge = next;
            cur = next.other(cur);
        }
        double r = 0, len = 0;
        // 2026-08-19 段 key 规范化：key = 【排序后】的边 token（"a>b" 按端点
        // key 排序）。此前按【走步顺序】拼接——同一物理段在 segments()（无边界）
        // 与 buildContextFromGraph 的 tmpNet.segments(boundary) 中走步起点可能
        // 不同 → key 字符串不同（反向/乱序）→ 温度表读不到加热用的温度模型
        // （一直显示环境温度 20°C）而导线照常烧毁。排序后与走步方向无关，
        // 两个路径对同一物理段必然得到同一 key。
        // ⚠ 2026-08-24 再统一：PhasorNetworkBuilder 段温度模型（烧毁用的）key =
        // 【段内全部端点 key 去重排序 + ';'】（pathKey："B..;B..;"）；本处此前用
        // 边 token（"a>b;"）→ 温度计读到 25°C 而导线已 200°C（用户断点实锤：
        // wireStore=1/temp=25 vs seg.thermal()=204）。改为与 pathKey 相同格式：
        // 收集段内全部端点 key（含中间点）去重排序 + ';'。
        List<String> pts = new ArrayList<>(path.size() * 2);
        for (WireEdge pe : path) {
            r += Math.max(pe.resistance, 0);
            len += Math.max(pe.length, 0);
            if (pe.a != null && pe.a.key != null) pts.add(pe.a.key);
            if (pe.b != null && pe.b.key != null) pts.add(pe.b.key);
        }
        java.util.Set<String> uniq = new java.util.LinkedHashSet<>(pts);
        List<String> sortedPts = new ArrayList<>(uniq);
        sortedPts.sort(null);
        StringBuilder kb = new StringBuilder();
        for (String t : sortedPts) kb.append(t).append(';');
        return new WireSegment(Collections.unmodifiableList(path), p, cur, r, len,
                kb.toString());
    }

    /** 排除 a-b 边后，从 from 出发能否到达 target（读锁内调用） */
    private boolean dfsReachableExcluding(WirePoint from, WirePoint target,
                                          WirePoint a, WirePoint b) {
        Set<WirePoint> seen = new HashSet<>();
        java.util.Deque<WirePoint> stack = new java.util.ArrayDeque<>();
        stack.push(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            WirePoint cur = stack.pop();
            if (cur.equals(target)) return true;
            Set<WireEdge> s = adjacency.get(cur);
            if (s == null) continue;
            for (WireEdge e : s) {
                WirePoint o = e.other(cur);
                if ((cur.equals(a) && o.equals(b)) || (cur.equals(b) && o.equals(a))) continue;
                if (seen.add(o)) stack.push(o);
            }
        }
        return false;
    }

    /** 排除 toRemove 边后，从 from 出发可达的全部节点（写锁内调用） */
    private Set<WirePoint> dfsExcludingSet(WirePoint from, WirePoint skipA, WireEdge toRemove) {
        Set<WirePoint> out = new LinkedHashSet<>();
        java.util.Deque<WirePoint> stack = new java.util.ArrayDeque<>();
        stack.push(from);
        out.add(from);
        while (!stack.isEmpty()) {
            WirePoint cur = stack.pop();
            Set<WireEdge> s = adjacency.get(cur);
            if (s == null) continue;
            for (WireEdge e : s) {
                if (e.equals(toRemove)) continue;
                WirePoint o = e.other(cur);
                if (out.add(o)) stack.push(o);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return "WireNetwork{id=" + id + " nodes=" + adjacency.size()
                    + " edges=" + edges.size() + " ver=" + version + "}";
        } finally { lock.readLock().unlock(); }
    }
}
