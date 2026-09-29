/**
 * ===== 导线网络管理器（2026-08-14 用户架构：IE 风格——导线交由各自网络管理） =====
 *
 * ⚠ 2026-08-19 算法下沉：全部图编排算法已迁至
 * {@link com.hdf.cryptand.circuitsimulation.netgraph.NetworkGraphStore}（common 纯核心，
 * 零 MC）。本类降为【薄壳】：保留单例、MC 职责（SavedData 保存/客户端同步/
 * 生命周期）与对外方法签名（外部调用点零改动），内部全委托 store。
 *
 * 主线程职责（铁律不变）：
 *   - 读原版世界 → 生成 WireEdge 纯数据 → addEdge（幂等，核心自动合并/新建）
 *   - 右键/烧毁/破坏 → removeEdge / removePoint / addPoint / addDevice
 *   - 核心自动完成：跨网络合并、分裂移入新网络、空网络收尾、version 推进。
 *
 * 线程安全：读写锁在 store 内（主线程写拓扑；求解/渲染/存档线程读）。
 */
package com.hdf.cryptand.neoforge.powergrid.network.wire;

import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;

import com.hdf.cryptand.circuitsimulation.netgraph.NetworkGraphStore;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netgraph.WireSegment;
import com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class WireNetworkManager {

    private static final WireNetworkManager INSTANCE = new WireNetworkManager();

    public static WireNetworkManager get() {
        return INSTANCE;
    }

    /** 缓存名（NetworkWorld.create 名；MainThreadInteractionManager 用） */
    public static final String CACHE_NAME = "mc-overworld";

    /** 核心图存储（算法全部下沉；读写锁在内） */
    private final NetworkGraphStore store = new NetworkGraphStore();
    /** 最近一次【非空】导线快照（SavedData 保存兜底：图被误清空时保留上次非空） */
    private volatile java.util.List<WireEdge> lastSnapshot = java.util.Collections.emptyList();
    /** 当前已加载世界（SavedData 保存触发用；onWorldLoad 设置 / onWorldUnload 清除） */
    private volatile net.minecraft.server.level.ServerLevel currentLevel;

    private WireNetworkManager() {
    }

    /** 访问核心图存储（求解/构建器直读；外部只读调用可绕薄壳） */
    public NetworkGraphStore store() {
        return store;
    }

    /** 最近一次非空导线快照（SavedData.save 兜底用） */
    public java.util.List<WireEdge> lastSnapshot() { return lastSnapshot; }

    /**
     * 设备点快照（SavedData 持久化用）：图上【全部方块端子】（B 点）。
     * <p>
     * ⚠ 2026-09-15 修复"重进世界后电路无功率 / 调参不更新"：此前只保存
     * "无导线连接的孤立端子"，理由是"有导线连接的端点由 edges 恢复时自动创建"。
     * 但 edges 恢复的只是【点的存在性 + 导线连通性】，丢掉了
     * 【同一设备的多个端子同属一个网络】这一信息 ⇒ 世界重载后同一设备的端子
     * 被拆到不同的导线分量（每个分量只含该设备的一个端子）⇒ 构建器的设备端子
     * 簇扩展拿不到成对端子 ⇒ 设备元件无法组装 ⇒ 无功率，且没有 ParamSource
     * ⇒ 调参永不刷新（用户"剪线重放就好"正是因为重走了合并路径）。
     * <p>
     * 因此改为保存全部设备端子：恢复时按方块分组 → {@link #restoreDevicePoints}
     * → addDevice 重建"同设备端子同网络"（端子间依然无边，不导电短路）。
     * <p>返回 key 列表（如 "BBlockPos{...}#0"）。
     */
    public java.util.List<String> devicePointKeys() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (WirePoint p : pointList()) {
            // 只有 B 前缀（方块端子）是设备点；J（接线端子）/导线中点跳过
            if (p.key == null || p.key.isEmpty() || p.key.charAt(0) != 'B') continue;
            out.add(p.key);
        }
        return out;
    }

    /**
     * 从存档恢复设备点（SavedData load 用）：按方块分组 → addDevice（幂等）。
     * @param keys 设备端子 key 列表（{@link #devicePointKeys} 的产物）
     */
    public boolean restoreDevicePoints(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) return false;
        boolean changed = false;
        java.util.Map<net.minecraft.core.BlockPos, java.util.Set<Integer>> byPos =
                new java.util.LinkedHashMap<>();
        for (String k : keys) {
            if (k == null || !k.startsWith("B")) continue;
            try {
                int hash = k.indexOf('#');
                if (hash < 0) continue;
                String posStr = k.substring(1, hash);
                int[] xyz = com.hdf.cryptand.neoforge.core.wire.WireKeyUtil.xyzOf(k);
                if (xyz == null) continue;
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                        xyz[0], xyz[1], xyz[2]);
                int term = Integer.parseInt(k.substring(hash + 1).trim());
                byPos.computeIfAbsent(pos, p -> new java.util.LinkedHashSet<>())
                        .add(term);
            } catch (Throwable ignored) {
            }
        }
        for (var en : byPos.entrySet()) {
            int count = 0;
            for (int t : en.getValue()) count = Math.max(count, t + 1);
            if (count <= 0) continue;
            if (addDevice(en.getKey(), count)) changed = true;
        }
        return changed;
    }

    /**
     * 设备端子收敛（幂等、便宜；主线程调用）：把图上【同一设备、却被导线分量
     * 拆散】的端子重新并回同一个网络。
     * <p>
     * 2026-09-15：与存档无关的兜底路径。{@code WireSavedData.load} 里的收敛只
     * 在世界加载读档时生效；若图是"内存保留后又被重建"的（不进 load），就漏掉了。
     * 这里直接用【当前图】的全部设备端子（{@link #devicePointKeys}）走一遍
     * {@link #restoreDevicePoints} —— 已经正确的图上是纯读操作、无任何变化，
     * 所以可以安全地周期性调用。
     * @return 是否发生真实变化
     */
    public boolean reconcileDeviceTerminals() {
        return restoreDevicePoints(devicePointKeys());
    }

    /** 保存时快照：仅当图非空时更新——图被误清空时保留上次非空，保存不丢 */
    public void snapshotForSave() {
        java.util.List<WireEdge> edges = edgeList();
        if (!edges.isEmpty()) lastSnapshot = edges;
    }

    // ==================== 查询（委托 store） ====================

    /** 全局结构版本（任何增删合并分裂 +1） */
    public long version() {
        return store.version();
    }

    public int nodeCount() {
        return store.nodeCount();
    }

    public int edgeCount() {
        return store.edgeCount();
    }

    /** 缓存实例 id（诊断）；对应网络世界缓存名 */
    public String instanceId() {
        return CACHE_NAME;
    }

    /** 网络世界句柄（"mc-overworld"；导出/元数据登记/诊断用；未创建 → null） */
    public com.hdf.cryptand.circuitsimulation.cache.NetworkWorld world() {
        return MainThreadInteractionManager.get().world();
    }

    /** 某方块所在导线段 key（温度/烧毁查询）；不在任何段 → null */
    public String segmentKeyAtBlock(net.minecraft.core.BlockPos pos) {
        if (pos == null) return null;
        for (WireSegment seg : segments()) {
            if (segmentHits(seg, pos)) return seg.key;
        }
        return null;
    }

    private static boolean segmentHits(WireSegment seg, net.minecraft.core.BlockPos pos) {
        if (seg == null || pos == null) return false;
        if (segmentPointHits(seg.endA, pos) || segmentPointHits(seg.endB, pos)) {
            return true;
        }
        for (WireEdge e : seg.edges) {
            if (segmentPointHits(e.a, pos) || segmentPointHits(e.b, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentPointHits(WirePoint p, net.minecraft.core.BlockPos pos) {
        if (p == null || p.key == null) return false;
        net.minecraft.core.BlockPos pp = WireKeyUtil.posOf(p.key);
        return pp != null && pp.equals(pos);
    }

    /** 端点是否存在（任一网络） */
    public boolean contains(WirePoint p) {
        return store.contains(p);
    }

    /** 端点所属网络（不存在返回 null） */
    public WireNetwork networkOf(WirePoint p) {
        return store.networkOf(p);
    }

    public WireNetwork networkOf(String key) {
        return store.networkOf(key);
    }

    /** 某端点邻接边（跨网络查；不存在返回空集） */
    public Set<WireEdge> adjacent(WirePoint p) {
        return store.adjacent(p);
    }

    /** 全部网络（副本；各自管理导线） */
    public List<WireNetwork> networks() {
        return store.networks();
    }

    /** 全量导线（全局视图：图同步/材质/渲染） */
    public List<WireEdge> edgeList() {
        return store.edgeList();
    }

    /** 全量端点（副本） */
    public List<WirePoint> pointList() {
        return store.pointList();
    }

    /** 全部分量（每个 = 该分量的节点集合；兼容旧 GraphOps.componentsDFS） */
    public List<Set<WirePoint>> components() {
        return store.components();
    }

    /** 全局连续段（各网络段汇总；统一温度/烧毁） */
    public List<WireSegment> segments() {
        return store.segments();
    }

    // ==================== 变更（委托 store；变化后标记存档） ====================

    /** 加入孤立点（设备悬空端子；新建单点网络）。幂等。 */
    public void addPoint(WirePoint p) {
        if (store.addPoint(p)) markDirty();
    }

    /**
     * 设备放置：把该方块全部声明端子作为【同一单点网络】加入（2026-08-15
     * 用户要求：每个实际元件/接线端子放下即创建一个网络，方便识别/导出/接线）。
     * <p>端子间【无边】（不导电短路）——仅网络对象层面同属一个分量：
     *   - 未接线也能被 /cryptand schematic 等工具识别（networkOf 命中）；
     *   - 接线时经 {@link #addEdge} 正常 merge 进导线网络；
     *   - 构建器（buildContextFromGraph 设备端子簇扩展）会把同方块悬空端子
     *     并入求解分量，设备元件完整建模。
     * 幂等：该方块任一端子已入图 → 跳过（设备已在图中）。
     */
    public boolean addDevice(net.minecraft.core.BlockPos pos, int terminalCount) {
        if (pos == null || terminalCount <= 0) return false;
        java.util.List<String> keys = new java.util.ArrayList<>(terminalCount);
        for (int t = 0; t < terminalCount; t++) keys.add("B" + pos + "#" + t);
        boolean changed = store.addDevice(keys);
        if (changed) markDirty();
        return changed;
    }

    /**
     * 设备端子标记清理（与 addDevice 登记对称）。⚠ 2026-08-26 新架构：
     * addDevice 仅把端子点挂入图（无独立"设备端子标记"状态）——本方法保留
     * 签名供旧调用方（NetOpExecutor/拆除路径）对称调用，内部 no-op。
     */
    public void unmarkDevice(net.minecraft.core.BlockPos pos) {
        // 新架构无独立标记状态（点由 removePoint 移除）；保留签名兼容旧调用方。
    }

    /**
     * 接线：加导线。核心自动完成合并/新建/幂等（见
     * {@link NetworkGraphStore#addEdge}）。变化后标记存档。
     */
    public void addEdge(WireEdge e) {
        if (store.addEdge(e)) markDirty();
    }

    /**
     * 拆线：核心自动完成分裂/移入新网络（见
     * {@link NetworkGraphStore#removeEdge}）。变化后标记存档。
     */
    public void removeEdge(WirePoint a, WirePoint b) {
        if (store.removeEdge(a, b)) markDirty();
    }

    /**
     * 设备拆除：移除端点及其全部导线。核心自动重新分组。变化后标记存档。
     */
    public void removePoint(WirePoint p) {
        if (store.removePoint(p)) markDirty();
    }

    /**
     * 全量重建（CEE/端子放置拆除即时收尾；2026-08-26 薄壳版）：图拓扑已由
     * 事件时同步完成，此处做一致性重分组 + 空网络收尾 + 标脏（不触碰 Level）。
     */
    public void reconstruct(net.minecraft.server.level.ServerLevel level) {
        try {
            store.compactNetworks();
            store.ensureComponentNetworks();
            markDirty();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 批量坐标迁移（Sable 装配搬移物理化前）：moves = {old → new}。对每个
     * 仍存在于图中的方块执行 {@link #remapBlockPos}。
     */
    public void preMoveUpdate(net.minecraft.server.level.ServerLevel level,
                              java.util.Map<net.minecraft.core.BlockPos,
                                      net.minecraft.core.BlockPos> moves) {
        if (moves == null || moves.isEmpty()) return;
        int n = 0;
        for (var en : moves.entrySet()) {
            net.minecraft.core.BlockPos from = en.getKey();
            net.minecraft.core.BlockPos to = en.getValue();
            if (from == null || to == null || from.equals(to)) continue;
            if (remapBlockPos(level, from, to)) n++;
        }
        if (n > 0) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[WireStore] preMoveUpdate remapped={} blocks", n);
        }
    }

    /**
     * 单方块坐标迁移（Sable 亚层移除回迁世界坐标/装配搬移）：图中该方块全部
     * 端子点(B/J 前缀) remap 到新坐标——旧点移除、连边按原参数重连到新端点、
     * 孤立端子以新坐标单点重建。返回是否有端点被迁移。
     */
    public boolean remapBlockPos(net.minecraft.server.level.ServerLevel level,
                                 net.minecraft.core.BlockPos from,
                                 net.minecraft.core.BlockPos to) {
        if (from == null || to == null || from.equals(to)) return false;
        try {
            java.util.List<WirePoint> oldPts = new java.util.ArrayList<>();
            for (WirePoint p : pointList()) {
                if (p.key == null) continue;
                char c = p.key.charAt(0);
                if (c != 'B' && c != 'J') continue;
                net.minecraft.core.BlockPos pp = WireKeyUtil.posOf(p.key);
                if (from.equals(pp)) oldPts.add(p);
            }
            if (oldPts.isEmpty()) return false;
            java.util.Set<WireEdge> affected = new java.util.LinkedHashSet<>();
            for (WirePoint p : oldPts) {
                affected.addAll(adjacent(p));
            }
            int removed = 0;
            for (WirePoint p : oldPts) {
                if (store.removePoint(p)) removed++;
            }
            // 孤立端子（原无边）→ 新坐标单点重建
            for (WirePoint p : oldPts) {
                WirePoint np = remapPoint(p, to);
                if (np != null) store.addPoint(np);
            }
            // 连边重连到新端点（addEdge 自动把不在图中的新端点并入网络）
            for (WireEdge e : affected) {
                WirePoint na = remapPoint(e.a, to);
                WirePoint nb = remapPoint(e.b, to);
                store.addEdge(new WireEdge(
                        na != null ? na : e.a,
                        nb != null ? nb : e.b,
                        e.resistance, e.temperatureKey, e.length));
            }
            if (removed > 0) markDirty();
            return removed > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** remap 一个点：key 坐标部分 from → to（null = 非 from 方块点/格式错） */
    private static WirePoint remapPoint(WirePoint p, net.minecraft.core.BlockPos to) {
        if (p == null || p.key == null) return null;
        char c = p.key.charAt(0);
        if (c != 'B' && c != 'J') return null;
        int hash = p.key.indexOf('#');
        String tail = hash >= 0 ? p.key.substring(hash) : "";
        return new WirePoint(c + to.toString() + tail);
    }

    /**
     * 网络拆合一致性收尾（2026-08-16 网表相关操作类执行器用）：移除空网络对象
     * （无任何端点）。网络拆合后可能残留空网络（如 removeEdge 分裂后旧网络全部
     * 节点被移走）；保持 networks() 列表干净，防止空网络无限累积。幂等。
     */
    public void compactNetworks() {
        store.compactNetworks();
    }

    /** 清空（世界卸载）——保留带边清空诊断（MC 侧职责） */
    public void clear() {
        store.clear();
        // 诊断（2026-08-14 保存丢失定位）：谁在有导线时清空了图
        try {
            int edgeN = edgeCount();
            if (edgeN > 0) {
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < Math.min(st.length, 10); i++) {
                    sb.append("\n  at ").append(st[i]);
                }
                // ⚠ 2026-08-30 审计 #21：改 debug 级——onWorldLoad 每次重进世界时
                // 旧图非空（onWorldUnload 故意不清图保留重载）→ 每次正常世界
                // 切换都打 warn+堆栈 = 误报刷屏。真异常清空仍可版本号/诊断定位。
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.debug(
                        "[WireStore] CLEAR with {} edges! stack:{}", edgeN, sb);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 生命周期（2026-08-14 改原版 SavedData 保存） ====================

    /** 边变化后标记 SavedData 待写（NeoForge dataStorage.save 只写 dirty 条目；
     *  LevelEvent.Save 触发晚于 dataStorage.save，故在变更点直接标记最可靠） */
    private void markDirty() {
        net.minecraft.server.level.ServerLevel lvl = currentLevel;
        if (lvl == null) return;
        try {
            WireSavedData.get(lvl).setDirty();
        } catch (Throwable ignored) {
        }
    }

    /** 世界加载：从原版 SavedData（NBT 随世界存档）恢复自管网络对象。
     *  仅主世界处理（图全局共享，由主世界统一保存/恢复；下界/末地跳过
     *  避免多维度重复写入与竞态 AccessDenied）。 */
    public void onWorldLoad(net.minecraft.server.level.ServerLevel level) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            this.currentLevel = null;
            return;
        }
        this.currentLevel = level;
        clear();
        try {
            WireSavedData.get(level); // 触发 SavedData load → addEdge 恢复
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[WireGraph] onWorldLoad restored: {} nodes, {} edges",
                    nodeCount(), edgeCount());
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[WireGraph] onWorldLoad restore failed", t);
        }
    }

    /** 世界卸载：不手动保存也不清图（2026-08-14 SavedData 方案）——
     *  SavedData 随世界存档自动落盘（保存时读 mgr/lastSnapshot 兜底）；
     *  图由下次 onWorldLoad 统一 clear+restore 重建。此前 SQLite 方案因
     *  退回主菜单时图被清空（edgeCount 6→0）导致保存空 → 丢线。 */
    public void onWorldUnload() {
        this.currentLevel = null;
        try {
            snapshotForSave();
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[WireStore] onWorldUnload edges={} (SavedData, 图保留待重载重建)",
                    edgeCount());
        } catch (Throwable ignored) {
        }
    }

    /** 世界保存：快照 + 标记 SavedData 待写（随世界存档自动落盘；仅主世界） */
    public void onWorldSave(net.minecraft.server.level.ServerLevel level) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        try {
            snapshotForSave();
            WireSavedData sd = WireSavedData.get(level);
            sd.setDirty();
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[WireStore] onWorldSave edges={} (SavedData, setDirty called)", edgeCount());
        } catch (Throwable ignored) {
        }
    }

    /** 每 tick 增量转换（委托 PowerGridWireConverter）：原版导线表 → 自管网络 */
    public void syncFromWorld(java.util.List<org.patryk3211.powergrid.electricity.sim.special.TransmissionLine> worldWires) {
        PowerGridWireConverter.convertWires(worldWires);
    }

    @Override
    public String toString() {
        return "WireNetworkManager(store=" + store + ")";
    }
}
