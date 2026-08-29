package com.hdf.cryptand.circuitsimulation.export;

import com.hdf.cryptand.circuitsimulation.cache.CachedNetwork;
import com.hdf.cryptand.circuitsimulation.cache.DeviceInfo;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 自定义 EDA 导出器（核心扩展，2026-08-22 用户需求：默认格式）。
 * <p>
 * 【直接导出虚拟电路】——只读 {@link NetworkWorld} 的 CachedNetwork 数据
 * （WirePoint / WireEdge / DeviceInfo / 端子 key 内的坐标），生成 test/ 前端
 * 可读的电路图 JSON：
 * <pre>
 *   { "version": 1,
 *     "components": [ { "type":"resistor","x":..,"y":..,"rotation":0,"params":{...} } ],
 *     "wires": [ { "points":[ {"x":..,"y":..}, ... ], "type":"copper" } ],
 *     "probes": [] }
 * </pre>
 * 坐标归并规则（与前端 Netlist 一致）：同一电气节点的端子/导线端点取相同画布
 * 坐标 → 前端自动归并为一个节点。要求：
 * <ul>
 *   <li>同一设备【不同端子】→ 不同引脚坐标（按端子索引布局，避免“设备内部
 *       导通被前端当短路”）；</li>
 *   <li>不同导线接【同一端子】（同方块同索引）→ 相同坐标（归并 ✓）。</li>
 * </ul>
 * 导线端点坐标 = 其所属设备端子的引脚坐标（前端靠“导线端点坐标 == 引脚坐标”
 * 建立电气连接）。无论设备类型是否登记，拓扑（导线/端子）都完整导出；设备
 * 类型由 DeviceInfo 提供（平台建网阶段登记），未登记 → 通用端子(connector)。
 * <p>
 * ⚠ 铁律：本类零 MC 依赖，不读取 Level / BlockEntity——只消费虚拟电路数据。
 */
public final class CustomEdaExporter implements SchematicExporter {

    /** test 前端画布网格 / 起点（与旧 neoforge 导出一致，画布足够不重叠） */
    private static final int GRID = 150;
    private static final int ORIGIN = 100;

    /** 前端元件默认引脚布局（rotation=0 时；2 引脚水平 ±50）。 */
    private static final int[] PIN_DX2 = {-50, 50};
    private static final int[] PIN_DY2 = {0, 0};
    /** 4 引脚（变压器类）：两列 × 两行 */
    private static final int[] PIN_DX4 = {-50, 50, -50, 50};
    private static final int[] PIN_DY4 = {-30, -30, 30, 30};

    @Override
    public SchematicFormat format() {
        return SchematicFormat.CUSTOM_EDA;
    }

    @Override
    public SchematicExportResult export(NetworkWorld world, SchematicExportRequest req) {
        if (world == null) return SchematicExportResult.fail(format(), "world is null");
        List<CachedNetwork> targets = selectNetworks(world, req == null ? null : req.networkKey());
        if (targets.isEmpty()) {
            return SchematicExportResult.ok(format(), emptyJson(),
                    "circuit_empty.json", 0, 0);
        }

        // 全部目标网络的器件+导线（画布坐标）
        List<Map<String, Object>> components = new ArrayList<>();
        List<Map<String, Object>> wires = new ArrayList<>();
        // 全局 min X/Z 归一化（多网络整体布局不重叠）
        int[] min = globalMin(targets);
        for (CachedNetwork net : targets) {
            ExportCtx ctx = new ExportCtx(world, net, min[0], min[1]);
            components.addAll(ctx.components);
            wires.addAll(ctx.wires);
        }
        String content = buildJson(components, wires);
        String fileName = (req != null && req.option("fileName", null) != null)
                ? req.option("fileName", null) : "circuit_export.json";
        return SchematicExportResult.ok(format(), content, fileName,
                components.size(), targets.size());
    }

    // ==================== 目标网络选择 ====================

    /** 选中网络：networkKey 为空 → 全部网络；否则先按端点 key（networkOf）
     *  定位，再按【网络 key 字符串】（String.valueOf(key)/名称）匹配。 */
    private static List<CachedNetwork> selectNetworks(NetworkWorld world, String networkKey) {
        if (networkKey == null || networkKey.isEmpty()) {
            return new ArrayList<>(world.allNetworks());
        }
        CachedNetwork byEnd = world.networkOf(networkKey);
        List<CachedNetwork> one = new ArrayList<>();
        if (byEnd != null) {
            one.add(byEnd);
            return one;
        }
        for (CachedNetwork cn : world.allNetworks()) {
            if (networkKey.equals(String.valueOf(cn.key()))
                    || (cn.name() != null && networkKey.equals(cn.name()))) {
                one.add(cn);
                break;
            }
        }
        return one;
    }

