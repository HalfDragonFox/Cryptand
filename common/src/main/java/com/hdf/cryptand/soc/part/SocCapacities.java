package com.hdf.cryptand.soc.part;

/**
 * ===== 部件容量档位（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-25）："EEPROM 提供 4kb、8kb、16kb、32kb、64kb、128kb 这几种；
 * 存储这样：提供 4K 开始，8K、16K、32K、64K… 一直到 1GB"。</p>
 *
 * <p>⚠ 为什么放 common：容量档位同时决定三件事 ——
 * ① 物品注册（MC 侧注册哪些 {@code hdd_*}/{@code eeprom_*}）；
 * ② 盘的容量（分区表、格式化时的 FAT 选择、配额判定）；
 * ③ 命令与 AI 工具的解析（{@code hdd_64k} ⇒ 64 KB）。
 * 三处各写一份，迟早出现"工具给的容量和手上那块盘对不上"。</p>
 *
 * <p>参考：<b>OC 原版 EEPROM = 8192 字节 = 8 KB</b>
 * （{@code OpenComputers/src/main/resources/application.conf:142  eepromSize: 8192}，
 * 由 {@code Settings.scala:57} 读取、可在配置里改）。我们的 8K 档与它同容量。</p>
 */
public final class SocCapacities {

    /** EEPROM 档位（KB）：4 / 8 / 16 / 32 / 64 / 128 —— 用户定案的那六种 */
    public static final int[] EEPROM_KB = {4, 8, 16, 32, 64, 128};

    /**
     * 存储（硬盘）档位（KB）：4K 起，每档翻倍，直到 1 GB —— 共 19 档。
     *
     * <p>4 / 8 / 16 / 32 / 64 / 128 / 256 / 512 KB → 1 / 2 / 4 … 512 MB → 1 GB。</p>
     */
    public static final int[] STORAGE_KB = powersOfTwo(4, 1024 * 1024);

    private SocCapacities() {
    }

    /** 从 {@code firstKb} 起每档翻倍，直到（含）{@code lastKb} */
    private static int[] powersOfTwo(int firstKb, int lastKb) {
        final int[] tmp = new int[32];
        int n = 0;
        for (long kb = firstKb; kb <= lastKb && n < tmp.length; kb <<= 1) {
            tmp[n++] = (int) kb;
        }
        return java.util.Arrays.copyOf(tmp, n);
    }

    /** 容量标签：{@code 4K} / {@code 64K} / {@code 1M} / {@code 512M} / {@code 1G} */
    public static String label(int kb) {
        if (kb <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + kb);
        }
        if (kb < 1024) {
            return kb + "K";
        }
        if (kb < 1024 * 1024) {
            return (kb / 1024) + "M";
        }
        return (kb / (1024 * 1024)) + "G";
    }

    /** 物品 id 后缀（小写）：{@code 4k} / {@code 64k} / {@code 1m} / {@code 1g} */
    public static String idSuffix(int kb) {
        return label(kb).toLowerCase(java.util.Locale.ROOT);
    }

    /** 解析容量：{@code 64k} / {@code 64K} / {@code 1m} / {@code 1g} / {@code 1024}（无后缀 = KB） */
    public static int parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty capacity");
        }
        final String s = text.trim().toLowerCase(java.util.Locale.ROOT);
        long mult = 1;
        String digits = s;
        if (s.endsWith("kb")) {
            digits = s.substring(0, s.length() - 2);
        } else if (s.endsWith("k")) {
            digits = s.substring(0, s.length() - 1);
        } else if (s.endsWith("mb")) {
            digits = s.substring(0, s.length() - 2);
            mult = 1024;
        } else if (s.endsWith("m")) {
            digits = s.substring(0, s.length() - 1);
            mult = 1024;
        } else if (s.endsWith("gb")) {
            digits = s.substring(0, s.length() - 2);
            mult = 1024L * 1024L;
        } else if (s.endsWith("g")) {
            digits = s.substring(0, s.length() - 1);
            mult = 1024L * 1024L;
        }
        final long value;
        try {
            value = Long.parseLong(digits.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid capacity: '" + text + "'");
        }
        final long kb = value * mult;
        if (kb <= 0 || kb > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("capacity out of range: '" + text + "'");
        }
        return (int) kb;
    }

    /** 这个容量是不是表里的档位（用来提示"没有这一档"而不是静默取近似值） */
    public static boolean isStorageTier(int kb) {
        for (final int v : STORAGE_KB) {
            if (v == kb) {
                return true;
            }
        }
        return false;
    }

    /** 这个容量是不是 EEPROM 档位 */
    public static boolean isEepromTier(int kb) {
        for (final int v : EEPROM_KB) {
            if (v == kb) {
                return true;
            }
        }
        return false;
    }
}
