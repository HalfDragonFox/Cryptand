/**
 * ===== 电路原理图导出器（2026-08-11） =====
 *
 * 根据游戏世界现有的接线，把每个电气网络（物理连通分量）导出为电路原理图：
 *   - 节点：方块端子（pos#terminal）
 *   - 元件：源/电阻/电容/电感/变压器（类型 + 值）
 *   - 连接：元件两端节点
 *
 * 数据来源：复用 Cryptand 相量求解的网络构建（PhasorNetworkBuilder），
 * 因此导出的原理图与【实际求解的电路】完全一致（不是手动推断）。
 *
 * 输出格式：Markdown + Mermaid（graph LR），可直接在 VS Code / GitHub 渲染。
 * 触发：命令 /cryptand schematic（服务端）。
 */

package com.hdf.cryptand.neoforge.core.export;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.MutualInductor;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback;
import com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireKeyUtil;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.HashMap;
import java.util.Map;

public final class CircuitSchematicExporter {

    private CircuitSchematicExporter() {}

    /** 导出世界全部电路的原理图（Markdown + Mermaid）。 */
    public static String export(Level level) {
        if (level == null) return "# 电路原理图\n\n（无世界 Level）\n";
        StringBuilder sb = new StringBuilder();
        sb.append("# Cryptand 电路原理图\n\n");
        // ⚠ 自管模式（2026-08-13 完全接管）：枚举【自管图分量】（转换后的真实
        //   拓扑），每个分量 buildContextFromGraph 构建——与原版网络无关。
        //   （原版 WORLD_NETS 快照在自管模式不含导线端点 → 原版构建会失真）
        if (PowerGridWireConverter.isEnabled() && WireNetworkManager.get().nodeCount() > 0) {
            int idx = 0;
            for (java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp
                    : WireNetworkManager.get().components()) {
                String seedKey = null;
                net.minecraft.core.BlockPos seedPos = null;
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                    if (WireKeyUtil.isBlock(p.key)) {
                        seedKey = p.key;
                        seedPos = PhasorNetworkBuilder.pointPosOfPublic(p.key);
                        break;
                    }
                }
                if (seedKey == null || seedPos == null) continue; // 纯悬挂点分量
                double freq = MultimeterDebug.getFrequencyHzFromGraph(level, seedPos);
                PhasorNetworkContext ctx;
                try {
                    ctx = PhasorNetworkBuilder.buildContextFromGraph(level, seedKey, freq);
                } catch (Throwable t) {
                    continue;
                }
                // ⚠ 2026-08-15 修复：自管构建 nodeToEngine 恒空（原版节点映射已
                // 删除）——空判定必须看 pointToEngine（自管端点映射），否则自管
                // 模式下全部分量被跳过 → 导出恒空（“无法识别网络”）。
                if (ctx == null || ctx.network == null
                        || (ctx.nodeToEngine.isEmpty() && ctx.pointToEngine.isEmpty())) {
                    continue;
                }
                sb.append(exportContext(ctx, idx++, freq));
            }
            if (idx == 0) {
                sb.append("（世界中暂无已接线的电气网络）\n");
            }
            return sb.toString();
        }
        // 物理去重：同一物理连通分量（PowerGrid 可能分裂成多网络对象）只导出一次
        java.util.Set<String> donePos = new java.util.HashSet<>();
        int netCount = 0;
        for (ElectricalNetwork net : PhasorWriteback.WORLD_NETS) {
            if (net == null || net.isEmpty()) continue;
            boolean dup = true;
            for (INode in : net.getNodes()) {
                if (in instanceof OwnedFloatingNode ofn
                        && ofn.endpoint instanceof BlockWireEndpoint bep) {
                    if (donePos.add(bep.getPos() + "#" + bep.getTerminal())) {
                        dup = false;
                    }
                }
            }
            if (dup) continue;
            double freq;
            try {
                freq = MultimeterDebug.getNetworkFrequencyHz(net);
            } catch (Throwable t) {
                freq = 0;
            }
            PhasorNetworkContext ctx;
            try {
                ctx = PhasorNetworkBuilder.buildContextFromNetwork(level, net, freq);
            } catch (Throwable t) {
                continue;
            }
            if (ctx == null || ctx.network == null || ctx.nodeToEngine.isEmpty()) continue;
            sb.append(exportContext(ctx, netCount++, freq));
        }
        if (netCount == 0) {
            sb.append("（世界中暂无已接线的电气网络）\n");
        }
        return sb.toString();
    }

    /**
     * 查找指定方块位置所在的电气网络（物理连通分量；无则 null）。
     * 用于"选定电路网络并导出"：玩家看向/指定坐标 → 定位其网络。
     */
    public static ElectricalNetwork findNetworkAt(Level level, net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return null;
        for (ElectricalNetwork net : PhasorWriteback.WORLD_NETS) {
            if (net == null || net.isEmpty()) continue;
            for (INode in : net.getNodes()) {
                if (in instanceof OwnedFloatingNode ofn
                        && ofn.endpoint instanceof BlockWireEndpoint bep
                        && bep.getPos().equals(pos)) {
                    return net;
                }
            }
        }
        return null;
    }

    /**
     * 导出单个【选定电路网络】为原理图（Markdown + Mermaid）。
     * 复用 {@link #exportContext}——导出的原理图与实际求解电路完全一致。
     */
    public static String exportNetwork(Level level, ElectricalNetwork net, int idx) {
        if (level == null || net == null) {
            return "# 电路原理图\n\n（未选中网络）\n";
        }
        double freq;
        try {
            freq = MultimeterDebug.getNetworkFrequencyHz(net);
        } catch (Throwable t) {
            freq = 0;
        }
        PhasorNetworkContext ctx;
        try {
            ctx = PhasorNetworkBuilder.buildContextFromNetwork(level, net, freq);
        } catch (Throwable t) {
            return "# 电路原理图\n\n（网络构建失败）\n";
        }
        if (ctx == null || ctx.network == null || ctx.nodeToEngine.isEmpty()) {
            return "# 电路原理图\n\n（网络无节点/未接线）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Cryptand 电路原理图（选定网络 #").append(idx).append("）\n\n");
        sb.append(exportContext(ctx, idx, freq));
        return sb.toString();
    }

    /**
     * 导出指定方块所在网络（选定电路网络）；该位置无网络返回 null。
     * <p>2026-08-15 自管分支（修复“指令无法识别网络”）：原版 findNetworkAt
     * 扫 WORLD_NETS（原版 ElectricalNetwork 节点）——自管模式下原版网络维护已
     * 禁（addWire/merge/line.tick 停）→ 网络不含设备节点 → 永远找不到 → 导出
     * 失败。自管模式改从 WireNetworkManager 定位 pos 所在自管分量 →
     * buildContextFromGraph 构建（与【实际求解的电路】完全一致）。
     */
    public static String exportNetworkAt(Level level, net.minecraft.core.BlockPos pos) {
        if (PowerGridWireConverter.isEnabled() && WireNetworkManager.get().nodeCount() > 0) {
            return exportNetworkAtFromGraph(level, pos);
        }
        ElectricalNetwork net = findNetworkAt(level, pos);
        if (net == null) return null;
        return exportNetwork(level, net, 0);
    }

    /** 自管模式：导出 pos 所在自管分量（该位置不在任何图分量 → null）。 */
    private static String exportNetworkAtFromGraph(Level level, net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            var mgr = WireNetworkManager.get();
            String hitKey = null;
            // 先按端子 key 精确查（设备端子点 "Bpos#t"，t=0..7 覆盖全部面）
            for (int t = 0; t < 8; t++) {
                String key = "B" + pos + "#" + t;
                if (mgr.networkOf(key) != null) {
                    hitKey = key;
                    break;
                }
            }
            // 兜底：遍历图中所有方块点找该位置（导线可能接在任意端子上）
            if (hitKey == null) {
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : mgr.pointList()) {
                    if (!WireKeyUtil.isBlock(p.key)) continue;
                    net.minecraft.core.BlockPos bp =
                            PhasorNetworkBuilder.pointPosOfPublic(p.key);
                    if (pos.equals(bp)) {
                        hitKey = p.key;
                        break;
                    }
                }
            }
            if (hitKey == null) return null;
            double freq = MultimeterDebug.getFrequencyHzFromGraph(level, pos);
            PhasorNetworkContext ctx;
            try {
                ctx = PhasorNetworkBuilder.buildContextFromGraph(level, hitKey, freq);
            } catch (Throwable t) {
                return null;
            }
            // ⚠ 2026-08-15 修复：自管构建 nodeToEngine 恒空 → 空判定必须看
            // pointToEngine（自管端点映射）；elements 有但两端点不全时也可能
            // 映射非空（至少 seed 点）→ 正常导出。
            if (ctx == null || ctx.network == null
                    || (ctx.nodeToEngine.isEmpty() && ctx.pointToEngine.isEmpty())) {
                return "# Cryptand 电路原理图（选定网络 @" + pos.toShortString()
                        + "）\n\n（该位置所在网络无节点/未接线）\n";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# Cryptand 电路原理图（选定网络 @").append(pos.toShortString())
                    .append("）\n\n");
            sb.append(exportContext(ctx, 0, freq));
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 导出单个网络（ctx）为原理图段落。 */
    private static String exportContext(PhasorNetworkContext ctx, int idx, double freq) {
        Map<Integer, String> names = nodeNames(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("## 网络 #").append(idx);
        if (freq > 0) sb.append("　频率 ").append(String.format("%.1f", freq)).append(" Hz");
        sb.append("\n\n");

        // 节点清单
        sb.append("**节点（").append(names.size()).append("）：**  ")
                .append(String.join("、", names.values())).append("\n\n");

        // Mermaid 图
        sb.append("```mermaid\ngraph LR\n");
        for (Element el : ctx.network.elements()) {
            if (el instanceof IdealTransformer it) {
                sb.append("    ").append(nm(names, it.a1)).append(" --- |")
                        .append("变压器 n=").append(fmt(it.ratio)).append("| ")
                        .append(nm(names, it.a2)).append("\n");
                sb.append("    ").append(nm(names, it.b1)).append(" --- |")
                        .append("副边| ").append(nm(names, it.b2)).append("\n");
            } else if (el instanceof MutualInductor mi) {
                // 互感：两绕组各自画出（对称，绕组1/绕组2）
                sb.append("    ").append(nm(names, mi.a1)).append(" --- |")
                        .append("绕组1 L=").append(fmt(mi.l1)).append("| ")
                        .append(nm(names, mi.a2)).append("\n");
                sb.append("    ").append(nm(names, mi.b1)).append(" --- |")
                        .append("绕组2 L=").append(fmt(mi.l2)).append(" M=").append(fmt(mi.m))
                        .append("| ").append(nm(names, mi.b2)).append("\n");
            } else {
                int a = el.nodeA(), b = el.nodeB();
                if (a == b) continue;
                sb.append("    ").append(nm(names, a)).append(" --- |")
                        .append(label(el)).append("| ").append(nm(names, b)).append("\n");
            }
        }
        sb.append("```\n\n");

        // 文本元件清单
        sb.append("**元件：**\n");
        for (Element el : ctx.network.elements()) {
            if (el instanceof IdealTransformer it) {
                sb.append("- ").append(label(el)).append("：初级 ")
                        .append(nm(names, it.a1)).append(" — ").append(nm(names, it.a2))
                        .append("，次级 ").append(nm(names, it.b1)).append(" — ")
                        .append(nm(names, it.b2)).append("\n");
            } else if (el instanceof MutualInductor mi) {
                sb.append("- ").append(label(el)).append("：绕组1 ")
                        .append(nm(names, mi.a1)).append(" — ").append(nm(names, mi.a2))
                        .append("，绕组2 ").append(nm(names, mi.b1)).append(" — ")
                        .append(nm(names, mi.b2)).append("\n");
            } else {
                int a = el.nodeA(), b = el.nodeB();
                if (a == b) continue;
                sb.append("- ").append(label(el)).append("：")
                        .append(nm(names, a)).append(" — ").append(nm(names, b)).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 引擎节点 id → 显示名（pos#terminal；无端点的内部节点 → n<id>）。
     *  <p>2026-08-15 修复：自管构建 nodeToEngine 恒空 → 合并 pointToEngine
     *  （自管端点 key = "Bpos#term"/"Jpos"）生成节点名，否则自管导出全部显示
     *  n<id> 无法辨识。 */
    private static Map<Integer, String> nodeNames(PhasorNetworkContext ctx) {
        Map<Integer, String> names = new HashMap<>();
        for (Map.Entry<OwnedFloatingNode, Integer> e : ctx.nodeToEngine.entrySet()) {
            OwnedFloatingNode ofn = e.getKey();
            String name = "n" + e.getValue();
            if (ofn.endpoint instanceof BlockWireEndpoint bep) {
                name = "(" + bep.getPos().toShortString() + ")" + "#" + bep.getTerminal();
            }
            names.put(e.getValue(), name);
        }
        for (Map.Entry<String, Integer> e : ctx.pointToEngine.entrySet()) {
            names.putIfAbsent(e.getValue(), pointName(e.getKey(), e.getValue()));
        }
        return names;
    }

    /** 自管端点 key → 显示名（"Bpos#term" → "(x, y, z)#t"；J 点 → "J(x, y, z)"） */
    private static String pointName(String key, int id) {
        try {
            net.minecraft.core.BlockPos p = WireKeyUtil.posOf(key);
            if (p == null) return "n" + id;
            int t = WireKeyUtil.termOf(key);
            return t >= 0
                    ? "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")#" + t
                    : "J(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
        } catch (Throwable ignored) {
            return "n" + id;
        }
    }

    /** Mermaid 节点：引号包裹（节点名含括号/#）。 */
    private static String nm(Map<Integer, String> names, int id) {
        String n = names.get(id);
        return n == null ? "n" + id : "\"" + n + "\"";
    }

    /** 元件 → 显示标签（类型 + 值）。 */
    private static String label(Element el) {
        double[] p = el.params();
        switch (el.type()) {
            case RESISTOR:        return "R " + fmt(p.length > 0 ? p[0] : 0);
            case CAPACITOR:       return "C " + fmt(p.length > 0 ? p[0] : 0) + "F";
            case INDUCTOR:        return "L " + fmt(p.length > 0 ? p[0] : 0) + "H";
            case DC_VOLTAGE_SOURCE: return "V=" + fmt(p.length > 0 ? p[0] : 0) + "V";
            case AC_VOLTAGE_SOURCE: return "V~" + fmt(p.length > 0 ? p[0] : 0) + "V";
            case CURRENT_SOURCE:  return "I " + fmt(p.length > 0 ? p[0] : 0) + "A";
            case IDEAL_TRANSFORMER: return "变压器 n=" + fmt(((IdealTransformer) el).ratio);
            case MUTUAL_INDUCTOR: {
                MutualInductor mi = (MutualInductor) el;
                return "互感 L1=" + fmt(mi.l1) + " L2=" + fmt(mi.l2) + " M=" + fmt(mi.m);
            }
            default:              return el.type().name();
        }
    }

    /** 数值格式化（k/M/µ 单位）。 */
    private static String fmt(double v) {
        if (Math.abs(v) >= 1e6) return String.format("%.2fM", v / 1e6);
        if (Math.abs(v) >= 1e3) return String.format("%.2fk", v / 1e3);
        if (v != 0 && Math.abs(v) < 1e-3) return String.format("%.2fµ", v * 1e6);
        return String.format("%.4g", v);
    }
}
