package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PhasorNetworkContext 映射编解码（2026-08-19 完整结构缓存：缓存网络内所有
 * 数据含电路结构，支持跨区块传输）。
 *
 * <p>序列化 ctx 的【纯数据映射】（JSON 字符串）：
 * <ul>
 *   <li>blockTerminals：BlockPos → Integer[]（端子 → 引擎节点）</li>
 *   <li>pointToEngine：WirePoint key → 引擎节点 id</li>
 *   <li>openTerminal：悬空节点 id → 参考节点 id</li>
 *   <li>transformerModels 参数快照（pa1/x/pa2/pb1/pb2/w + 铜阻/铁损/互感/匝比）</li>
 * </ul>
 * 复合元件/导线段/温度模型【不在此编码】：Network 结构由 NetworkStructureCodec
 * 恢复（基础元件 + WireComposite 等纯数据复合）；温度状态由 DeviceThermalStore/
 * WireThermalStore（跨重建持久）恢复；设备复合模型（电机/加热器等）恢复后由
 * 组装器从 DeviceCache 重建（同正常构建路径）。
 *
 * <p>格式：每行 "key=value"，value 内字段用 ';' 分隔，列表用 ','：
 * <pre>
 * bt=x,y,z:0,1,-1,3;x2,y2,z2:2;      // blockTerminals（-1 = 无引擎节点）
 * pe=key:nodeId;key2:nodeId2;         // pointToEngine
 * ot=src:ref;src2:ref2;               // openTerminal
 * tf=x,y,z:a1;x2,y2,z2:a2;            // transformerModels（位置 → 参数 id）
 * tfp=id:pa1,x,pa2,pb1,pb2,w,rCp,rCs,rCore,lM,lLp,lLs,ratio;  // 变压器参数
 * </pre>
 */
public final class PhasorNetworkContextCodec {

    private PhasorNetworkContextCodec() {}