    /** 全部网络器件/导线的全局 min X/Z（画布归一化基准） */
    private static int[] globalMin(List<CachedNetwork> nets) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (CachedNetwork net : nets) {
            for (WirePoint p : net.points()) {
                int[] xyz = SchematicKeyParser.xyzOf(p.key);
                if (xyz == null) continue;
                minX = Math.min(minX, xyz[0]);
                minZ = Math.min(minZ, xyz[2]);
            }
        }
        if (minX == Integer.MAX_VALUE) { minX = 0; minZ = 0; }
        return new int[]{minX, minZ};
    }

    // ==================== 单网络导出上下文 ====================

    /** 单网络的导出上下文：按端子索引布局设备引脚、生成元件与导线。 */
    private static final class ExportCtx {
        final List<Map<String, Object>> components = new ArrayList<>();
        final List<Map<String, Object>> wires = new ArrayList<>();
        /** 端子 key → 画布引脚坐标（导线端点用） */
        final Map<String, int[]> pinByKey = new LinkedHashMap<>();
        /** 方块 key → 前端元件 type */
        final Map<String, String> typeByBlock = new TreeMap<>();
        /** 世界全局设备登记（键=方块，与网络生命周期解耦） */
        private final NetworkWorld world;

        ExportCtx(NetworkWorld world, CachedNetwork net, int minX, int minZ) {
            this.world = world;
            // 1) 设备方块（B 前缀）按端子索引分组
            Map<String, List<WirePoint>> byBlock = new TreeMap<>();
            for (WirePoint p : net.points()) {
                if (!SchematicKeyParser.isBlockKey(p.key)) continue;
                byBlock.computeIfAbsent(p.key.contains("#")
                                ? p.key.substring(0, p.key.indexOf('#')) : p.key,
                        k -> new ArrayList<>()).add(p);
            }
            // 2) 每台设备：中心坐标 + 引脚布局 + 元件
            int fbIndex = 0;
            for (var en : byBlock.entrySet()) {
                String blockKey = en.getKey();
                List<WirePoint> terms = en.getValue();
                terms.sort(Comparator.comparingInt(p -> SchematicKeyParser.termOf(p.key)));
                int[] xyz = SchematicKeyParser.xyzOf(blockKey);
                int cx = (xyz == null) ? ORIGIN + fbIndex * GRID : ORIGIN + (xyz[0] - minX) * GRID;
                int cy = (xyz == null) ? ORIGIN + fbIndex * GRID : ORIGIN + (xyz[2] - minZ) * GRID;
                fbIndex++;

                DeviceInfo info = world.deviceInfo(blockKey);
                String type = (info == null) ? null : info.type();
                int nTerms = terms.size();
                int n = nTerms;
                if (type != null && type.equals("transformer")) n = Math.max(n, 4);
                int[] pinDx, pinDy;
                if (n <= 1) { pinDx = new int[]{0}; pinDy = new int[]{0}; }
                else if (n == 2) { pinDx = PIN_DX2; pinDy = PIN_DY2; }
                else { pinDx = PIN_DX4; pinDy = PIN_DY4; }

                // 元件（type 未登记 → connector）
                String compType = (type == null) ? "connector" : type;
                Map<String, Object> comp = new LinkedHashMap<>();
                comp.put("type", compType);
                comp.put("x", cx);
                comp.put("y", cy);
                comp.put("rotation", 0);
                comp.put("params", (info == null || info.params() == null || info.params().isEmpty())
                        ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(info.params()));
                components.add(comp);
                typeByBlock.put(blockKey, compType);

                // 端子 → 引脚坐标
                for (int i = 0; i < terms.size(); i++) {
                    int t = SchematicKeyParser.termOf(terms.get(i).key);
                    int idx = (t < 0 || t >= pinDx.length) ? Math.min(i, pinDx.length - 1) : t;
                    pinByKey.put(terms.get(i).key, new int[]{
                            cx + pinDx[idx], cy + pinDy[idx]});
                }
            }
            // 3) 导线：端点坐标 = 设备引脚坐标（J 点/孤立用自身坐标）
            for (WireEdge e : net.wires()) {
                int[] pa = pinOf(e.a);
                int[] pb = pinOf(e.b);
                if (pa == null || pb == null) continue;
                List<Map<String, Object>> points = new ArrayList<>();
                points.add(point(pa[0], pa[1]));
                points.add(point(pb[0], pb[1]));
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("points", points);
                w.put("type", (e.rendererId == null || e.rendererId.isEmpty())
                        ? "copper" : e.rendererId);
                wires.add(w);
            }
        }

        /** 端子画布坐标（J 单纯端点/未布局点 → 按 key 顺序占位） */
        private int[] pinOf(WirePoint p) {
            int[] v = pinByKey.get(p.key);
            if (v != null) return v;
            int[] xyz = SchematicKeyParser.xyzOf(p.key);
            if (xyz != null) {
                // J 点/孤立端子：画布坐标 = 世界坐标独立一格
                int j = Math.max(0, Math.min(xyz[2] % 1000, 999));
                return pinByKey.computeIfAbsent(p.key,
                        k -> new int[]{ORIGIN + (xyz[0] * 7 + j), ORIGIN + (xyz[2] * 7)});
            }
            return null;
        }

        private static Map<String, Object> point(double x, double y) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", (int) x);
            m.put("y", (int) y);
            return m;
        }
    }

    // ==================== JSON 生成（零依赖手写） ====================

    private static String emptyJson() {
        return "{\"version\":1,\"components\":[],\"wires\":[],\"probes\":[]}";
    }

    private static String buildJson(List<Map<String, Object>> components,
                                    List<Map<String, Object>> wires) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":1,\"components\":").append(arr(components))
                .append(",\"wires\":").append(arr(wires))
                .append(",\"probes\":[]}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String arr(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(',');
            first = false;
            if (o instanceof Map<?, ?> m) sb.append(obj((Map<String, Object>) m));
            else if (o instanceof List<?> l) sb.append(arr(l));
            else if (o instanceof Number) sb.append(o);
            else if (o instanceof Boolean) sb.append(o);
            else sb.append(str(String.valueOf(o)));
        }
        return sb.append(']').toString();
    }

    private static String obj(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> en : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(str(en.getKey())).append(':');
            Object v = en.getValue();
            if (v instanceof Map<?, ?> vm) sb.append(obj((Map<String, Object>) vm));
            else if (v instanceof List<?> l) sb.append(arr(l));
            else if (v instanceof Number) sb.append(String.valueOf(v));
            else if (v instanceof Boolean) sb.append(v);
            else sb.append(str(v == null ? "" : String.valueOf(v)));
        }
        return sb.append('}').toString();
    }

    private static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}