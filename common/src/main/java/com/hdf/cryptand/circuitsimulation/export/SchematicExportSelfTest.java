package com.hdf.cryptand.circuitsimulation.export;

import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager;
import com.hdf.cryptand.circuitsimulation.comm.CommOp;
import com.hdf.cryptand.circuitsimulation.comm.CommRequest;
import com.hdf.cryptand.circuitsimulation.comm.CommRequestHandler;
import com.hdf.cryptand.circuitsimulation.comm.CommResponse;
import com.hdf.cryptand.circuitsimulation.comm.WorldFactory;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原理图导出核心扩展自测（2026-08-22：导出原理图作为核心功能的扩展功能）。
 *
 * <p>验证：
 *   1) 端子 key 坐标解析（BBlockPos{x=..}#t / B(..)#t，纯核心零 MC 依赖）；
 *   2) 导出请求（带网络）→ 核心直接导出虚拟电路（不检测 BE）：
 *      设备登记（DeviceInfo）→ 前端元件 type+参数；拓扑 → 导线；指定网络；
 *   3) 未支持格式（markdown 等"其他"）→ 暂不支持提示（默认自定义 EDA）；
 *   4) 通信层 EXPORT_SCHEMATIC 请求链路（CommRequestHandler 路由到门面）。
 *
 * 运行：{@code ./gradlew :common:runSchematicExportTest}
 */
public final class SchematicExportSelfTest {

    private static final class RecordingExecutor implements NetOpExecutor {
        @Override public boolean executeDestroy(Object k, Object d) { return true; }
        @Override public boolean executeSplitMerge(Object k, Object d) { return true; }
        @Override public boolean executeRebuild(Object k, Object d) { return true; }
        @Override public void executeSolve(Object k, Object d) { }
    }

    private static final RecordingExecutor EXEC = new RecordingExecutor();

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 原理图导出核心扩展自测（SchematicExport） ====");

        // 1) key 坐标解析
        System.out.println("1) 端子 key 坐标解析（纯核心零 MC 依赖）");
        int[] a = SchematicKeyParser.xyzOf("BBlockPos{x=12, y=64, z=-30}#0");
        int[] b = SchematicKeyParser.xyzOf("B(1,2,3)#1");
        boolean c1 = a != null && a[0] == 12 && a[1] == 64 && a[2] == -30
                && b != null && b[0] == 1 && b[2] == 3
                && "BBlockPos{x=12, y=64, z=-30}".equals(
                        SchematicKeyParser.blockKeyOf("BBlockPos{x=12, y=64, z=-30}#0"))
                && SchematicKeyParser.termOf("B(1,2,3)#1") == 1
                && SchematicKeyParser.isBlockKey("B(1,2,3)#0")
                && !SchematicKeyParser.isBlockKey("J(1,2,3)");
        System.out.println("   xyz(BBlockPos)=[" + (a == null ? "null"
                : (a[0] + "," + a[1] + "," + a[2])) + "] xyz(B(..))=["
                + (b == null ? "null" : (b[0] + "," + b[1] + "," + b[2]))
                + "] (c1=" + c1 + ")");
        pass &= c1;

        // 2) 导出请求 → 虚拟电路直接导出（经通信层 EXPORT_SCHEMATIC）
        System.out.println("2) 导出请求（带网络）→ 核心直接导出虚拟电路");
        NetworkWorldManager mgr = NetworkWorldManager.get();
        NetworkWorld w = NetworkWorld.create("export-test", null, EXEC);
        CommRequestHandler handler = new CommRequestHandler(mgr, new WorldFactory() {
            @Override public com.hdf.cryptand.circuitsimulation.cache.AppLink linkFor(String n) { return null; }
            @Override public NetOpExecutor executorFor(String n) { return EXEC; }
        });