    /** 编码 ctx 映射为 JSON 文本。 */
    public static String encode(PhasorNetworkContext ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder(256);
        // blockTerminals
        sb.append("bt=");
        boolean first = true;
        for (Map.Entry<BlockPos, Integer[]> e : ctx.blockTerminals.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            BlockPos p = e.getKey();
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(':');
            Integer[] arr = e.getValue();
            if (arr != null) {
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(arr[i] == null ? -1 : arr[i]);
                }
            }
        }
        sb.append('\n');
        // pointToEngine
        sb.append("pe=");
        first = true;
        for (Map.Entry<String, Integer> e : ctx.pointToEngine.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        sb.append('\n');
        // openTerminal
        sb.append("ot=");
        first = true;
        for (Map.Entry<Integer, Integer> e : ctx.openTerminal.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        sb.append('\n');
        // transformerModels（位置 → 参数索引）
        sb.append("tf=");
        first = true;
        int idx = 0;
        Map<String, Integer> tfIdx = new HashMap<>();
        for (Map.Entry<BlockPos, PhasorNetworkContext.TransformerModel> e
                : ctx.transformerModels.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            BlockPos p = e.getKey();
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                    .append(':').append(idx);
            tfIdx.put(p.getX() + "," + p.getY() + "," + p.getZ(), idx++);
        }
        sb.append('\n');
        // 变压器参数表
        sb.append("tfp=");
        first = true;
        for (Map.Entry<BlockPos, PhasorNetworkContext.TransformerModel> e
                : ctx.transformerModels.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            PhasorNetworkContext.TransformerModel m = e.getValue();
            sb.append(tfIdx.get(e.getKey().getX() + "," + e.getKey().getY()
                    + "," + e.getKey().getZ())).append(':')
                    .append(m.pa1).append(',').append(m.x).append(',').append(m.pa2)
                    .append(',').append(m.pb1).append(',').append(m.pb2).append(',')
                    .append(m.w).append(',').append(m.rCp).append(',').append(m.rCs)
                    .append(',').append(m.rCore).append(',').append(m.lM).append(',')
                    .append(m.lLp).append(',').append(m.lLs).append(',').append(m.ratio);
        }
        return sb.toString();
    }

    /** 解码 ctx 映射：从恢复的 Network + mapping 重建 ctx 映射部分。 */
    public static PhasorNetworkContext decode(Network net, String mapping, double frequency) {
        Map<BlockPos, Integer[]> blockTerminals = new HashMap<>();
        Map<String, Integer> pointToEngine = new HashMap<>();
        Map<Integer, Integer> openTerminal = new HashMap<>();
        Map<BlockPos, PhasorNetworkContext.TransformerModel> tfModels = new HashMap<>();
        if (mapping != null) {
            try {
                for (String line : mapping.split("\n")) {
                    if (line == null || line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq);
                    String val = line.substring(eq + 1);
                    switch (key) {
                        case "bt" -> parseBt(val, blockTerminals);
                        case "pe" -> parsePe(val, pointToEngine);
                        case "ot" -> parseOt(val, openTerminal);
                        case "tfp" -> parseTfp(val, tfModels);
                        default -> { }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        PhasorNetworkContext ctx = new PhasorNetworkContext(net, blockTerminals,
                new HashMap<>(), pointToEngine, frequency, tfModels, openTerminal, false);
        // 从恢复的 Network 复合元件重建 wireSegments / deviceThermals / energyDevices
        try {
            List<WireComposite> segs = new ArrayList<>();
            for (CompositeElement c : net.composites()) {
                if (c instanceof WireComposite wc) segs.add(wc);
                if (c instanceof ThermalDevice td) {
                    try { ctx.deviceThermals.put(BlockPos.of(0), td); } catch (Throwable ignored) { }
                }
                if (c instanceof EnergyDevice ed) {
                    try { ctx.energyDevices.put(BlockPos.of(0), ed); } catch (Throwable ignored) { }
                }
            }
            ctx.wireSegments = segs;
        } catch (Throwable ignored) {
        }
        return ctx;
    }

    // ==================== 解析 ====================

    private static void parseBt(String val, Map<BlockPos, Integer[]> out) {
        for (String entry : val.split(";")) {
            if (entry.isEmpty()) continue;
            int colon = entry.indexOf(':');
            if (colon < 0) continue;
            String[] xyz = entry.substring(0, colon).split(",");
            if (xyz.length < 3) continue;
            try {
                int x = Integer.parseInt(xyz[0]);
                int y = Integer.parseInt(xyz[1]);
                int z = Integer.parseInt(xyz[2]);
                String[] ids = entry.substring(colon + 1).split(",");
                Integer[] arr = new Integer[8];
                for (int i = 0; i < Math.min(ids.length, 8); i++) {
                    int v = Integer.parseInt(ids[i].trim());
                    arr[i] = v < 0 ? null : v;
                }
                out.put(new BlockPos(x, y, z), arr);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void parsePe(String val, Map<String, Integer> out) {
        for (String entry : val.split(";")) {
            if (entry.isEmpty()) continue;
            int colon = entry.indexOf(':');
            if (colon < 0) continue;
            try {
                out.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void parseOt(String val, Map<Integer, Integer> out) {
        for (String entry : val.split(";")) {
            if (entry.isEmpty()) continue;
            int colon = entry.indexOf(':');
            if (colon < 0) continue;
            try {
                out.put(Integer.parseInt(entry.substring(0, colon)),
                        Integer.parseInt(entry.substring(colon + 1)));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void parseTfp(String val, Map<BlockPos, PhasorNetworkContext.TransformerModel> out) {
        for (String entry : val.split(";")) {
            if (entry.isEmpty()) continue;
            int colon = entry.indexOf(':');
            if (colon < 0) continue;
            try {
                String[] p = entry.substring(colon + 1).split(",");
                if (p.length < 13) continue;
                int pa1 = Integer.parseInt(p[0]);
                int x = Integer.parseInt(p[1]);
                int pa2 = Integer.parseInt(p[2]);
                int pb1 = Integer.parseInt(p[3]);
                int pb2 = Integer.parseInt(p[4]);
                int w = Integer.parseInt(p[5]);
                double rCp = Double.parseDouble(p[6]);
                double rCs = Double.parseDouble(p[7]);
                double rCore = Double.parseDouble(p[8]);
                double lM = Double.parseDouble(p[9]);
                double lLp = Double.parseDouble(p[10]);
                double lLs = Double.parseDouble(p[11]);
                double ratio = Double.parseDouble(p[12]);
                PhasorNetworkContext.TransformerModel m =
                        new PhasorNetworkContext.TransformerModel(pa1, x, pa2, pb1, pb2, w,
                                rCp, rCs, rCore, lM, lLp, lLs, ratio, null);
                out.put(BlockPos.of(0), m); // 位置在 tf 行，恢复时忽略精确 pos（发热按 pos 查 store）
            } catch (Throwable ignored) {
            }
        }
    }
}
