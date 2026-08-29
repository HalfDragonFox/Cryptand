package com.hdf.cryptand.circuitsimulation.export;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 虚拟电路端子 key 解析（2026-08-22 核心导出扩展：纯核心、零 MC 依赖）。
 * <p>
 * 自管虚拟电路的 WirePoint key 由平台生成，统一为
 * "BBlockPos{x=0, y=0, z=14}#0" 或 "B(0,0,14)#0"（J 点前缀 J）。
 * 本解析器用正则从 key 字符串提取世界坐标/端子索引——导出原理图不需要
 * 检测实际 BE 模型，坐标就编码在虚拟电路数据的 key 里。
 */
public final class SchematicKeyParser {

    private static final Pattern NUM = Pattern.compile("-?\\d+");

    private SchematicKeyParser() {
    }

    /** 是否方块端子点（B 前缀；设备方块点 true） */
    public static boolean isBlockKey(String key) {
        return key != null && !key.isEmpty() && key.charAt(0) == 'B';
    }

    /** 是否设备/接线端子点（B 或 J 前缀） */
    public static boolean isTerminalKey(String key) {
        if (key == null || key.isEmpty()) return false;
        char c = key.charAt(0);
        return c == 'B' || c == 'J';
    }

    /** 方块 key（去掉 #端子索引；"BBlockPos{...}#0" → "BBlockPos{...}"；无 # 原样） */
    public static String blockKeyOf(String key) {
        if (key == null) return null;
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    /** 端子索引（B/J 方块点；解析失败 -1） */
    public static int termOf(String key) {
        try {
            if (!isTerminalKey(key)) return -1;
            int hash = key.indexOf('#');
            if (hash < 0) return -1;
            return Integer.parseInt(key.substring(hash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * key → [x, y, z]；失败 null。
     * 兼容 "BBlockPos{x=0, y=0, z=14}#0" 与 "B(0,0,14)#0"（J 前缀同）。
     */
    public static int[] xyzOf(String key) {
        try {
            if (!isTerminalKey(key)) return null;
            String body = key.substring(1);
            int hash = body.indexOf('#');
            String posStr = hash >= 0 ? body.substring(0, hash) : body;
            Matcher m = NUM.matcher(posStr);
            int[] out = new int[3];
            int i = 0;
            while (m.find() && i < 3) out[i++] = Integer.parseInt(m.group());
            return i == 3 ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }
}