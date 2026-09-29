package com.hdf.cryptand.neoforge.powergrid.persistence;

import com.hdf.cryptand.circuitsimulation.db.NetlistDatabase;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.NetworkCacheRecord;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContextCodec;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络求解缓存管理器（2026-08-15 用户要求：网络采用缓存机制——直接缓存到
 * SQLite；没有对应缓存则重新建立寻找）。
 * <p>
 * <b>交互约定（用户强调）：仅在世界【加载】与【保存】时交互 SQLite，
 * 正常游戏期间零数据库交互（纯内存）：</b>
 * <ul>
 *   <li><b>世界加载</b> {@link #loadAll}：从 {@code network_cache} 表一次性
 *       读入内存（signature → 记录）——之后游戏期间只查内存。</li>
 *   <li><b>游戏期间</b> {@link #tryApply} / {@link #recordSolved}：纯内存
 *       查/记——求解轮次中命中缓存 → 恢复节点电压跳过重建/求解；未命中 →
 *       正常重建 + 求解（"重新建立寻找"）；成功结果记入内存待存表。</li>
 *   <li><b>世界保存</b> {@link #saveAllAsync}：内存待存表【单事务全量替换】
 *       写回 SQLite（清空+全量写 = 已消失网络的陈旧缓存自动清理）。</li>
 * </ul>
 * <p>
 * 缓存键 {@link #signatureOf} = 分量内容稳定哈希（排序端点键 + 排序边参数 +
 * 频率）——跨会话确定，重启后同一网络同一状态命中同一缓存。
 * <p>
 * 线程安全：全部 ConcurrentHashMap——主线程（世界加载/保存）与求解调度线程
 * （100Hz 轮次）并发读写安全；SQLite 写走单写线程异步队列不阻塞。
 */
public final class NetworkCacheManager {

    /** 已加载缓存（世界加载时读入；signature → 记录）。只读快照语义。 */
    private static final ConcurrentHashMap<String, NetworkCacheRecord> CACHE =
            new ConcurrentHashMap<>();

    /** 本会话最新结果（求解/命中后记录；世界保存时批量落库）。 */
    private static final ConcurrentHashMap<String, NetworkCacheRecord> LATEST =
            new ConcurrentHashMap<>();

    private NetworkCacheManager() {}

    // ==================== 世界加载 / 保存（仅这两处交互 SQLite） ====================

    /** 世界加载：从网表库一次性读入全部网络缓存（同步；之后游戏期间只查内存）。 */
    public static void loadAll(NetlistDatabase db) {
        CACHE.clear();
        LATEST.clear();
        if (db == null || db.isClosed()) return;
        try {
            int n = 0;
            for (NetworkCacheRecord rec : db.loadAllNetworkCaches()) {
                if (rec != null && rec.signature() != null) {
                    CACHE.put(rec.signature(), rec);
                    n++;
                }
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[NetCache] loaded {} network cache rows", n);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[NetCache] loadAll failed", t);
        }
    }

    /** 世界保存（ESC/自动保存）：内存待存表 → SQLite 单事务全量替换
     *  （异步，不阻塞主线程防卡顿）。待存表为空 → 跳过（保留旧缓存）。 */
    public static void saveAllAsync(NetlistDatabase db) {
        if (db == null || db.isClosed() || LATEST.isEmpty()) return;
        try {
            List<NetworkCacheRecord> list = new ArrayList<>(LATEST.values());
            db.saveNetworkCachesAsync(list);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[NetCache] save {} cache rows queued (async)", list.size());
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[NetCache] saveAllAsync failed", t);
        }
    }

    /** 退出世界：同步保存（立即落盘，再关库）。待存表为空 → 跳过。 */
    public static void saveAllSync(NetlistDatabase db) {
        if (db == null || db.isClosed() || LATEST.isEmpty()) return;
        try {
            List<NetworkCacheRecord> list = new ArrayList<>(LATEST.values());
            db.saveNetworkCachesSync(list);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[NetCache] save {} cache rows (sync)", list.size());
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[NetCache] saveAllSync failed", t);
        }
    }

    /** 清空（世界卸载） */
    public static void clear() {
        CACHE.clear();
        LATEST.clear();
    }

    // ==================== 游戏期间（纯内存） ====================

    /**
     * 分量内容稳定签名：排序端点键 + 排序边参数 + 频率 → FNV-1a 64 位。
     * 跨会话确定（不依赖网络 id/图版本）→ 重启后同一网络同一状态命中同一缓存。
     */
    public static String signatureOf(Collection<WirePoint> comp, double freq) {
        List<String> edges = new ArrayList<>();
        List<String> points = new ArrayList<>();
        for (WirePoint p : comp) {
            if (p != null && p.key != null) points.add(p.key);
        }
        points.sort(String::compareTo);
        for (WireEdge e : WireNetworkManager.get().edgeList()) {
            if (e == null || e.a == null || e.b == null) continue;
            if (comp.contains(e.a) && comp.contains(e.b)) {
                String a = e.a.key, b = e.b.key;
                if (a.compareTo(b) > 0) { String t = a; a = b; b = t; }
                edges.add(a + "|" + b + "|" + e.resistance + "|" + e.length
                        + "|" + nz(e.rendererId) + "|" + e.colorOverride + "|" + nz(e.itemId));
            }
        }
        edges.sort(String::compareTo);
        long h = 0xcbf29ce484222325L;
        for (String s : edges) h = fnv(h, s);
        for (String s : points) h = fnv(h, s);
        h = fnv(h, "f=" + freq);
        return Long.toHexString(h);
    }

    private static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    /**
     * 纯内存查缓存：命中 → 恢复节点电压（构造 SolveResult），调用方跳过求解。
     * 未命中（无缓存/节点数不符/非线性/参数已变）→ null → 调用方重新建立寻找
     * （正常 buildContextFromGraph + solve）。
     */
    public static SolveResult tryApply(String sig, PhasorNetworkContext ctx) {
        if (sig == null || ctx == null) return null;
        NetworkCacheRecord rec = CACHE.get(sig);
        if (rec == null) return null;
        Network net = ctx.network;
        if (net == null || rec.nodeCount() != net.nodeCount()) return null;
        // 非线性网络（伪时域强制重解）或参数已变（paramVersion>0）→ 不用缓存
        if (net.nonlinearCount() > 0) return null;
        if (ctx.paramVersion.get() != 0) return null;
        try {
            double[] v = parseDoubles(rec.voltages());
            if (v == null || v.length != net.nodeCount()) return null;
            Complex[] c = null;
            if (rec.complex() != null && !rec.complex().isEmpty()) {
                double[] reim = parseDoubles(rec.complex());
                if (reim != null && reim.length == net.nodeCount() * 2) {
                    c = new Complex[net.nodeCount()];
                    for (int i = 0; i < net.nodeCount(); i++) {
                        c[i] = new Complex(reim[i * 2], reim[i * 2 + 1]);
                    }
                }
            }
            // 2026-08-21 用户要求"AC/DC 同一套系统"：统一相量 mode（DC 也是 0Hz
            // 相量，存档也存 COMPLEX_AC）
            SolveMode mode = SolveMode.COMPLEX_AC;
            SolveResult res = new SolveResult(v, c, true, 0, 0, mode);
            // 节点电压同步写回（元件温度/热计算直接读 node.voltage）
            for (com.hdf.cryptand.circuitsimulation.model.Node n : net.nodes()) {
                if (n.id < v.length) n.voltage = v[n.id];
            }
            return res;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 记录本会话最新求解结果（纯内存；世界保存时批量落库）。
     *  2026-08-19 完整结构缓存：同时记录电路结构（NetworkStructureCodec 二进制
     *  base64）与 ctx 映射（blockTerminals/pointToEngine/openTerminal/段/波形
     *  JSON）——跨区块加载后直接恢复完整电路（跳过 buildContextFromGraph）。 */
    /** 结构编码失败诊断节流（2026-09-15） */
    private static volatile long STRUCT_DBG_LAST;

    public static void recordSolved(String sig, long networkId, String seedKey,
                                    double freq, PhasorNetworkContext ctx,
                                    SolveResult res) {
        if (sig == null || res == null || !res.converged || res.voltages == null) return;
        Network net = ctx == null ? null : ctx.network;
        if (net == null) return;
        String vStr = joinDoubles(res.voltages);
        String cStr = null;
        if (res.complex != null) {
            double[] reim = new double[res.complex.length * 2];
            for (int i = 0; i < res.complex.length; i++) {
                reim[i * 2] = res.complex[i].re;
                reim[i * 2 + 1] = res.complex[i].im;
            }
            cStr = joinDoubles(reim);
        }
        String structure = null;
        String mapping = null;
        try {
            byte[] bytes = com.hdf.cryptand.circuitsimulation.compute.NetworkStructureCodec.encode(net);
            structure = java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable t) {
            // 2026-09-15：原先静默吞异常 ⇒ "某些网络的电路结构从来没进过 SQLite"
            //  完全不可见（表现为"进世界没功率/必须重建/跨区块恢复失效"）。
            //  NetworkStructureCodec.writeComposite 目前只支持 WireComposite /
            //  ResistorModel / CapacitorModel / InductorModel 四种，电机/变压器/灯/
            //  加热器等一律抛 IOException。改为可见诊断（5s 节流）。
            long nowS = System.currentTimeMillis();
            if (nowS - STRUCT_DBG_LAST >= 5000) {
                STRUCT_DBG_LAST = nowS;
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                        "[NetCache] struct-encode FAILED (not persisted) sig={} err={}",
                        sig, String.valueOf(t));
            }
        }
        try {
            mapping = PhasorNetworkContextCodec
                    .encode(ctx);
        } catch (Throwable ignored) {
        }
        LATEST.put(sig, new NetworkCacheRecord(
                sig, networkId, seedKey, freq,
                res.voltages.length, res.mode.name(), vStr, cStr,
                structure, mapping, System.currentTimeMillis()));
    }

    /**
     * 完整结构恢复（2026-08-19 用户需求：缓存网络内所有数据含电路结构，支持
     * 跨区块传输）：命中 → 反序列化 Network（NetworkStructureCodec）+ ctx 映射
     * （PhasorNetworkContextCodec）→ 重建完整 ctx（含元件/端子/映射/波形），
     * 调用方跳过 buildContextFromGraph。未命中/无结构 → null。
     */
    public static PhasorNetworkContext tryRestoreStructure(String sig) {
        if (sig == null) return null;
        NetworkCacheRecord rec = CACHE.get(sig);
        if (rec == null || rec.structure() == null) return null;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(rec.structure());
            Network net = com.hdf.cryptand.circuitsimulation.compute.NetworkStructureCodec.decode(bytes);
            if (net == null || net.nodeCount() != rec.nodeCount()) return null;
            if (rec.mapping() == null) return null;
            PhasorNetworkContext ctx =
                    PhasorNetworkContextCodec
                            .decode(net, rec.mapping(), rec.frequency());
            return ctx;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== 工具 ====================

    private static double[] parseDoubles(String csv) {
        if (csv == null || csv.isEmpty()) return null;
        try {
            String[] parts = csv.split(",");
            double[] out = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String joinDoubles(double[] a) {
        StringBuilder sb = new StringBuilder(a.length * 12);
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    /** 已加载缓存数（诊断） */
    public static int loadedCount() {
        return CACHE.size();
    }

    /** 待保存结果数（诊断） */
    public static int latestCount() {
        return LATEST.size();
    }
}
