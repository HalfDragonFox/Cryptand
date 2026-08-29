package com.hdf.cryptand.circuitsimulation.comm;

import com.hdf.cryptand.circuitsimulation.cache.CachedNetwork;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager;
import com.hdf.cryptand.circuitsimulation.export.SchematicExportRequest;
import com.hdf.cryptand.circuitsimulation.export.SchematicExportResult;
import com.hdf.cryptand.circuitsimulation.export.SchematicExporters;
import com.hdf.cryptand.circuitsimulation.export.SchematicFormat;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通信请求处理器（2026-08-22 通信组件：总接口管理类转发到具体实例的执行端）。
 * <p>
 * 把统一请求（{@link CommRequest}）**按实例方式（worldName）转发到具体实例**
 * （{@link NetworkWorld}）：实例生命周期经 {@link NetworkWorldManager} +
 * {@link WorldFactory}（服务端注入 executor/link），图数据/消息/查询映射到
 * 对应世界的方法。实例完全隔离——处理器是唯一接触实例的执行入口。
 * <p>
 * 跨通信 data 契约（可序列化，ProtoCommCodec 支持）：
 * <pre>
 *   ADD_DEVICE   keys=List&lt;String&gt;
 *   ADD_EDGE     a=b;key, b=b;key, r=double?, len=double?
 *   REMOVE_EDGE  a,b（端点 key）
 *   REMOVE_POINT / ADD_POINT   p=key
 *   POST_DESTROY p=key（点破坏）
 *   POST_TOPOLOGY structural=boolean
 *   POST_SOLVE   forceInit=boolean
 *   SUBMIT       kind="DESTROY|SPLIT_MERGE|REBUILD|SOLVE", networkKey=str
 * </pre>
 */
public final class CommRequestHandler {

    private final NetworkWorldManager manager;
    private final WorldFactory factory;

    public CommRequestHandler(NetworkWorldManager manager, WorldFactory factory) {
        this.manager = manager;
        this.factory = factory;
    }

    /** 处理一个请求（总接口管理类/端点/直调入口共用） */
    public CommResponse handle(CommRequest req) {
        if (req == null) return CommResponse.fail(-1, null, "null request");
        long id = req.id;
        CommOp op = req.op;
        String world = req.worldName;
        try {
            return switch (op) {
                case CREATE_WORLD -> {
                    if (world.isEmpty()) yield CommResponse.fail(id, op, "world name required");
                    NetworkWorld w = NetworkWorld.create(world,
                            factory == null ? null : factory.linkFor(world),
                            factory == null ? null : factory.executorFor(world));
                    yield CommResponse.ok(id, op, summary(w));
                }
                case GET_WORLD -> CommResponse.ok(id, op, manager.getWorld(world) != null);
                case REMOVE_WORLD -> {
                    manager.removeWorld(world);
                    yield CommResponse.ok(id, op);
                }
                case LIST_WORLDS -> CommResponse.ok(id, op, new ArrayList<>(manager.names()));
                case PING -> CommResponse.ok(id, op, "pong");
                default -> {
                    NetworkWorld w = manager.getWorld(world);
                    yield w == null ? CommResponse.fail(id, op, "world not found: " + world)
                            : dispatch(w, req);
                }
            };
        } catch (Throwable t) {
            return CommResponse.fail(id, op, String.valueOf(t));
        }
    }