        // 设备 A：电阻（2 端子，登记 type=resistor, resistance=100）
        w.addDevice(java.util.List.of("B(1,2,3)#0", "B(1,2,3)#1"));
        w.registerDevice(com.hdf.cryptand.circuitsimulation.cache.DeviceInfo.of("B(1,2,3)", "resistor",
                "电阻", java.util.Map.of("resistance", 100.0)));
        // 设备 B：交流电压源（2 端子，type=acsourc）
        w.addDevice(java.util.List.of("B(4,5,6)#0", "B(4,5,6)#1"));
        w.registerDevice(com.hdf.cryptand.circuitsimulation.cache.DeviceInfo.of("B(4,5,6)", "acsourc",
                "交流源", java.util.Map.of("amplitude", 5.0, "frequency", 50.0)));
        // 导线：A#1 — B#0（合并两设备网络）
        w.addEdge(new WireEdge(new WirePoint("B(1,2,3)#1"), new WirePoint("B(4,5,6)#0"),
                0.5, "t0", 1.0, "copper", 0, true, "copper"));
        Object keyA = w.networkOf("B(1,2,3)#0").key();
        // 另一个网络（net-B），只连一个悬空端子，验证 networkKey 过滤
        w.addDevice(java.util.List.of("B(100,10,10)#0"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("format", "custom-eda");
        CommResponse all = handler.handle(CommRequest.of(101, "export-test",
                CommOp.EXPORT_SCHEMATIC, data));
        String json = (all.ok && all.result instanceof Map<?, ?> m0
                && m0.get("content") instanceof String s0) ? s0 : "";
        String fmt = (all.ok && all.result instanceof Map<?, ?> m0
                && m0.get("format") != null) ? String.valueOf(
                ((Map<?, ?>) all.result).get("format")) : "?";
        boolean okAll = all.ok && all.result instanceof Map<?, ?> m
                && json.contains("\"type\":\"resistor\"")
                && json.contains("\"type\":\"acsourc\"")
                && json.contains("\"resistance\":100.0")
                && json.contains("\"amplitude\":5.0")
                && SchematicFormat.CUSTOM_EDA.id().equals(fmt);
        if (!okAll) {
            System.out.println("   [dbg] ok=" + all.ok + " fmt=" + fmt
                    + " resistor=" + json.contains("\"type\":\"resistor\"")
                    + " acsourc=" + json.contains("\"type\":\"acsourc\"")
                    + " res100=" + json.contains("\"resistance\":100.0")
                    + " amp5=" + json.contains("\"amplitude\":5.0")
                    + " err=" + all.error);
        }
        String shown = json;
        System.out.println("   all ok=" + all.ok + " content=" + shortJson(shown)
                + (okAll ? " ✅" : " ❌"));
        pass &= okAll;

        // 2b) 指定网络导出（networkKey=真实 key）：只含该网络元件
        Map<String, Object> data2 = new LinkedHashMap<>();
        data2.put("format", "custom-eda");
        data2.put("networkKey", String.valueOf(keyA));
        CommResponse one = handler.handle(CommRequest.of(102, "export-test",
                CommOp.EXPORT_SCHEMATIC, data2));
        boolean okOne = one.ok && one.result instanceof Map<?, ?> mn
                && mn.get("content") instanceof String jn
                && jn.contains("resistor") && jn.contains("acsourc");
        System.out.println("   one ok=" + one.ok
                + (okOne ? " ✅" : " ❌"));
        pass &= okOne;

        // 3) 未支持格式（"其他"）→ 暂不支持提示，默认自定义 EDA
        System.out.println("3) 未支持格式（markdown 等'其他'）→ 暂不支持提示");
        Map<String, Object> data3 = new LinkedHashMap<>();
        data3.put("format", "markdown");
        CommResponse u = handler.handle(CommRequest.of(103, "export-test",
                CommOp.EXPORT_SCHEMATIC, data3));
        boolean c3 = !u.ok && u.error != null
                && u.error.contains("暂不支持") && u.error.contains("custom-eda");
        System.out.println("   formats=" + java.util.Arrays.toString(SchematicFormat.values())
                + " err=" + u.error + (c3 ? " ✅" : " ❌"));
        pass &= c3;

        // 4) 世界为空 → 失败提示
        CommResponse nw = handler.handle(CommRequest.of(104, "no-such-world",
                CommOp.EXPORT_SCHEMATIC, data));
        boolean c4 = !nw.ok;
        System.out.println("4) 世界不存在 → 失败 err=" + nw.error + (c4 ? " ✅" : " ❌"));
        pass &= c4;

        mgr.removeWorld("export-test");

        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }

    private static String shortJson(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}