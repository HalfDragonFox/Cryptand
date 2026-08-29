package com.hdf.cryptand.circuitsimulation.lib;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPICE 数值解析与参数化表达式求值工具。
 * <p>
 * 支持：SPICE 工程后缀（T/G/MEG/K/M/U/N/P/F/MIL）、科学计数法、
 * 参数化（{@code {Rval}} 或裸参数名）、以及简单四则运算表达式
 * （{@code {R}*2} / {@code 1/(2*{C})}，含括号与一元负号）。
 */
public final class SpiceValue {

    private static final Pattern NUM = Pattern.compile(
            "([+-]?(?:[0-9]*\\.)?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*([A-Za-z]*)");

    private SpiceValue() {
    }

    /** 解析 SPICE 数值；非数值返回 NaN（参数化/关键字/表达式）。 */
    public static double parse(String s) {
        if (s == null) return Double.NaN;
        s = s.trim();
        if (s.isEmpty()) return Double.NaN;
        if (s.startsWith("{") || s.indexOf('{') >= 0 || s.indexOf('+') >= 0
                || s.indexOf('*') >= 0 || s.indexOf('/') >= 0) {
            return Double.NaN; // 参数化/表达式 → 由 eval 处理
        }
        Matcher m = NUM.matcher(s);
        if (!m.matches()) return Double.NaN;
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
        String suf = m.group(2).toUpperCase();
        switch (suf) {
            case "":    break;
            case "T":   v *= 1e12;  break;
            case "G":   v *= 1e9;   break;
            case "MEG": v *= 1e6;   break;
            case "K":   v *= 1e3;   break;
            case "M":   v *= 1e-3;  break;
            case "U":   v *= 1e-6;  break;
            case "N":   v *= 1e-9;  break;
            case "P":   v *= 1e-12; break;
            case "F":   v *= 1e-15; break;
            case "MIL": v *= 25.4e-6; break; // 千分之一英寸
            default:    return Double.NaN;   // 未知后缀（单位字符等）→ 非数值
        }
        return v;
    }

    /**
     * 参数化值求值：{@code {name}} / 裸参数名 / 数字 / 简单四则表达式。
     * 无法求值返回 NaN。
     */
    public static double eval(String s, Map<String, Double> params) {
        if (s == null) return Double.NaN;
        s = s.trim();
        if (s.isEmpty()) return Double.NaN;
        // 纯数值（无运算符/参数引用）
        double direct = parse(s);
        if (!Double.isNaN(direct)) return direct;
        // 替换参数引用
        StringBuilder expr = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                int j = s.indexOf('}', i);
                if (j < 0) return Double.NaN;
                String key = s.substring(i + 1, j).trim();
                Double v = params == null ? null : params.get(key);
                if (v == null) return Double.NaN;
                expr.append(v);
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length()
                        && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                String key = s.substring(i, j);
                Double v = params == null ? null : params.get(key);
                if (v == null) return Double.NaN;
                expr.append(v);
                i = j - 1;
            } else {
                expr.append(c);
            }
        }
        return evaluate(expr.toString());
    }

    /** 递归下降求值四则表达式（+ - * / 括号、一元负号、PI 常量）。 */
    private static double evaluate(String expr) {
        double[] pos = {0};
        double v = evalAdd(expr, pos);
        // 跳过尾部空白
        while (pos[0] < expr.length() && Character.isWhitespace(expr.charAt((int) pos[0]))) {
            pos[0]++;
        }
        if (pos[0] != expr.length()) return Double.NaN;
        return v;
    }

    private static double evalAdd(String s, double[] pos) {
        double v = evalMul(s, pos);
        while (true) {
            skipWs(s, pos);
            if (pos[0] >= s.length()) return v;
            char c = s.charAt((int) pos[0]);
            if (c == '+') { pos[0]++; v += evalMul(s, pos); }
            else if (c == '-') { pos[0]++; v -= evalMul(s, pos); }
            else return v;
        }
    }

    private static double evalMul(String s, double[] pos) {
        double v = evalUnary(s, pos);
        while (true) {
            skipWs(s, pos);
            if (pos[0] >= s.length()) return v;
            char c = s.charAt((int) pos[0]);
            if (c == '*') { pos[0]++; v *= evalUnary(s, pos); }
            else if (c == '/') {
                pos[0]++;
                double d = evalUnary(s, pos);
                if (d == 0) return Double.NaN;
                v /= d;
            } else return v;
        }
    }

    private static double evalUnary(String s, double[] pos) {
        skipWs(s, pos);
        if (pos[0] >= s.length()) return Double.NaN;
        char c = s.charAt((int) pos[0]);
        if (c == '+') { pos[0]++; return evalUnary(s, pos); }
        if (c == '-') { pos[0]++; return -evalUnary(s, pos); }
        if (c == '(') {
            pos[0]++;
            double v = evalAdd(s, pos);
            skipWs(s, pos);
            if (pos[0] < s.length() && s.charAt((int) pos[0]) == ')') pos[0]++;
            return v;
        }
        // 数字字面量
        Matcher m = NUM.matcher(s.substring((int) pos[0]));
        if (m.find() && m.start() == 0) {
            double v = Double.parseDouble(m.group(1));
            String suf = m.group(2).toUpperCase();
            switch (suf) {
                case "K": v *= 1e3; break;
                case "M": v *= 1e-3; break;
                case "U": v *= 1e-6; break;
                case "N": v *= 1e-9; break;
                case "P": v *= 1e-12; break;
                case "G": v *= 1e9; break;
                case "T": v *= 1e12; break;
                case "MEG": v *= 1e6; break;
                default: break;
            }
            pos[0] += m.end();
            return v;
        }
        // PI 常量
        if (s.regionMatches(true, (int) pos[0], "PI", 0, 2)) {
            pos[0] += 2;
            return Math.PI;
        }
        return Double.NaN;
    }

    private static void skipWs(String s, double[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt((int) pos[0]))) {
            pos[0]++;
        }
    }
}