    /** 把数据/消息/查询操作转发到具体实例 */
    private CommResponse dispatch(NetworkWorld w, CommRequest req) {
        return switch (req.op) {
            // ===== 图数据（建网，直接写入） =====
            case ADD_DEVICE -> {
                List<String> keys = strList(req.get("keys"));
                yield CommResponse.ok(req.id, req.op, w.addDevice(keys));
            }
            case ADD_EDGE -> CommResponse.ok(req.id, req.op, w.addEdge(edge(req)));
            case REMOVE_EDGE -> CommResponse.ok(req.id, req.op,
                    w.removeEdge(point(req, "a"), point(req, "b")));
            case REMOVE_POINT -> CommResponse.ok(req.id, req.op, w.removePoint(point(req, "p")));
            case ADD_POINT -> CommResponse.ok(req.id, req.op, w.addPoint(point(req, "p")));

            // ===== 图算法 =====
            case ENSURE_COMPONENTS -> CommResponse.ok(req.id, req.op, w.ensureComponentNetworks());
            case COMPACT -> CommResponse.ok(req.id, req.op, w.compactNetworks());
            case CLEAR_GRAPH -> {
                w.clearGraph();
                yield CommResponse.ok(req.id, req.op);
            }

            // ===== 消息（异步 → 核心） =====
            case POST_DESTROY -> {
                String p = str(req.get("p"));
                w.postDestroy(p == null ? "GLOBAL" : p, p == null ? null : p);
                yield CommResponse.ok(req.id, req.op);
            }
            case POST_TOPOLOGY -> {
                boolean structural = bool(req.get("structural"), false);
                w.postTopology(worldKeyOf(w, str(req.get("networkKey"))), structural);
                yield CommResponse.ok(req.id, req.op);
            }
            case POST_SOLVE -> {
                boolean force = bool(req.get("forceInit"), false);
                w.postSolve(worldKeyOf(w, str(req.get("networkKey"))), force);
                yield CommResponse.ok(req.id, req.op);
            }
            case SUBMIT -> {
                String kind = str(req.get("kind"));
                NetOpKind k = kind == null ? NetOpKind.SPLIT_MERGE : NetOpKind.valueOf(kind);
                w.submit(k, worldKeyOf(w, str(req.get("networkKey"))), req.get("data"));
                yield CommResponse.ok(req.id, req.op);
            }

            // ===== 查询 =====
            case QUERY_SUMMARY -> CommResponse.ok(req.id, req.op, summary(w));
            case QUERY_NETWORKS -> CommResponse.ok(req.id, req.op, networks(w));

            // ===== 导出原理图（核心扩展；直接导出虚拟电路，不检测 BE） =====
            case EXPORT_SCHEMATIC -> {
                SchematicFormat fmt = SchematicFormat.fromId(str(req.get("format")));
                SchematicExportRequest ereq = SchematicExportRequest.of(
                        w.name(), str(req.get("networkKey"))).withFormat(fmt);
                SchematicExportResult r = SchematicExporters.export(w, ereq);
                yield r.ok ? CommResponse.ok(req.id, req.op, r.toMap())
                           : CommResponse.fail(req.id, req.op, r.error);
            }

            default -> CommResponse.fail(req.id, req.op, "unhandled " + req.op);
        };
    }

    // ===== 映射辅助 =====

    private static Object worldKeyOf(NetworkWorld w, String netKey) {
        return (netKey == null || netKey.isEmpty()) ? w.name() : netKey;
    }

    private static WireEdge edge(CommRequest req) {
        WirePoint a = point(req, "a");
        WirePoint b = point(req, "b");
        double r = dbl(req.get("r"), 0.0);
        double len = dbl(req.get("len"), 1.0);
        return new WireEdge(a, b, r, null, len, "copper", 0, true, "wire");
    }

    private static WirePoint point(CommRequest req, String k) {
        Object v = req.get(k);
        String key = v instanceof String s ? s : String.valueOf(v);
        return new WirePoint(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : (v == null ? null : String.valueOf(v));
    }

    private static boolean bool(Object v, boolean dft) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return dft;
    }

    private static double dbl(Object v, double dft) {
        if (v instanceof Number n) return n.doubleValue();
        return dft;
    }

    /** 世界摘要（QUERY_SUMMARY / CREATE_WORLD 返回） */
    private static Map<String, Object> summary(NetworkWorld w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.name());
        m.put("version", w.version());
        m.put("nodes", w.nodeCount());
        m.put("edges", w.edgeCount());
        m.put("networks", w.networkCount());
        m.put("components", w.components().size());
        return m;
    }

    /** 世界网络列表（QUERY_NETWORKS） */
    private static List<Object> networks(NetworkWorld w) {
        List<Object> out = new ArrayList<>();
        for (CachedNetwork cn : w.allNetworks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", String.valueOf(cn.key()));
            m.put("name", cn.name());
            m.put("nodes", cn.nodeCount());
            m.put("edges", cn.edgeCount());
            out.add(m);
        }
        return out;
    }
}