package com.hdf.cryptand.circuitsimulation.lib;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.BjtElement;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DiodeElement;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.MutualInductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.VfetElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cryptand 元件库管理器：加载 config/cryptand/library/ 下的 SPICE 库文件
 * （.lib / .spice / .cir），并把子电路展开为 Cryptand 求解器元件。
 * <p>
 * 展开规则（SPICE → Cryptand）：
 * <ul>
 *   <li>R → {@link Resistor}、C → {@link Capacitor}、L → {@link Inductor}</li>
 *   <li>V（DC/AC/SIN）→ {@link DcVoltageSource} / {@link AcVoltageSource}，
 *       I → {@link CurrentSource}</li>
 *   <li>D → {@link DiodeElement}、Q → {@link BjtElement}、
 *       M → {@link VfetElement}（模型名含 PNP/PMOS 自动判型）</li>
 *   <li>K → {@link MutualInductor}（简式，耦合系数 k 换算互感 M）</li>
 *   <li>X → 递归展开嵌套子电路（深度限制防循环）</li>
 * </ul>
 * 节点 "0" 映射到网络参考地（{@link Network#groundNode}）；端口按引脚顺序映射
 * 到调用方提供的引擎节点；内部节点自动分配。
 */
public final class SpiceLibrary {

    /** 嵌套子电路最大展开深度（防递归循环） */
    public static final int MAX_DEPTH = 8;
    /** 单个子电路最大元件数（防恶意/错误库撑爆网络） */
    public static final int MAX_ELEMENTS = 512;

    private final Map<String, SpiceSubcircuit> subcircuits = new LinkedHashMap<>();
    /** 元件模型（.model，同名覆盖 = 替换内部电路实现模型） */
    private final Map<String, SpiceModel> models = new LinkedHashMap<>();

    public SpiceLibrary() {
    }

    /** 注册一个子电路（同名覆盖）。 */
    public void add(SpiceSubcircuit sub) {
        if (sub != null && sub.name != null) {
            subcircuits.put(sub.name.toUpperCase(), sub);
        }
    }

    /** 注册元件模型（同名覆盖 = 替换内部电路实现模型）。 */
    public void add(SpiceModel m) {
        if (m != null && m.name != null) {
            models.put(m.name.toUpperCase(), m);
        }
    }

    /** 按名查找子电路（大小写不敏感）。 */
    public SpiceSubcircuit get(String name) {
        return name == null ? null : subcircuits.get(name.toUpperCase());
    }

    /** 按名查元件模型（大小写不敏感；无则 null）。 */
    public SpiceModel getModel(String name) {
        return name == null ? null : models.get(name.toUpperCase());
    }

    public Collection<SpiceModel> allModels() {
        return models.values();
    }

    public Collection<SpiceSubcircuit> all() {
        return subcircuits.values();
    }

    public int size() {
        return subcircuits.size();
    }

    public boolean isEmpty() {
        return subcircuits.isEmpty();
    }

    /** 从目录加载所有 *.lib / *.spice / *.cir 文件（递归一层）。 */
    public static SpiceLibrary load(Path dir) {
        SpiceLibrary lib = new SpiceLibrary();
        if (dir == null || !Files.isDirectory(dir)) return lib;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".lib") || n.endsWith(".spice") || n.endsWith(".cir");
            }).forEach(p -> {
                try {
                    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    SpiceParseResult res = SpiceParser.parse(text);
                    for (SpiceSubcircuit sub : res.subcircuits) {
                        lib.add(sub);
                    }
                    for (SpiceModel m : res.models) {
                        lib.add(m);
                    }
                } catch (IOException ex) {
                    // 忽略单个文件解析失败
                }
            });
        } catch (IOException ex) {
            // 目录不可读 → 空库
        }
        return lib;
    }

    /** 按名展开到网络（未找到返回 null）。 */
    public SpiceInstance expand(Network net, String name, int[] portNodeIds, Map<String, Double> params) {
        SpiceSubcircuit sub = get(name);
        if (sub == null) return null;
        return expand(net, sub, portNodeIds, params);
    }

    /** 展开子电路：端口引脚 → 调用方节点，内部节点经 net.addNode() 分配。 */
    public SpiceInstance expand(Network net, SpiceSubcircuit sub, int[] portNodeIds, Map<String, Double> params) {
        SpiceInstance inst = new SpiceInstance();
        inst.net = net;
        if (sub == null) return inst;
        Map<String, Integer> nodeMap = new HashMap<>();
        int n = Math.min(sub.portCount(), portNodeIds == null ? 0 : portNodeIds.length);
        for (int i = 0; i < n; i++) {
            nodeMap.put(sub.pins.get(i), portNodeIds[i]);
        }
        expandInto(sub, nodeMap, params, inst, 0, 0);
        return inst;
    }
    // ===================== 固化（resolve）与固化展开 =====================

    /** 按名解析为固化电路（未找到返回 null）。 */
    public ResolvedCircuit resolve(String name, Map<String, Double> params) {
        SpiceSubcircuit sub = get(name);
        if (sub == null) return null;
        return resolve(sub, params);
    }

    /**
     * 解析子电路为【固化电路】：参数求值（{@code {Rval}} → 数字）、嵌套 X 内联、
     * 节点名唯一化。返回的 ResolvedCircuit 可直接持久化——已放置元件不再依赖库。
     */
    public ResolvedCircuit resolve(SpiceSubcircuit sub, Map<String, Double> params) {
        ResolvedCircuit out = new ResolvedCircuit();
        if (sub == null) return out;
        out.pins.addAll(sub.pins);
        Map<String, Double> p = new HashMap<>();
        if (sub.defaultParams != null) p.putAll(sub.defaultParams);
        if (params != null) p.putAll(params);
        Map<String, String> nameMap = new HashMap<>();
        for (String pin : sub.pins) {
            nameMap.put(pin, pin); // 端口名 → 自身（保留原名）
        }
        resolveInto(sub, p, out, 0, nameMap, new int[]{0});
        return out;
    }

    private void resolveInto(SpiceSubcircuit sub, Map<String, Double> p, ResolvedCircuit out,
                             int depth, Map<String, String> nameMap, int[] counter) {
        if (depth > MAX_DEPTH) return;
        for (SpiceElement el : sub.elements) {
            if (el.type.equals("X")) {
                SpiceSubcircuit child = get(el.subcktRef);
                if (child == null) continue;
                Map<String, String> childMap = new HashMap<>();
                for (int i = 0; i < child.portCount(); i++) {
                    if (i < el.nodes.size()) {
                        childMap.put(child.pins.get(i), resolveNode(el.nodes.get(i), nameMap, counter));
                    } else {
                        childMap.put(child.pins.get(i), "n" + (counter[0]++));
                    }
                }
                Map<String, Double> childParams = new HashMap<>();
                if (child.defaultParams != null) childParams.putAll(child.defaultParams);
                for (String e : el.extra) {
                    int eq = e.indexOf('=');
                    if (eq > 0) {
                        double v = SpiceValue.eval(e.substring(eq + 1), childParams);
                        if (!Double.isNaN(v)) childParams.put(e.substring(0, eq), v);
                    }
                }
                resolveInto(child, childParams, out, depth + 1, childMap, counter);
                continue;
            }
            String valueText = resolveValueText(el.value, p);
            List<String> nodes = new ArrayList<>(el.nodes.size());
            if (el.type.equals("K")) {
                // K 引用的是 L 元件名（固化后已内联），不参与节点解析
                nodes.addAll(el.nodes);
            } else {
                for (String n : el.nodes) {
                    nodes.add(resolveNode(n, nameMap, counter));
                }
            }
            List<String> extra = new ArrayList<>(el.extra);
            if (el.type.equals("D") || el.type.equals("Q") || el.type.equals("M")) {
                // 内部电路实现模型：把 .model 参数并入固化 extra（构建不再依赖库）
                SpiceModel model = getModel(modelName(el));
                if (model != null) {
                    for (Map.Entry<String, Double> me : model.params.entrySet()) {
                        boolean exists = false;
                        for (String x : extra) {
                            if (x.startsWith(me.getKey() + "=")) { exists = true; break; }
                        }
                        if (!exists) extra.add(me.getKey() + "=" + me.getValue());
                    }
                }
            }
            out.elements.add(new ResolvedCircuit.Element(el.type, nodes, valueText, extra));
        }
    }

    private String resolveValueText(String expr, Map<String, Double> p) {
        if (expr == null) return null;
        double v = SpiceValue.eval(expr, p);
        if (!Double.isNaN(v)) return String.valueOf(v);
        return expr; // 关键字（DC/AC/SIN(...)）原样保留
    }

    private String resolveNode(String name, Map<String, String> nameMap, int[] counter) {
        if ("0".equals(name)) return "0";
        String mapped = nameMap.get(name);
        if (mapped != null) return mapped;
        String newName = "n" + (counter[0]++);
        nameMap.put(name, newName);
        return newName;
    }

    /** 用固化电路构建 Cryptand 元件（不依赖库；端口引脚 → 调用方节点）。 */
    public SpiceInstance expandResolved(Network net, ResolvedCircuit circ, int[] portNodeIds) {
        SpiceInstance inst = new SpiceInstance();
        inst.net = net;
        if (net == null || circ == null) return inst;
        Map<String, Integer> nodeMap = new HashMap<>();
        int n = Math.min(circ.pins.size(), portNodeIds == null ? 0 : portNodeIds.length);
        for (int i = 0; i < n; i++) {
            nodeMap.put(circ.pins.get(i), portNodeIds[i]);
        }
        Map<String, int[]> inductors = new HashMap<>();
        for (ResolvedCircuit.Element el : circ.elements) {
            try {
                expandResolvedOne(net, el, nodeMap, inst, inductors);
            } catch (Throwable t) {
                inst.note("expand failed " + el.type + ": " + t);
            }
        }
        return inst;
    }

    private void expandResolvedOne(Network net, ResolvedCircuit.Element el,
                                   Map<String, Integer> nodeMap, SpiceInstance inst,
                                   Map<String, int[]> inductors) {
        switch (el.type) {
            case "R": {
                if (el.nodes.size() < 2) break;
                int a = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                double r = el.value();
                if (Double.isNaN(r) || r <= 0) break;
                inst.add(new Resistor(a, b, r));
                break;
            }
            case "C": {
                if (el.nodes.size() < 2) break;
                int a = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                double c = el.value();
                if (Double.isNaN(c) || c <= 0) break;
                inst.add(new Capacitor(a, b, c));
                break;
            }
            case "L": {
                if (el.nodes.size() < 2) break;
                int a = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                double l = el.value();
                if (Double.isNaN(l) || l <= 0) break;
                inst.add(new Inductor(a, b, l));
                inductors.put("L" + inst.elements().size(), new int[]{a, b});
                break;
            }
            case "V": {
                if (el.nodes.size() < 2) break;
                int a = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                expandVoltageSourceText(el.valueText, el.extra, a, b, inst);
                break;
            }
            case "I": {
                if (el.nodes.size() < 2) break;
                int a = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                double i = el.value();
                if (Double.isNaN(i)) break;
                inst.add(new CurrentSource(a, b, i));
                break;
            }
            case "D": {
                if (el.nodes.size() < 2) break;
                int a = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                double vth = extraParam(el.extra, "Vf", extraParam(el.extra, "Vth", 0.7));
                double rF = extraParam(el.extra, "Ron", 1.0);
                double rR = extraParam(el.extra, "Roff", 1_000_000.0);
                inst.add(new DiodeElement(a, b, vth, rF, rR));
                break;
            }
            case "Q": {
                if (el.nodes.size() < 3) break;
                int c = rnode(net, el.nodes.get(0), nodeMap, inst);
                int b = rnode(net, el.nodes.get(1), nodeMap, inst);
                int e = rnode(net, el.nodes.get(2), nodeMap, inst);
                String mn = modelName(el.extra);
                boolean pnp = mn.contains("PNP");
                double beta = extraParam(el.extra, "Bf", extraParam(el.extra, "Beta", 100.0));
                double vth = extraParam(el.extra, "Vbe", extraParam(el.extra, "Vth", 0.7));
                double rbe = extraParam(el.extra, "Rbe", 1000.0);
                double vceSat = extraParam(el.extra, "VceSat", 0.2);
                double rceSat = extraParam(el.extra, "RceSat", 1.0);
                double rceOff = extraParam(el.extra, "RceOff", 1_000_000.0);
                inst.add(new BjtElement(c, e, b, pnp, beta, vth, rbe, vceSat, rceSat, rceOff));
                break;
            }
            case "M": {
                if (el.nodes.size() < 3) break;
                int d = rnode(net, el.nodes.get(0), nodeMap, inst);
                int g = rnode(net, el.nodes.get(1), nodeMap, inst);
                int s = rnode(net, el.nodes.get(2), nodeMap, inst);
                String mn = modelName(el.extra);
                boolean nCh = !(mn.startsWith("P") || mn.contains("PMOS"));
                double vth = extraParam(el.extra, "Vto", extraParam(el.extra, "Vth", 2.0));
                double rdsOn = extraParam(el.extra, "Ron", 1.0);
                double rdsOff = extraParam(el.extra, "Roff", 1_000_000.0);
                inst.add(new VfetElement(d, s, g, nCh, vth, rdsOn, rdsOff));
                break;
            }
            case "K": {
                // K 引用电感：resolve 已内联，这里用"前两个相邻 L 的节点"匹配
                // （SPICE K 顺序在库文件里引用具体 L 名，固化后由 resolve 前的
                //  展开序保证：K 只可能引用本层已出现的 L —— 按出现序匹配）
                if (el.nodes.size() < 2) break;
                double k = el.value();
                if (Double.isNaN(k)) break;
                if (inductors.size() < 2) break;
                java.util.Iterator<int[]> it = inductors.values().iterator();
                int[] l1 = it.next();
                int[] l2 = it.next();
                double L1 = findInductance(inst, l1[0], l1[1]);
                double L2 = findInductance(inst, l2[0], l2[1]);
                double m = k * Math.sqrt(L1 * L2);
                int x = inst.net == null ? inst.internalNodes++ : inst.net.addNode().id;
                int y = inst.net == null ? inst.internalNodes++ : inst.net.addNode().id;
                int kk = inst.net == null ? inst.internalNodes++ : inst.net.addNode().id;
                inst.add(new MutualInductor(l1[0], l1[1], l2[0], l2[1], L1, L2, m, 1.0, x, y, kk));
                break;
            }
            default:
                inst.note("unsupported resolved type " + el.type);
        }
    }

    private void expandVoltageSourceText(String v, List<String> extra, int a, int b,
                                         SpiceInstance inst) {
        if (v == null) return;
        String up = v.trim().toUpperCase();
        if (up.startsWith("SIN(")) {
            List<String> args = inside(v);
            double amp = args.size() > 1 ? SpiceValue.parse(args.get(1)) : 1.0;
            if (Double.isNaN(amp)) amp = 1.0;
            inst.add(new AcVoltageSource(a, b, amp, 0.0, 1e-4));
            return;
        }
        if (up.equals("DC")) {
            double vv = extra.isEmpty() ? 0 : SpiceValue.parse(extra.get(0));
            if (Double.isNaN(vv)) vv = 0;
            inst.add(new DcVoltageSource(a, b, vv, 1e-4));
            return;
        }
        if (up.equals("AC")) {
            double vv = extra.isEmpty() ? 1.0 : SpiceValue.parse(extra.get(0));
            if (Double.isNaN(vv)) vv = 1.0;
            inst.add(new AcVoltageSource(a, b, vv, 0.0, 1e-4));
            return;
        }
        double vv = SpiceValue.parse(v);
        if (Double.isNaN(vv)) {
            inst.note("bad V=" + v);
            return;
        }
        inst.add(new DcVoltageSource(a, b, vv, 1e-4));
    }

    /** 固化元件节点解析："0" → 参考地；端口/已分配 → 复用；否则新节点。 */
    private static int rnode(Network net, String name, Map<String, Integer> nodeMap,
                             SpiceInstance inst) {
        if ("0".equals(name) && inst.net != null) return inst.net.groundNode;
        Integer id = nodeMap.get(name);
        if (id != null) return id;
        id = net.addNode().id;
        nodeMap.put(name, id);
        inst.internalNodes++;
        return id;
    }

    /** 固化元件模型名（extra 中第一个字母开头 token）。 */
    private static String modelName(List<String> extra) {
        for (String e : extra) {
            if (!e.isEmpty() && Character.isLetter(e.charAt(0))) return e.toUpperCase();
        }
        return "";
    }

    /** 模型参数读取（无则默认）。 */
    private static double modelParam(Map<String, Double> params, String key, double def) {
        Double v = params == null ? null : params.get(key);
        return v == null ? def : v;
    }

    /** 固化 extra 中 k=v 参数读取（无则默认）。 */
    private static double extraParam(List<String> extra, String key, double def) {
        String prefix = key + "=";
        for (String e : extra) {
            if (e.startsWith(prefix)) {
                double v = SpiceValue.parse(e.substring(prefix.length()));
                if (!Double.isNaN(v)) return v;
            }
        }
        return def;
    }
    private void expandInto(SpiceSubcircuit sub, Map<String, Integer> nodeMap,
                            Map<String, Double> params, SpiceInstance inst,
                            int depth, int count) {
        if (depth > MAX_DEPTH) {
            inst.note("nesting too deep at " + sub.name);
            return;
        }
        // 合并默认参数 + 外部参数
        Map<String, Double> p = new HashMap<>();
        if (sub.defaultParams != null) p.putAll(sub.defaultParams);
        if (params != null) p.putAll(params);

        Map<String, int[]> inductors = new HashMap<>(); // L 名 → {a,b,L}
        for (SpiceElement el : sub.elements) {
            if (count > MAX_ELEMENTS) {
                inst.note("element limit exceeded");
                return;
            }
            count++;
            try {
                count = expandOne(el, nodeMap, p, inst, depth, count, inductors);
            } catch (Throwable t) {
                inst.note("expand failed " + el.name + ": " + t);
            }
        }
    }

    private int expandOne(SpiceElement el, Map<String, Integer> nodeMap,
                          Map<String, Double> p, SpiceInstance inst,
                          int depth, int count, Map<String, int[]> inductors) {
        switch (el.type) {
            case "R": {
                if (el.nodes.size() < 2) break;
                int a = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                double r = SpiceValue.eval(el.value, p);
                if (Double.isNaN(r) || r <= 0) {
                    inst.note(el.name + " bad R=" + el.value);
                    break;
                }
                inst.add(new Resistor(a, b, r));
                break;
            }
            case "C": {
                if (el.nodes.size() < 2) break;
                int a = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                double c = SpiceValue.eval(el.value, p);
                if (Double.isNaN(c) || c <= 0) {
                    inst.note(el.name + " bad C=" + el.value);
                    break;
                }
                inst.add(new Capacitor(a, b, c));
                break;
            }
            case "L": {
                if (el.nodes.size() < 2) break;
                int a = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                double l = SpiceValue.eval(el.value, p);
                if (Double.isNaN(l) || l <= 0) {
                    inst.note(el.name + " bad L=" + el.value);
                    break;
                }
                inst.add(new Inductor(a, b, l));
                inductors.put(el.name.toUpperCase(), new int[]{a, b});
                break;
            }
            case "V": {
                if (el.nodes.size() < 2) break;
                int a = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                expandVoltageSource(el, a, b, p, inst);
                break;
            }
            case "I": {
                if (el.nodes.size() < 2) break;
                int a = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                double i = SpiceValue.eval(el.value, p);
                if (Double.isNaN(i)) {
                    inst.note(el.name + " bad I=" + el.value);
                    break;
                }
                inst.add(new CurrentSource(a, b, i));
                break;
            }
            case "D": {
                if (el.nodes.size() < 2) break;
                int a = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                inst.add(new DiodeElement(a, b));
                break;
            }
            case "Q": {
                if (el.nodes.size() < 3) break;
                int c = node(el.nodes.get(0), nodeMap, inst);
                int b = node(el.nodes.get(1), nodeMap, inst);
                int e = node(el.nodes.get(2), nodeMap, inst);
                boolean pnp = modelName(el).contains("PNP");
                inst.add(new BjtElement(c, e, b, pnp, 100.0, 0.7, 1000.0, 0.2, 1.0, 1_000_000.0));
                break;
            }
            case "M": {
                if (el.nodes.size() < 3) break;
                int d = node(el.nodes.get(0), nodeMap, inst);
                int g = node(el.nodes.get(1), nodeMap, inst);
                int s = node(el.nodes.get(2), nodeMap, inst);
                String mn = modelName(el);
                boolean nCh = !(mn.startsWith("P") || mn.contains("PMOS"));
                inst.add(new VfetElement(d, s, g, nCh, 2.0, 1.0, 1_000_000.0));
                break;
            }
            case "K": {
                if (el.nodes.size() < 2) break;
                int[] l1 = inductors.get(el.nodes.get(0).toUpperCase());
                int[] l2 = inductors.get(el.nodes.get(1).toUpperCase());
                double k = SpiceValue.eval(el.value, p);
                if (l1 == null || l2 == null || Double.isNaN(k)) {
                    inst.note(el.name + " K needs two L refs, k=" + el.value);
                    break;
                }
                // L 值：从 inst 里找对应 Inductor 元件（inductors 只存了节点，值需要重查）
                double L1 = findInductance(inst, l1[0], l1[1]);
                double L2 = findInductance(inst, l2[0], l2[1]);
                double m = k * Math.sqrt(L1 * L2);
                int x = inst.net == null ? inst.internalNodes++ : inst.net.addNode().id;
                int y = inst.net == null ? inst.internalNodes++ : inst.net.addNode().id;
                int kk = inst.net == null ? inst.internalNodes++ : inst.net.addNode().id;
                inst.add(new MutualInductor(l1[0], l1[1], l2[0], l2[1], L1, L2, m, 1.0, x, y, kk));
                break;
            }
            case "X": {
                SpiceSubcircuit child = get(el.subcktRef);
                if (child == null) {
                    inst.note("X " + el.name + " ref " + el.subcktRef + " not found");
                    break;
                }
                // 子电路实例：独立局部命名空间（内部节点不与外层冲突）
                Map<String, Integer> childMap = new HashMap<>();
                for (int i = 0; i < child.portCount(); i++) {
                    if (i < el.nodes.size()) {
                        childMap.put(child.pins.get(i), node(el.nodes.get(i), nodeMap, inst));
                    } else {
                        childMap.put(child.pins.get(i),
                                inst.net == null ? inst.internalNodes++ : inst.net.addNode().id);
                    }
                }
                Map<String, Double> childParams = new HashMap<>();
                if (child.defaultParams != null) childParams.putAll(child.defaultParams);
                for (String e : el.extra) {
                    int eq = e.indexOf('=');
                    if (eq > 0) {
                        double v = SpiceValue.eval(e.substring(eq + 1), childParams);
                        if (!Double.isNaN(v)) childParams.put(e.substring(0, eq), v);
                    }
                }
                expandInto(child, childMap, childParams, inst, depth + 1, count);
                break;
            }
            default:
                inst.note("unsupported type " + el.type);
        }
        return count;
    }

    private void expandVoltageSource(SpiceElement el, int a, int b,
                                     Map<String, Double> p, SpiceInstance inst) {
        String v = el.value == null ? "" : el.value.trim();
        String up = v.toUpperCase();
        if (up.startsWith("SIN(")) {
            // SIN(offset amplitude freq [td theta]) → 相量源（幅值 + 相位 0）
            List<String> args = inside(v);
            double amp = args.size() > 1 ? SpiceValue.eval(args.get(1), p) : 1.0;
            double off = args.size() > 0 ? SpiceValue.eval(args.get(0), p) : 0.0;
            if (Double.isNaN(amp)) amp = 1.0;
            inst.add(new AcVoltageSource(a, b, amp, 0.0, 1e-4));
            return;
        }
        if (up.equals("DC")) {
            double vv = el.extra.isEmpty() ? Double.NaN : SpiceValue.eval(el.extra.get(0), p);
            if (Double.isNaN(vv)) vv = 0;
            inst.add(new DcVoltageSource(a, b, vv, 1e-4));
            return;
        }
        if (up.equals("AC")) {
            double vv = el.extra.isEmpty() ? Double.NaN : SpiceValue.eval(el.extra.get(0), p);
            if (Double.isNaN(vv)) vv = 1.0;
            inst.add(new AcVoltageSource(a, b, vv, 0.0, 1e-4));
            return;
        }
        double vv = SpiceValue.eval(v, p);
        if (Double.isNaN(vv)) {
            inst.note(el.name + " bad V=" + el.value);
            return;
        }
        inst.add(new DcVoltageSource(a, b, vv, 1e-4));
    }

    /** 元件模型名（extra 中第一个字母开头的 token；无则空串）。 */
    private static String modelName(SpiceElement el) {
        for (String e : el.extra) {
            if (!e.isEmpty() && Character.isLetter(e.charAt(0))) return e.toUpperCase();
        }
        return "";
    }

    /** 从已展开元件中反查某节点对之间的电感值（K 互感用）。 */
    private static double findInductance(SpiceInstance inst, int a, int b) {
        for (Element e : inst.elements()) {
            if (e instanceof Inductor in && in.nodeA() == a && in.nodeB() == b) {
                return in.inductance;
            }
        }
        return 0.001; // 兜底：1mH
    }

    /** 节点解析："0" → 参考地；端口/已分配 → 复用；否则 net.addNode() 新节点。 */
    private static int node(String name, Map<String, Integer> nodeMap, SpiceInstance inst) {
        if ("0".equals(name) && inst.net != null) return inst.net.groundNode;
        Integer id = nodeMap.get(name);
        if (id != null) return id;
        int nid = inst.net == null ? inst.internalNodes : inst.net.addNode().id;
        nodeMap.put(name, nid);
        inst.internalNodes++;
        return nid;
    }

    /** 解析 SIN(...) 括号内参数。 */
    private static List<String> inside(String sin) {
        List<String> out = new ArrayList<>();
        int o = sin.indexOf('(');
        int c = sin.lastIndexOf(')');
        if (o < 0 || c <= o) return out;
        String body = sin.substring(o + 1, c);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
