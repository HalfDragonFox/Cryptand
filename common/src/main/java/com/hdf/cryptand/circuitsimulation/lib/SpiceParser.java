package com.hdf.cryptand.circuitsimulation.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPICE 库文本解析器（.subckt/.lib 结构）。
 * <p>
 * 支持：
 * <ul>
 *   <li>{@code .subckt name pin1 pin2 ...} ... {@code .ends [name]} 子电路块</li>
 *   <li>{@code .param name=value} 默认参数（可在子电路内或顶层）</li>
 *   <li>元件行（R/C/L/V/I/D/Q/M/K/X）、续行（{@code +}）、注释（{@code *}、{@code $}）</li>
 *   <li>{@code .model} / {@code .include} / {@code .lib} / {@code .global} 等跳过</li>
 * </ul>
 * 顶层（非子电路内）元件行被忽略——只收集 {@code .subckt} 定义。
 */
public final class SpiceParser {

    private SpiceParser() {
    }

    /** 解析库文本 → 子电路定义 + 元件模型（.model，按声明顺序）。 */
    public static SpiceParseResult parse(String text) {
        SpiceParseResult result = new SpiceParseResult();
        if (text == null || text.isEmpty()) return result;
        // 合并续行（+ 开头）
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : text.split("\r?\n")) {
            String t = raw.trim();
            if (t.startsWith("+")) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(t.substring(1).trim());
            } else {
                if (cur.length() > 0) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(t);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());

        SpiceSubcircuit curSub = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("*")) continue;
            // 行内注释：$ 之后截断
            int di = line.indexOf('$');
            if (di >= 0) line = line.substring(0, di).trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase();
            if (upper.startsWith(".SUBCKT")) {
                String[] toks = tokenize(line.substring(7)).toArray(new String[0]);
                if (toks.length < 1) continue;
                SpiceSubcircuit sub = new SpiceSubcircuit(toks[0].toUpperCase());
                for (int i = 1; i < toks.length; i++) {
                    sub.pins.add(toks[i].toLowerCase());
                }
                result.subcircuits.add(sub);
                curSub = sub;
            } else if (upper.startsWith(".MODEL")) {
                SpiceModel m = parseModel(line);
                if (m != null) result.models.add(m);
            } else if (upper.startsWith(".ENDS")) {
                curSub = null;
            } else if (upper.startsWith(".PARAM")) {
                if (curSub != null) {
                    parseParams(line.substring(6), curSub.defaultParams);
                }
            } else if (upper.startsWith(".")) {
                // .include/.lib/.global 等：MVP 跳过
            } else {
                if (curSub == null) continue; // 顶层元件行：忽略
                SpiceElement el = parseElement(line);
                if (el != null) curSub.elements.add(el);
            }
        }
        return result;
    }

    /**
     * 解析 .model NAME TYPE(p1=v1 p2=v2 ...)（内部电路实现模型）。
     * 括号可与 TYPE 相连（{@code D(Is=...)}）或独立 token。
     */
    static SpiceModel parseModel(String line) {
        String rest = line.substring(6).trim();
        List<String> toks = tokenize(rest);
        if (toks.isEmpty()) return null;
        String name = toks.get(0).toUpperCase();
        Map<String, Double> params = new LinkedHashMap<>();
        String type;
        if (toks.size() >= 2) {
            String t2 = toks.get(1);
            int o = t2.indexOf('(');
            if (o >= 0) {
                type = t2.substring(0, o).toUpperCase();
                parseModelParams(t2, params);
            } else {
                type = t2.toUpperCase();
                for (int i = 2; i < toks.size(); i++) {
                    parseModelParams(toks.get(i), params);
                }
            }
        } else {
            type = "";
        }
        if (type.isEmpty()) return null;
        return new SpiceModel(name, type, params);
    }

    /** 解析模型参数串（括号内 k=v 或裸 k=v）。 */
    private static void parseModelParams(String s, Map<String, Double> params) {
        int o = s.indexOf('(');
        int c = s.lastIndexOf(')');
        if (o >= 0 && c > o) s = s.substring(o + 1, c);
        for (String part : s.split("[,\\s]+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            double v = SpiceValue.eval(part.substring(eq + 1).trim(), params);
            if (!Double.isNaN(v)) params.put(part.substring(0, eq).trim(), v);
        }
    }

    /** 解析 .param name=value[, name=value ...]。 */
    private static void parseParams(String rest, java.util.Map<String, Double> into) {
        for (String part : rest.split("[,]")) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            double v = SpiceValue.eval(part.substring(eq + 1).trim(), into);
            if (!Double.isNaN(v)) into.put(key, v);
        }
    }

    /** 解析一条元件行 → SpiceElement（无法识别返回 null）。 */
    static SpiceElement parseElement(String line) {
        List<String> toks = tokenize(line);
        if (toks.isEmpty()) return null;
        String name = toks.get(0);
        char c0 = name.charAt(0);
        if (!Character.isLetter(c0)) return null;
        char c = Character.toUpperCase(c0);
        String type;
        switch (c) {
            case 'R': case 'C': case 'L': case 'V': case 'I':
            case 'D': case 'Q': case 'M': case 'K': case 'X':
                type = String.valueOf(c);
                break;
            default:
                return null;
        }
        // 节点数（SPICE 固定语法）：R/C/L/V/I/D=2，Q=3(C B E)，M=4(D G S B)
        int nodeCount;
        switch (type) {
            case "Q": nodeCount = 3; break;
            case "M": nodeCount = 4; break;
            default:  nodeCount = 2; break;
        }
        List<String> nodes = new ArrayList<>();
        int idx = 1;
        for (int i = 0; i < nodeCount && idx < toks.size(); i++) {
            nodes.add(toks.get(idx).toLowerCase());
            idx++;
        }
        if (nodes.isEmpty()) return null;
        // 剩余 token：值 + 附加
        String value = null;
        List<String> extra = new ArrayList<>();
        if (idx < toks.size()) {
            value = toks.get(idx);
            idx++;
        }
        for (; idx < toks.size(); idx++) extra.add(toks.get(idx));
        String subcktRef = null;
        if (type.equals("X")) {
            // X1 n1 n2 ... subcktName [params]：最后一个节点 token 是子电路名
            if (nodes.size() >= 2) {
                subcktRef = nodes.remove(nodes.size() - 1).toUpperCase();
            }
        }
        return new SpiceElement(type, name, nodes, value, subcktRef, extra);
    }

    /** 按空白分词，保持括号内（SIN(...) 为整体）。 */
    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth = Math.max(0, depth - 1);
            if (Character.isWhitespace(ch) && depth == 0) {
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
