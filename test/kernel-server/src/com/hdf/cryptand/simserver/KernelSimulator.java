package com.hdf.cryptand.simserver;

/**
 * 内核仿真桥接器（独立服务版）：把前端（test/）发来的【网表元素列表】翻译成 Cryptand
 * 内核的 {@link com.hdf.cryptand.circuitsimulation.model.Network}，用内核求解器
 * （RealMnaSolver = DC/时域；ComplexMnaSolver = AC 相量）求解，返回节点电压、
 * 元件/导线电流功率、温度、能量与探针波形。
 *
 * <p>本文件与 neoforge 模块 {@code simserver.KernelSimulator} 同源（独立服务版），
 * 请求/响应协议完全一致；引擎源码在编译时从 common 模块直接引用，保证与模组一致。
 *
 * <p>请求元素 kinds：resistor / capacitor / inductor / vsource / acsource /
 * isource / diode / wire（switch 由前端映射为电阻）。节点 0 为地。
 * 电感带内阻时前端已展开为 inductor + resistor（内部节点）。
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DiodeElement;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KernelSimulator {

    /** 环境温度（°C），与前端温度模型一致 */
    private static final double AMBIENT = 20.0;

    private static double num(JsonObject o, String k, double def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsDouble();
    }
    private static boolean numId(String s) {
        return s.matches("-?\\d+");
    }

    /** 元件温度/能量状态 */
    private static final class CompState {
        final String idKey;
        final boolean idNumeric;
        final double r0, alpha, g, c;
        double temp = AMBIENT, energy = 0, vPrev = 0, iPrev = 0;
        Resistor resistor = null;          // 温度系数/烧毁时 setResistance

        CompState(String idKey, double r0, double alpha, double g, double c) {
            this.idKey = idKey;
            this.idNumeric = numId(idKey);
            this.r0 = Math.max(r0, 1e-9);
            this.alpha = alpha;
            this.g = g;
            this.c = c;
        }
        double rAtTemp() {
            double r = r0;
            if (alpha != 0) r = r0 * (1 + alpha * (temp - AMBIENT));
            return Math.max(r, 1e-9);
        }
    }

    /** 导线段（一段 = 一个内核电阻元件） */
    private static final class WireSeg {
        final Resistor resistor;
        final double r;
        WireSeg(Resistor resistor, double r) { this.resistor = resistor; this.r = r; }
    }

    /** 导线（多段按 id 聚合） */
    private static final class WireState {
        final String idKey;
        final double ratedCurrent, g, c, maxTemp;
        final List<WireSeg> segs = new ArrayList<>();
        String type = "copper", name = "导线";
        double rSum = 0, length = 0, temp = AMBIENT, energy = 0;
        boolean burned = false;

        WireState(JsonObject e) {
            this.idKey = e.has("id") ? String.valueOf(e.get("id")) : "-1";
            this.ratedCurrent = num(e, "ratedCurrent", 0);
            this.g = num(e, "thermalConductance", 2.0);
            this.c = num(e, "heatCapacity", 5.0);
            this.maxTemp = num(e, "maxTemp", 200.0);
            if (e.has("type")) this.type = e.get("type").getAsString();
            if (e.has("name")) this.name = e.get("name").getAsString();
            if (e.has("length")) this.length = e.get("length").getAsDouble();
        }
        double current(double[] v) {
            if (v == null || segs.isEmpty()) return 0;
            WireSeg s = segs.get(0);
            return (v[s.resistor.nodeA()] - v[s.resistor.nodeB()]) / s.resistor.resistance;
        }
        double power(double[] v) {
            double i = current(v);
            return i * i * rSum;
        }
    }

    private KernelSimulator() {}

    /** 主入口：执行一次内核仿真并返回结果 JSON */
    public static JsonObject simulate(JsonObject req) {
        String mode = req.has("mode") ? req.get("mode").getAsString() : "dc";
        double duration = num(req, "duration", 0.05);
        double step = num(req, "step", 1e-4);
        double frequency = num(req, "frequency", 0);
        int nodeCount = req.has("nodeCount") ? req.get("nodeCount").getAsInt() : 0;
        int ground = req.has("groundNode") ? req.get("groundNode").getAsInt() : 0;

        JsonArray elArr = req.has("elements") ? req.getAsJsonArray("elements") : new JsonArray();
        JsonArray probeArr = req.has("probes") ? req.getAsJsonArray("probes") : new JsonArray();
        int[] probes = new int[probeArr.size()];
        for (int i = 0; i < probes.length; i++) probes[i] = probeArr.get(i).getAsInt();

        // ---- 构建内核网络 ----
        Network net = new Network();
        net.groundNode = ground;
        net.dt = step;
        net.frequency = frequency;
        for (int i = 0; i < nodeCount; i++) net.addNode();

        Map<String, CompState> comps = new LinkedHashMap<>();
        List<WireState> wires = new ArrayList<>();
        Map<String, WireState> wireById = new LinkedHashMap<>();

        for (JsonElement je : elArr) {
            JsonObject e = je.getAsJsonObject();
            String kind = e.get("kind").getAsString();
            int a = e.get("a").getAsInt();
            int b = e.get("b").getAsInt();
            String key = e.has("id") ? String.valueOf(e.get("id")) : null;

            switch (kind) {
                case "resistor": {
                    double r0 = Math.max(num(e, "value", 1000), 1e-9);
                    CompState st = new CompState(key == null ? "-1" : key, r0,
                            num(e, "tempCoef", 0), num(e, "thermalConductance", 0),
                            num(e, "heatCapacity", 0));
                    st.resistor = new Resistor(a, b, st.rAtTemp());
                    net.addElement(st.resistor);
                    if (key != null) comps.put(key, st);
                    break;
                }
                case "capacitor":
                    net.addElement(new Capacitor(a, b, Math.max(num(e, "value", 0), 0)));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                case "inductor":
                    net.addElement(new Inductor(a, b, Math.max(num(e, "value", 0), 1e-9)));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                case "vsource":
                    net.addElement(new DcVoltageSource(a, b, num(e, "value", 0), num(e, "series", 0.01)));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                case "wavesource": {   // 波形源：DC/SINE/SQUARE/TRIANGLE/SAWTOOTH
                    WaveformType wf = WaveformType.SINE;
                    try { wf = WaveformType.valueOf(e.has("waveform") ? e.get("waveform").getAsString() : "SINE"); } catch (Exception ignored) { }
                    net.addElement(new WaveformSource(a, b, true, wf,
                            num(e, "amplitude", 5), num(e, "frequency", 50),
                            num(e, "phase", 0), num(e, "duty", 0.5), num(e, "offset", 0),
                            num(e, "series", 0.01)));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                }
                case "acsource":
                    // 时域瞬态：WaveformSource(SINE)，按 net.time 取瞬时值
                    net.addElement(new WaveformSource(a, b, true, WaveformType.SINE,
                            num(e, "amplitude", 5), num(e, "frequency", 50), 0, 0.5, 0,
                            num(e, "series", 0.01)));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                case "isource":
                    net.addElement(new CurrentSource(a, b, num(e, "value", 0)));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                case "transformer": {   // 理想变压器：V2 = n·V1
                    int a1 = e.get("a1").getAsInt(), a2 = e.get("a2").getAsInt();
                    int b1 = e.get("b1").getAsInt(), b2 = e.get("b2").getAsInt();
                    int k = e.has("k") ? e.get("k").getAsInt() : net.addNode().id;
                    double ratio = num(e, "ratio", 1);
                    net.addElement(new IdealTransformer(a1, a2, b1, b2, k, ratio));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                }
                case "diode":
                    net.addElement(new DiodeElement(a, b, num(e, "vth", 0.7), 0.05, 1_000_000.0));
                    if (key != null) comps.put(key, new CompState(key, 1, 0, 0, 0));
                    break;
                case "wire": {
                    double r = Math.max(num(e, "r", 0), 1e-9);
                    WireState ws = wireById.get(key);
                    if (ws == null) {
                        ws = new WireState(e);
                        wireById.put(key, ws);
                        wires.add(ws);
                    }
                    ws.rSum += r;
                    ws.length += num(e, "length", 0);
                    WireSeg seg = new WireSeg(new Resistor(a, b, r), r);
                    ws.segs.add(seg);
                    net.addElement(seg.resistor);
                    break;
                }
                default:
                    break; // 未知元素忽略
            }
        }

        JsonObject out = new JsonObject();
        try {
            if (mode.equals("ac") || frequency > 0) {
                SolveResult r = new ComplexMnaSolver().solve(net);
                out.addProperty("solver", "ComplexMnaSolver");
                out.addProperty("mode", "ac");
                fillResult(out, r, r.voltages, comps, wires);
            } else if (mode.equals("tran") || mode.equals("step")) {
                out.addProperty("solver", "RealMnaSolver");
                out.addProperty("mode", "tran");
                runTransient(net, duration, step, probes, out, comps, wires);
            } else {
                SolveResult r = new RealMnaSolver().solve(net);
                out.addProperty("solver", "RealMnaSolver");
                out.addProperty("mode", "dc");
                dcThermalLoop(net, r, comps, wires);
                fillResult(out, r, r.voltages, comps, wires);
            }
            out.addProperty("ok", true);
        } catch (Throwable ex) {
            out.addProperty("ok", false);
            out.addProperty("error", String.valueOf(ex));
        }
        return out;
    }

    /** 直流热稳态：R(T) ↔ 温度 外循环 */
    private static void dcThermalLoop(Network net, SolveResult r,
                                      Map<String, CompState> comps, List<WireState> wires) {
        boolean anyThermal = false;
        for (CompState st : comps.values()) if (st.resistor != null && st.g > 0) anyThermal = true;
        if (!anyThermal && wires.isEmpty()) return;
        for (int ti = 0; ti < 12; ti++) {
            double dT = 0;
            for (CompState st : comps.values()) {
                if (st.resistor == null || st.g <= 0) continue;
                double vd = vd(st.resistor, r.voltages);
                double p = vd * vd / st.resistor.resistance;
                double tNew = AMBIENT + Math.abs(p) / st.g;
                dT = Math.max(dT, Math.abs(tNew - st.temp));
                st.temp = tNew;
                st.resistor.setResistance(st.rAtTemp());
            }
            for (WireState ws : wires) {
                double p = ws.power(r.voltages);
                double tNew = AMBIENT + p / ws.g;
                dT = Math.max(dT, Math.abs(tNew - ws.temp));
                ws.temp = Math.min(tNew, ws.maxTemp);
                if (tNew > ws.maxTemp) {
                    ws.burned = true;
                    for (WireSeg s : ws.segs) s.resistor.setResistance(1e9);
                }
            }
            if (dT < 0.1) break;
            r = new RealMnaSolver().solve(net);
        }
    }

    /** 瞬态：逐拍推进 net.time；内核求解器负责电容/电感历史；适配器负责温度/能量 */
    private static void runTransient(Network net, double duration, double step,
                                     int[] probes, JsonObject out,
                                     Map<String, CompState> comps, List<WireState> wires) {
        RealMnaSolver solver = new RealMnaSolver();
        JsonArray times = new JsonArray();
        JsonArray waveforms = new JsonArray();
        for (int k = 0; k < probes.length; k++) waveforms.add(new JsonArray());

        double t = 0;
        int steps = Math.max(1, (int) Math.ceil(duration / step));
        SolveResult last = null;
        boolean converged = true;
        for (int s = 0; s <= steps && t <= duration + 1e-9; s++) {
            net.time = t;
            for (CompState st : comps.values()) {
                if (st.resistor != null && (st.alpha != 0 || st.g > 0)) {
                    st.resistor.setResistance(st.rAtTemp());
                }
            }
            for (WireState ws : wires) {
                if (ws.burned) for (WireSeg seg : ws.segs) seg.resistor.setResistance(1e9);
            }
            last = solver.solve(net);
            converged &= last.converged;
            times.add(t);
            for (int k = 0; k < probes.length; k++) {
                int p = probes[k];
                waveforms.get(k).getAsJsonArray().add(p >= 0 && p < last.voltages.length ? last.voltages[p] : 0);
            }
            // 温度/能量
            for (CompState st : comps.values()) {
                if (st.resistor != null && st.g > 0 && st.c > 0) {
                    double vd = vd(st.resistor, last.voltages);
                    double p = vd * vd / st.resistor.resistance;
                    st.temp += (Math.abs(p) - st.g * (st.temp - AMBIENT)) * step / st.c;
                    st.energy += Math.abs(p) * step;
                }
            }
            for (WireState ws : wires) {
                double p = ws.power(last.voltages);
                ws.temp += (p - ws.g * (ws.temp - AMBIENT)) * step / ws.c;
                ws.energy += p * step;
                if (ws.temp > ws.maxTemp) {
                    ws.temp = ws.maxTemp;
                    ws.burned = true;
                    for (WireSeg seg : ws.segs) seg.resistor.setResistance(1e9);
                }
            }
            t += step;
        }

        JsonObject tr = new JsonObject();
        tr.add("times", times);
        tr.add("waveforms", waveforms);
        out.add("transient", tr);
        out.addProperty("converged", converged);
        fillResult(out, last, last != null ? last.voltages : null, comps, wires);
    }

    private static double vd(Resistor r, double[] v) {
        if (v == null) return 0;
        return v[r.nodeA()] - v[r.nodeB()];
    }

    /** 填充响应：节点电压 + 元件/导线结果 */
    private static void fillResult(JsonObject out, SolveResult r, double[] v,
                                   Map<String, CompState> comps, List<WireState> wires) {
        JsonArray voltages = new JsonArray();
        if (v != null) for (double d : v) voltages.add(d);
        out.add("nodeVoltages", voltages);
        if (r != null) {
            out.addProperty("converged", r.converged);
            out.addProperty("iterations", r.iterations);
        }

        JsonArray compArr = new JsonArray();
        for (CompState st : comps.values()) {
            JsonObject c = new JsonObject();
            if (st.idNumeric) c.addProperty("id", Integer.parseInt(st.idKey));
            else c.addProperty("id", st.idKey);
            double i = st.resistor != null ? vd(st.resistor, v) / st.resistor.resistance : 0;
            double p = i * (st.resistor != null ? vd(st.resistor, v) : 0);
            c.addProperty("i", i);
            c.addProperty("p", p);
            c.addProperty("t", Math.round(st.temp * 10) / 10.0);
            c.addProperty("energy", Math.round(st.energy * 1000) / 1000.0);
            c.addProperty("stored", 0);
            compArr.add(c);
        }
        out.add("components", compArr);

        JsonArray wireArr = new JsonArray();
        for (WireState ws : wires) {
            JsonObject w = new JsonObject();
            if (numId(ws.idKey)) w.addProperty("id", Integer.parseInt(ws.idKey));
            else w.addProperty("id", ws.idKey);
            double i = ws.current(v);
            w.addProperty("r", ws.rSum);
            w.addProperty("length", Math.round(ws.length * 100) / 100.0);
            w.addProperty("i", i);
            w.addProperty("p", i * i * ws.rSum);
            w.addProperty("t", Math.round(ws.temp * 10) / 10.0);
            w.addProperty("energy", Math.round(ws.energy * 1000) / 1000.0);
            w.addProperty("burned", ws.burned);
            w.addProperty("type", ws.type);
            w.addProperty("name", ws.name);
            w.addProperty("ratedCurrent", ws.ratedCurrent);
            w.addProperty("load", ws.ratedCurrent > 0 ? Math.abs(i) / ws.ratedCurrent : 0);
            wireArr.add(w);
        }
        out.add("wires", wireArr);

        JsonArray burned = new JsonArray();
        for (WireState ws : wires) if (ws.burned) burned.add(ws.idKey);
        out.add("burnedWires", burned);
    }
}
