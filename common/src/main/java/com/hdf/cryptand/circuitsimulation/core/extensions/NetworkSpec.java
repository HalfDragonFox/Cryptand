package com.hdf.cryptand.circuitsimulation.core.extensions;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络规格编解码（2026-08-30 手写紧凑文本，零 JSON 库依赖——核心跨程序可用）。
 * <p>
 * 元件规格（register 命令第三段，空格分隔多个元件；'|' 或 ';' 或 ',' 分隔）：
 * <pre>
 *   R &lt;a&gt; &lt;b&gt; &lt;ohm&gt;                电阻
 *   C &lt;a&gt; &lt;b&gt; &lt;farad&gt;             电容
 *   L &lt;a&gt; &lt;b&gt; &lt;henry&gt;             电感
 *   V &lt;a&gt; &lt;b&gt; &lt;amp&gt; &lt;phaseDeg&gt; &lt;ohm&gt;   交流电压源
 *   D &lt;a&gt; &lt;b&gt; &lt;volt&gt; &lt;ohm&gt;        直流电压源
 * </pre>
 * 节点由元件自动创建（编号 max+1）。结果序列化：节点 RMS 逗号分隔。
 */
public final class NetworkSpec {

    private NetworkSpec() {
    }

    /** 解析网络规格（节点自动扩展） */
    public static Network parse(String spec) {
        Network net = new Network();
        net.frequency = 0;
        if (spec == null || spec.isBlank()) return net;
        for (String token : spec.split("[;,|]")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split("\\s+");
            if (p.length < 3) continue;
            try {
                String type = p[0].toUpperCase();
                int a = Integer.parseInt(p[1]);
                int b = Integer.parseInt(p[2]);
                ensureNodes(net, a, b);
                double x = p.length > 3 ? Double.parseDouble(p[3]) : 0;
                double y = p.length > 4 ? Double.parseDouble(p[4]) : 0;
                double z = p.length > 5 ? Double.parseDouble(p[5]) : 0;
                switch (type) {
                    case "R" -> net.addElement(new Resistor(a, b, x));
                    case "C" -> net.addElement(new Capacitor(a, b, x));
                    case "L" -> net.addElement(new Inductor(a, b, x));
                    case "V" -> net.addElement(new AcVoltageSource(a, b, x, y,
                            Math.max(z, 1e-4)));
                    case "D" -> net.addElement(new DcVoltageSource(a, b, x,
                            Math.max(y, 1e-4)));
                    default -> { /* 未知类型跳过 */ }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return net;
    }

    private static void ensureNodes(Network net, int a, int b) {
        while (net.nodeCount() <= Math.max(a, b)) {
            net.addNode();
        }
    }

    /** 求解结果序列化：节点 RMS（V）逗号分隔；失败 "NaN" */
    public static String result(SolveResult r) {
        if (r == null || r.voltages == null) return "NaN";
        List<String> out = new ArrayList<>();
        for (double v : r.voltages) {
            out.add(Double.isFinite(v) ? String.format("%.6f", v) : "NaN");
        }
        return String.join(",", out);
    }
}
