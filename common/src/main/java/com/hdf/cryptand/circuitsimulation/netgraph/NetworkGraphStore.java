package com.hdf.cryptand.circuitsimulation.netgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 导线网络图存储（2026-08-19 算法下沉：核心自动处理网络拆合/合并）。
 *
 * <p>纯算法组件（零 Minecraft / 零 PowerGrid 依赖）：把原 neoforge
 * {@code WireNetworkManager} 的图编排逻辑整体下沉到 common——主线程/调用方
 * 只需把【边/点的纯数据变化】发进来，本类自动完成：
 * <pre>
 *   addEdge    接线编排：同网络加边 / 跨网络 merge 合并 / 都不在新建网络
 *   removeEdge 拆线编排：removeEdgeSplit 分裂 → b 侧移入新网络
 *   addDevice  设备放置：同方块全部端子并入同一网络（放下即建网）
 *   removePoint 设备拆除：移除端点 + 全部导线 → 剩余重新分组
 *   compactNetworks 空网络收尾（幂等）
 * </pre>
 * 全局索引：端点 key → 所属网络；每物理连通分量 = 一个 {@link WireNetwork}。
 * 任何真实结构变化 → {@link #version()} +1（稳定检测/缓存失效依据；重复幂等
 * 导入不涨版本——convertWires 每 tick 全量重发靠此避免风暴）。
 *
 * <p>线程安全：{@link ReentrantReadWriteLock}（写：主线程/调用方；读：
 * 求解/渲染/存档线程）。调用方通过返回值判断是否有变化（如 MC 侧标记存档）。
 */
public final class NetworkGraphStore {

    /** 端点 key → 所属网络（精确端子索引） */
    private final Map<String, WireNetwork> byPoint = new HashMap<>();
    /** 所有网络（各自管理导线） */
    private final List<WireNetwork> networks = new ArrayList<>();
    /** 全局结构版本：任何真实网络变化 +1 */
    private long version;
    private long nextNetworkId = 1;

    // ⚠ 2026-08-30 审计 #12：诊断日志走 System.Logger（不再 System.out.println）+
    // 节流——原无条件打印（每次拆线触发 ENTER / 每次清空触发 CLEAR）造成性能
    // 与日志污染；lost>0（重建丢点）是真实异常 → warn 保留可见。
    private static final System.Logger LOG = System.getLogger("cryptand.graphstore");
    private static volatile long gsDbgLast;
    private static final long GS_DBG_INTERVAL_MS = 5000;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public NetworkGraphStore() {
    }

    /** 指定起始网络 id（多实例隔离时用；默认从 1 开始） */
    public NetworkGraphStore(long startNetworkId) {
        this.nextNetworkId = startNetworkId;
    }

    // ==================== 查询（读锁） ====================

    /** 全局结构版本（任何增删合并分裂 +1） */
    public long version() {
        lock.readLock().lock();
        try { return version; } finally { lock.readLock().unlock(); }
    }

    public int nodeCount() {
        lock.readLock().lock();
        try {
            int n = 0;
            for (WireNetwork net : networks) n += net.nodeCount();
            return n;
        } finally { lock.readLock().unlock(); }
    }

    public int edgeCount() {
        lock.readLock().lock();
        try {
            int n = 0;
            for (WireNetwork net : networks) n += net.edgeCount();
            return n;
        } finally { lock.readLock().unlock(); }
    }

    /** 端点是否存在（任一网络） */
    public boolean contains(WirePoint p) {
        lock.readLock().lock();
        try { return byPoint.containsKey(p.key); } finally { lock.readLock().unlock(); }
    }

    /** 端点所属网络（不存在返回 null） */
    public WireNetwork networkOf(WirePoint p) {
        lock.readLock().lock();
        try { return byPoint.get(p.key); } finally { lock.readLock().unlock(); }
    }

    public WireNetwork networkOf(String key) {
        lock.readLock().lock();
        try { return byPoint.get(key); } finally { lock.readLock().unlock(); }
    }

    /** 某端点邻接边（跨网络查；不存在返回空集） */
    public Set<WireEdge> adjacent(WirePoint p) {
        lock.readLock().lock();
        try {
            WireNetwork net = byPoint.get(p.key);
            return net == null ? Set.of() : net.adjacent(p);
        } finally { lock.readLock().unlock(); }
    }

    /** 全部网络（副本；各自管理导线） */
    public List<WireNetwork> networks() {
        lock.readLock().lock();
        try { return new ArrayList<>(networks); } finally { lock.readLock().unlock(); }
    }

    /** 全量导线（全局视图：图同步/材质/渲染） */
    public List<WireEdge> edgeList() {
        lock.readLock().lock();
        try {
            List<WireEdge> out = new ArrayList<>();
            for (WireNetwork net : networks) out.addAll(net.edges());
            return out;
        } finally { lock.readLock().unlock(); }
    }

    /** 全量端点（副本） */
    public List<WirePoint> pointList() {
        lock.readLock().lock();
        try {
            List<WirePoint> out = new ArrayList<>(byPoint.size());
            for (String k : byPoint.keySet()) out.add(new WirePoint(k));
            return out;
        } finally { lock.readLock().unlock(); }
    }

    /** 全部分量（每个 = 该分量的节点集合） */
    public List<Set<WirePoint>> components() {
        lock.readLock().lock();
        try {
            List<Set<WirePoint>> out = new ArrayList<>();
            for (WireNetwork net : networks) {
                Set<WirePoint> comp = net.points();
                if (!comp.isEmpty()) out.add(comp);
            }
            return out;
        } finally { lock.readLock().unlock(); }
    }

    /** 全局连续段（各网络段汇总；统一温度/烧毁） */
    public List<WireSegment> segments() {
        lock.readLock().lock();
        try {
            List<WireSegment> out = new ArrayList<>();
            for (WireNetwork net : networks) out.addAll(net.segments());
            return out;
        } finally { lock.readLock().unlock(); }
    }

    // ==================== 变更（写锁；返回是否真实变化） ====================

    /** 加入孤立点（设备悬空端子；新建单点网络）。幂等。 */
    public boolean addPoint(WirePoint p) {
        lock.writeLock().lock();
        try {
            if (byPoint.containsKey(p.key)) return false;
            WireNetwork n = new WireNetwork(nextNetworkId++);
            n.addPoint(p);
            byPoint.put(p.key, n);
            networks.add(n);
            version++;
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * 设备放置：把该设备全部声明端子 key 并入【同一单点网络】（放下即建网）。
     * <p>端子间【无边】（不导电短路）——仅网络对象层面同属一个分量：
     *   - 未接线也能被识别（networkOf 命中）；
     *   - 接线时经 {@link #addEdge} 正常 merge 进导线网络；
     *   - 构建器会把同方块悬空端子并入求解分量，设备元件完整建模。
     * 幂等：全部端子已入图 → 只做"同网络"收敛；部分已接线 → 补齐缺失端子
     * 到该端子所在网络。
     *
     * @param terminalKeys 该设备全部端子 key（如 "B12#34#0".."B12#34#3"）
     * @return 是否发生真实变化
     */
    public boolean addDevice(java.util.Collection<String> terminalKeys) {
        if (terminalKeys == null || terminalKeys.isEmpty()) return false;
        lock.writeLock().lock();
        boolean changed = false;
        try {
            boolean all = true;
            WireNetwork n = null;
            for (String k : terminalKeys) {
                WireNetwork existing = byPoint.get(k);
                if (existing == null) { all = false; continue; }
                if (n == null) n = existing;
            }
            if (all) {
                // ===== 2026-09-15 修复"重进世界后电路无功率 / 调参不更新"=====
                // 全部端子已在图中，【不等于】它们已经同属一个网络。
                // 世界重载时 WireSavedData 先按 edges 恢复导线端点 → 同一设备的
                // 端子各自落在【不同的导线分量】里（每个分量只含该设备的一个端子，
                // 例如 A#1-B#0 一个分量、A#0-B#1 另一个分量）⇒ 构建器的"设备端子簇
                // 扩展"拿不到成对端子 ⇒ 设备元件根本无法组装 ⇒ 无功率；同时也没有
                // ParamSource 注册 ⇒ 调参永不刷新。用户"剪线重放就好"正是因为
                // 它重新走了 addEdge/addDevice 的合并路径。
                // 这里补齐语义：把它们收敛到同一网络（幂等）。端子间【仍然无边】
                // （不导电短路），只是同属一个分量 —— 与厂商语义一致。
                for (String k : terminalKeys) {
                    WireNetwork other = byPoint.get(k);
                    if (other == null || other == n) continue;
                    java.util.List<WirePoint> otherPoints =
                            new ArrayList<>(other.points());
                    n.merge(other);
                    for (WirePoint p : otherPoints) byPoint.put(p.key, n);
                    networks.remove(other);
                    changed = true;
                }
                if (changed) version++;
                return changed;
            }
            if (n == null) {
                n = new WireNetwork(nextNetworkId++);
                networks.add(n);
            }
            for (String k : terminalKeys) {
                if (byPoint.containsKey(k)) continue;
                WirePoint p = new WirePoint(k);
                n.addPoint(p);
                byPoint.put(k, n);
                changed = true;
            }
            if (changed) version++;
            return changed;
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * 接线：加导线。找两端所属网络：
     *   - 同网络（环）→ 直接加边（重复边幂等，不算变化）
     *   - 不同网络 → 合并（merge，IE 语义）后加边
     *   - 都不在 → 新建网络
     * <p>只有真实结构变化才 version++——每 tick 重复导入同一条导线
     * （新 WireEdge 对象 equals 相同 → 幂等）不应涨版本，否则稳定检测
     * 反复重置 → 分量电压被清零。
     */
    public boolean addEdge(WireEdge e) {
        lock.writeLock().lock();
        boolean changed = false;
        try {
            WireNetwork na = byPoint.get(e.a.key);
            WireNetwork nb = byPoint.get(e.b.key);
            if (na != null && nb != null && na == nb) {
                changed = na.addEdge(e); // 环/同网络：重复边 → false（无变化）
            } else if (na != null && nb != null) {
                // 合并两网络（merge 前记录 nb 全部端点，merge 后更新索引）
                List<WirePoint> nbPoints = new ArrayList<>(nb.points());
                na.merge(nb);
                for (WirePoint p : nbPoints) byPoint.put(p.key, na);
                networks.remove(nb);
                na.addEdge(e);
                changed = true;
            } else {
                WireNetwork n = na != null ? na : nb;
                if (n == null) {
                    n = new WireNetwork(nextNetworkId++);
                    networks.add(n);
                }
                changed = n.addEdge(e);
                byPoint.put(e.a.key, n);
                byPoint.put(e.b.key, n);
            }
            if (changed) version++;
        } finally { lock.writeLock().unlock(); }
        return changed;
    }

    /**
     * 拆线：移除 a-b 边。所属网络若分裂 → 分裂出的【b 侧】分量移入新网络。
     * IE LocalWireNetwork split 语义。端点仍保留（悬空端子）。
     * <p>边不存在时（重复拆/悬空端）不涨版本。
     */
    public boolean removeEdge(WirePoint a, WirePoint b) {
        lock.writeLock().lock();
        boolean changed = false;
        try {
            WireNetwork net = byPoint.get(a.key);
            if (net == null) net = byPoint.get(b.key);
            if (net == null) return false;
            if (!net.connected(a, b)) return false; // 该边不存在 → 无变化
            Set<WirePoint> bSide = net.removeEdgeSplit(a, b);
            if (!bSide.isEmpty()) {
                // ⚠ 2026-08-21 修复"剪线后设备端子分离 → 电容没网络/没正确分裂"：
                // removeEdgeSplit 的 bSide 是【导线连通 DFS 可达集】——孤立端子
                // （同方块其它端子，无导线，如电容负极 #1）不可达 → 不进入 bSide
                // → 拆线后电容#0 移入新网络、电容#1 留在原网络 → 设备两端分离
                // → 电容无法建模（没网络）；且构建时设备端子簇扩展跨网络合并 →
                // 掩盖分裂（"打掉 AC 重放还在同一网络"）。扩展：bSide 中每个点
                // 所属【同方块】（key 前缀 "B{pos}"）的其它端子，若还在原网络 →
                // 一并并入 bSide 移动（设备端子保持同网络 → 正确分裂）。
                for (WirePoint p : new java.util.ArrayList<>(bSide)) {
                    String prefix = prefixOf(p.key);
                    if (prefix == null) continue;
                    for (WirePoint q : net.points()) {
                        if (q == p || bSide.contains(q)) continue;
                        if (prefix.equals(prefixOf(q.key))) bSide.add(q);
                    }
                }
                // 分裂：b 侧移入新网络
                WireNetwork newNet = new WireNetwork(nextNetworkId++);
                for (WirePoint p : bSide) {
                    List<WireEdge> moved = net.removeNode(p);
                    for (WireEdge e : moved) newNet.addEdge(e);
                    byPoint.put(p.key, newNet);
                }
                networks.add(newNet);
            }
            // ⚠ 2026-08-22 分裂后重新分组（治本，与 NetworkCache 一致）：同方块
            // 扩展可能把某点的边另一端（跨 bSide）拉入新网络而 byPoint 未更新，
            // 或设备端子/剩余边使网络实际不分裂（双线回路剪一根仍连通）→ 由
            // ensureComponentNetworks 统一修正（byPoint=网络一致性，剪线端点不丢）。
            ensureComponentNetworks();
            changed = true;
            version++;
        } finally { lock.writeLock().unlock(); }
        return changed;
    }

    /** 端点 key 的方块前缀（"BBlockPos{...}#t" → "BBlockPos{...}"；J 点同）。
     *  同前缀 = 同方块端子（设备完整性判定）。 */
    private static String prefixOf(String key) {
        if (key == null) return null;
        int i = key.indexOf('#');
        return i < 0 ? null : key.substring(0, i);
    }

    /**
     * 设备拆除：移除端点及其全部导线。所属网络可能分裂 → 重新分组。
     */
    public boolean removePoint(WirePoint p) {
        lock.writeLock().lock();
        boolean changed = false;
        try {
            WireNetwork net = byPoint.get(p.key);
            if (net == null) return false;
            // 记录全部节点 + 边，然后清空该网络
            Set<WirePoint> allPoints = new HashSet<>(net.points());
            List<WireEdge> allEdges = new ArrayList<>(net.edges());
            net.clear();
            networks.remove(net);
            byPoint.remove(p.key);
            // 重新分组剩余（排除 p 的边）
            Map<String, WireNetwork> assigned = new HashMap<>();
            for (WireEdge e : allEdges) {
                if (e.a.equals(p) || e.b.equals(p)) continue;
                assignEdge(e, assigned);
            }
            // 孤立剩余点（无边的端点）→ 单点网络
            for (WirePoint q : allPoints) {
                if (q.equals(p)) continue;
                if (assigned.containsKey(q.key)) {
                    byPoint.put(q.key, assigned.get(q.key));
                } else {
                    WireNetwork n = new WireNetwork(nextNetworkId++);
                    n.addPoint(q);
                    byPoint.put(q.key, n);
                    networks.add(n);
                }
            }
            version++;
            changed = true;
        } finally { lock.writeLock().unlock(); }
        return changed;
    }

    /**
     * 网络拆合一致性收尾：移除空网络对象（无任何端点）。网络拆合后可能残留
     * 空网络（如 removeEdge 分裂后旧网络全部节点被移走）；保持 networks()
     * 列表干净，防止空网络无限累积。幂等。
     */
    public boolean compactNetworks() {
        lock.writeLock().lock();
        try {
            return networks.removeIf(n -> n.nodeCount() == 0);
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * 拆除后网络完整性（2026-08-21 用户要求：拆除后检查是否分割为两个独立
     * 回路，是则给另一部分重新分配网络）。完全重新分组：
     *   1) 按【导线连通】分组成连通分量（assignEdge）；
     *   2) 【设备端子同网络】——同方块（key 前缀）的端子必须同网络（设备
     *      任一端子在某网络 → 全归入；全孤立 → 同方块端子同一单点网络）。
     *      这保证拆线后电容#0/#1 不分离（否则电容两端拆到不同网络 → 没网络）；
     *   3) 其余孤立点（J 点等）→ 单点网络。
     * 分裂的两部分各自获得独立网络对象（byPoint 一致，无残留/空网络）。
     * 幂等，可重复调用。
     */
    public boolean ensureComponentNetworks() {
        lock.writeLock().lock();
        boolean changed = false;
        try {
            Set<WirePoint> allPoints = new HashSet<>();
            List<WireEdge> allEdges = new ArrayList<>();
            for (WireNetwork net : networks) {
                allPoints.addAll(net.points());
                allEdges.addAll(net.edges());
            }
            // ⚠ 2026-08-22 修复"只在 byPoint、不在任何网络"的点被重建丢弃：
            // allPoints 原本只从 net.points() 收集——若历史遗留/某分支导致某点
            // 仅在 byPoint（网络无），重建时 byPoint.clear() 后它永久丢失。
            // 补上 byPoint 的 key：这些点至少以孤立点恢复（分支3）。
            for (String k : byPoint.keySet()) {
                allPoints.add(new WirePoint(k));
            }
            // 诊断（2026-08-22 定位图被清空）：进入时图状态（#12：logger+节流）
            try {
                long now = System.currentTimeMillis();
                if (now - gsDbgLast > GS_DBG_INTERVAL_MS) {
                    gsDbgLast = now;
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    String caller = st.length > 3
                            ? st[3].getClassName() + "#" + st[3].getMethodName() : "?";
                    LOG.log(System.Logger.Level.DEBUG,
                            "[GraphStore] ensureComponentNetworks ENTER nets={0} "
                                    + "allPoints={1} byPoint={2} by {3}",
                            networks.size(), allPoints.size(), byPoint.size(), caller);
                }
            } catch (Throwable ignored) {
            }
            if (allPoints.isEmpty()) return false;
            for (WireNetwork net : networks) net.clear();
            networks.clear();
            byPoint.clear();
            Map<String, WireNetwork> assigned = new HashMap<>();
            for (WireEdge e : allEdges) assignEdge(e, assigned);
            // 设备完整性：同方块端子同网络
            Map<String, List<WirePoint>> byPrefix = new HashMap<>();
            for (WirePoint q : allPoints) {
                String prefix = prefixOf(q.key);
                if (prefix != null) byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(q);
            }
            for (List<WirePoint> terms : byPrefix.values()) {
                if (terms.size() < 2) continue;
                WireNetwork target = null;
                for (WirePoint q : terms) {
                    WireNetwork n = assigned.get(q.key);
                    if (n != null) { target = n; break; }
                }
                if (target == null) {
                    target = new WireNetwork(nextNetworkId++);
                    networks.add(target);
                }
                for (WirePoint q : terms) {
                    WireNetwork old = assigned.get(q.key);
                    if (old != null && old != target) {
                        List<WirePoint> oldPoints = new ArrayList<>(old.points());
                        target.merge(old);
                        for (WirePoint r : oldPoints) {
                            assigned.put(r.key, target);
                            // ⚠ 2026-08-22 修复"网络有点、byPoint 无"（剪线
                            // contains-fail 根因）：merge 把 old 网络的点（含非
                            // terms 的其他方块点）移入 target，但原代码只更新
                            // assigned、未更新 byPoint → 这些点在网络里
                            // components() 可见、contains()（查 byPoint）却 false
                            // → 剪线时异步 executeDestroy contains(b)=false 无法
                            // 删除（实测：图 5 点 2 边，剪线 b 端点 contains=false
                            // 但 GraphRound 分量里却有它）。
                            byPoint.put(r.key, target);
                        }
                        networks.remove(old);
                    }
                    // ⚠ 2026-08-22 修复"reconstruct 丢点（实测 5->3）"：
                    // q 之前【未 assigned】（无导线的设备悬空端子）时，原代码只
                    // 更新 assigned/byPoint 指向 target，却【没真正 addPoint(q)
                    // 进 target.adjacency】→ byPoint 有 q 但 target.points() 无 q
                    // → 下次 ensureComponentNetworks 从 net.points() 收集 allPoints
                    // 时漏掉 q，且分支3因 assigned 含 q 跳过 → 点永久丢失 →
                    // adjacent(q) 空 → 剪线查不到边。必须显式 addPoint（幂等）。
                    target.addPoint(q);
                    assigned.put(q.key, target);
                    byPoint.put(q.key, target);
                }
            }
            // 孤立点（无方块前缀，如 J 点）→ 单点网络
            for (WirePoint q : allPoints) {
                if (assigned.containsKey(q.key)) continue;
                WireNetwork n2 = new WireNetwork(nextNetworkId++);
                n2.addPoint(q);
                byPoint.put(q.key, n2);
                networks.add(n2);
            }
            // ⚠ 2026-08-22 一致性收尾（治本）：byPoint 必须与网络 adjacency
            // 完全一致——contains()/adjacent() 查 byPoint，components()/points()
            // 查网络，任何分支遗漏都会导致二者不一致 → 剪线 contains-fail、
            // 求解/颜色分量不一致。直接按重建后的网络点集重建 byPoint，
            // 保证任何分支都不遗漏（网络有 byPoint 无 / byPoint 有网络无）。
            byPoint.clear();
            for (WireNetwork net : networks) {
                for (WirePoint p : net.points()) {
                    byPoint.put(p.key, net);
                }
            }
            changed = true;
            version++;
            // 诊断（2026-08-22 定位图被清空）：进出节点数 + 丢失点
            try {
                int lost = 0;
                StringBuilder ls = new StringBuilder();
                for (WirePoint q : allPoints) {
                    if (!byPoint.containsKey(q.key)) {
                        lost++;
                        if (lost <= 8) ls.append(q.key).append(' ');
                    }
                }
                if (lost > 0 || byPoint.size() != allPoints.size()) {
                    // #12：重建丢点是真实异常 → warn（节流）
                    long now = System.currentTimeMillis();
                    if (now - gsDbgLast > GS_DBG_INTERVAL_MS) {
                        gsDbgLast = now;
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        String caller = st.length > 3
                                ? st[3].getClassName() + "#" + st[3].getMethodName() : "?";
                        LOG.log(System.Logger.Level.WARNING,
                                "[GraphStore] ensureComponentNetworks in={0} out={1} "
                                        + "lost={2} lostKeys=[{3}] by {4}",
                                allPoints.size(), byPoint.size(), lost, ls, caller);
                    }
                }
            } catch (Throwable ignored) {
            }
        } finally { lock.writeLock().unlock(); }
        return changed;
    }

    /** 清空（世界卸载/重载） */
    public void clear() {
        lock.writeLock().lock();
        try {
            // 诊断（2026-08-22 定位图被清空）：打印调用者（#12：logger+节流）
            try {
                long now = System.currentTimeMillis();
                if (now - gsDbgLast > GS_DBG_INTERVAL_MS) {
                    gsDbgLast = now;
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    String caller = st.length > 3
                            ? st[3].getClassName() + "#" + st[3].getMethodName() : "?";
                    LOG.log(System.Logger.Level.DEBUG,
                            "[GraphStore] CLEAR nodes={0} by {1}",
                            byPoint.size(), caller);
                }
            } catch (Throwable ignored) {
            }
            for (WireNetwork net : networks) net.clear();
            networks.clear();
            byPoint.clear();
            version++;
        } finally { lock.writeLock().unlock(); }
    }

    /** 分组辅助：把边归入已分配网络 / 新建（调用方持写锁） */
    private void assignEdge(WireEdge e, Map<String, WireNetwork> assigned) {
        WireNetwork na = assigned.get(e.a.key);
        WireNetwork nb = assigned.get(e.b.key);
        if (na != null && nb != null && na == nb) {
            na.addEdge(e);
        } else if (na != null && nb != null) {
            List<WirePoint> nbPoints = new ArrayList<>(nb.points());
            na.merge(nb);
            for (WirePoint q : nbPoints) assigned.put(q.key, na);
            networks.remove(nb);
            na.addEdge(e);
        } else {
            WireNetwork n = na != null ? na : nb;
            if (n == null) {
                n = new WireNetwork(nextNetworkId++);
                networks.add(n);
            }
            n.addEdge(e);
            assigned.put(e.a.key, n);
            assigned.put(e.b.key, n);
        }
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("NetworkGraphStore{ver=").append(version)
                    .append(" nets=").append(networks.size()).append(" [");
            for (WireNetwork net : networks) sb.append(net).append(' ');
            return sb.append("]}").toString();
        } finally { lock.readLock().unlock(); }
    }
}
