/**
 * ===== 自管端点 key 解析工具（2026-08-14 修复系统性格式 bug） =====
 *
 * 自管 WireGraph 的端点 key 由生成端统一为【格式 B】：
 *   - 方块端子：`"B" + BlockPos + "#" + terminal` → `"BBlockPos{x=0, y=0, z=14}#0"`
 *   - 接线端子：`"J" + BlockPos` → `"JBlockPos{x=0, y=0, z=12}"`（term=-1）
 * 生成端：CryptandWirePlacement、PowerGridWireConverter.pointOf、PhasorEngine、
 * PhasorNetworkBuilder.posKeyOf、PhasorWriteback.posKeyOf、TerminalRegistry。
 *
 * ⚠ 2026-08-14 修复：消费端此前误用旧括号格式 `"B(0,0,14)#0)"` 解析（startsWith
 * "B("）→ 全部失败：渲染同步 edges=0（放置后看不到线）、自管写回/频率反查/设备
 * 移除清理失效。本工具用正则提取数字，兼容两种格式（防历史数据/诊断残留）。
 *
 * 纯工具，无依赖。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.core.BlockPos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WireKeyUtil {

    private static final Pattern NUM = Pattern.compile("-?\\d+");

    private WireKeyUtil() {
    }

    /** key 是否方块端子点（B 前缀；J 点 false） */
    public static boolean isBlock(String key) {
        return key != null && !key.isEmpty() && key.charAt(0) == 'B';
    }

    /** key → 方块位置；失败 null。兼容 "BBlockPos{x=0, y=0, z=14}#0" 与 "B(0,0,14)#0"。 */
    public static BlockPos posOf(String key) {
        int[] xyz = xyzOf(key);
        return xyz == null ? null : new BlockPos(xyz[0], xyz[1], xyz[2]);
    }

    /** 复合元件 key（"D"+BlockPos、"R"+、"C"+、"L"+、"M"+、"T"+…）→ BlockPos；
     *  格式如 DBlockPos{x=6, y=71, z=-26}（无 #端子）；失败 null。 */
    public static BlockPos posOfComposite(String key) {
        try {
            if (key == null || key.length() < 2) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("x=(-?\\d+)\\s*,\\s*y=(-?\\d+)\\s*,\\s*z=(-?\\d+)")
                    .matcher(key);
            if (m.find()) {
                return new BlockPos(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** key → 端子索引；J 点/解析失败 → -1 */
    public static int termOf(String key) {
        try {
            if (!isBlock(key)) return -1;
            int hash = key.indexOf('#');
            if (hash < 0) return -1;
            return Integer.parseInt(key.substring(hash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** key → [x, y, z]；失败 null */
    public static int[] xyzOf(String key) {
        try {
            if (key == null || key.isEmpty()) return null;
            char prefix = key.charAt(0);
            if (prefix != 'B' && prefix != 'J') return null;
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
