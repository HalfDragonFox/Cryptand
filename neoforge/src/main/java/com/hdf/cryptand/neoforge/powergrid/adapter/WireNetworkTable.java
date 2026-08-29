package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.core.storage.AsyncOp;
import com.hdf.cryptand.core.storage.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 自管导线网络表（2026-08-14：IE 风格网络对象集合持久化，替代 WireGraphTable）。
 * <p>
 * 表 {@code wire_graph(a_key,b_key,r,therm_key,sag,len,color,self_placed,item_id)}
 * ——导线强类型列存储（PRIMARY KEY 双端点键），无 NBT：
 *   - {@link #saveAsync}：世界保存时把全局管理类（各网络自管导线）全量写入
 *     （异步批量，走 SqliteStore 单写线程队列，不阻塞主线程/求解线程池）。
 *   - {@link #restore}：进世界时同步读全部 → 重建各网络对象（跨会话拓扑
 *     立即可用）。
 * 旧库（无 color/self_placed/item_id 列）自动 ALTER 补列，缺省值安全。
 */
public final class WireNetworkTable {

    private final SqliteStore store;
    private final String name = "wire_graph"; // 沿用表名（旧库兼容）

    /** 保存去重（2026-08-14 保存世界卡住修复）：多维度 LevelEvent.Save/Unload
     *  各触发一次 saveAsync → 全量写排队积压 → 退出时 flush 等一堆写卡住。
     *  只允许【一个】排队写 + 保留最新快照：排队的任务写最新快照，后续调用
     *  只更新快照不排队（退出时最多 1 个全量写，且数据最新）。 */
    private final java.util.concurrent.atomic.AtomicBoolean saveQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile List<WireEdge> pendingEdges = java.util.Collections.emptyList();

    public WireNetworkTable(SqliteStore store) throws Exception {
        this.store = store;
        store.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + name + " ("
                            + "a_key TEXT NOT NULL, b_key TEXT NOT NULL, "
                            + "r REAL, therm_key TEXT, sag REAL, len REAL, "
                            + "color INTEGER, self_placed INTEGER, item_id TEXT, "
                            + "renderer_id TEXT, color_override INTEGER, "
                            + "PRIMARY KEY(a_key, b_key))")) {
                ps.executeUpdate();
            }
            // 旧库补列（列已存在 → ALTER 抛异常，忽略）
            // 2026-08-14 渲染参数引用化：新增 renderer_id/color_override；
            // 旧 sag/color 列保留（历史数据，新写不再填充）
            for (String col : new String[]{"color INTEGER", "self_placed INTEGER", "item_id TEXT",
                    "renderer_id TEXT", "color_override INTEGER"}) {
                try (PreparedStatement alt = c.prepareStatement(
                        "ALTER TABLE " + name + " ADD COLUMN " + col)) {
                    alt.executeUpdate();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 异步保存全量导线（单事务清空+全量写，不阻塞调用方）。
     *  去重：已有排队写 → 只更新最新快照（该任务会写最新）；无任务 → 排队。 */
    public AsyncOp saveAsync(WireNetworkManager mgr) {
        List<WireEdge> edges = mgr.edgeList(); // 主线程捕获快照（clear 前）
        boolean queued = saveQueued.compareAndSet(false, true);
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                "[WireStore] saveAsync edges={} saveQueuedWas={} queued={}",
                edges.size(), !queued, queued);
        if (queued) {
            // 无排队任务 → 用最新快照排队
            pendingEdges = edges;
            return store.asyncWrite((Connection c) -> {
                try {
                    saveNow(c, pendingEdges);
                } finally {
                    saveQueued.set(false);
                }
            });
        }
        // 已有排队任务 → 只更新快照（该任务将写最新数据），避免全量写积压
        pendingEdges = edges;
        return null;
    }

    /** 世界加载前重置保存去重标志（跨世界残留 true 会阻塞下次保存） */
    public void resetSaveQueued() {
        saveQueued.set(false);
        pendingEdges = java.util.Collections.emptyList();
    }

    /** 同步保存（世界卸载前 flush 用；立即落盘）。 */
    public AsyncOp saveSync(WireNetworkManager mgr) throws Exception {
        List<WireEdge> edges = mgr.edgeList();
        store.transaction((Connection c) -> saveNow(c, edges));
        return AsyncOp.done();
    }

    private static void saveNow(Connection c, List<WireEdge> edges) throws Exception {
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                "[WireStore] saveNow writing {} edges", edges.size());
        try (PreparedStatement del = c.prepareStatement("DELETE FROM wire_graph");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO wire_graph"
                             + "(a_key,b_key,r,therm_key,sag,len,color,self_placed,item_id,renderer_id,color_override) "
                             + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            del.executeUpdate();
            for (WireEdge e : edges) {
                ps.setString(1, e.a.key);
                ps.setString(2, e.b.key);
                ps.setDouble(3, e.resistance);
                ps.setString(4, e.temperatureKey);
                ps.setDouble(5, 0);             // 旧 sag 列（不再用）
                ps.setDouble(6, e.length);
                ps.setInt(7, 0);                // 旧 color 列（不再用）
                ps.setInt(8, e.selfPlaced ? 1 : 0);
                ps.setString(9, e.itemId);
                ps.setString(10, e.rendererId); // 渲染器引用 id
                ps.setInt(11, e.colorOverride); // 染色覆盖（0=渲染器默认）
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 同步恢复：读取全部导线 → 重建各网络对象。进世界时调用。 */
    public void restore(WireNetworkManager mgr) throws Exception {
        List<WireEdge> edges = new ArrayList<>();
        store.query((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a_key,b_key,r,therm_key,len,self_placed,item_id,renderer_id,color_override "
                            + "FROM wire_graph");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ak = rs.getString("a_key");
                    String bk = rs.getString("b_key");
                    if (ak == null || bk == null || ak.equals(bk)) continue;
                    Object selfObj = rs.getObject("self_placed");
                    boolean self = selfObj != null && ((Number) selfObj).intValue() == 1;
                    Object itemObj = rs.getObject("item_id");
                    String item = itemObj == null ? null : itemObj.toString();
                    Object renObj = rs.getObject("renderer_id");
                    String rendererId = renObj == null ? null : renObj.toString();
                    Object coObj = rs.getObject("color_override");
                    int colorOverride = coObj == null ? 0 : ((Number) coObj).intValue();
                    edges.add(new WireEdge(
                            new WirePoint(ak), new WirePoint(bk),
                            rs.getDouble("r"),
                            rs.getString("therm_key"),
                            rs.getDouble("len"),
                            rendererId, colorOverride, self, item));
                }
            }
            return null;
        });
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                "[WireStore] restore read {} edges", edges.size());
        mgr.clear();
        for (WireEdge e : edges) mgr.addEdge(e);
    }
}
