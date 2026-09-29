package com.hdf.cryptand.soc.part;

/**
 * ===== 容量档位沙盒自测（纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-25）：EEPROM 4K/8K/16K/32K/64K/128K；存储 4K 起每档翻倍到 1GB。
 * 这张表是物品注册、盘容量、命令/AI 解析的**同一份**来源，所以它自己必须先被钉死。</p>
 */
public final class SocCapacitiesSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== 容量档位自测（common，无 MC）===");

        // ---- 1. EEPROM 档位 = 用户定的六种 ----
        check("EEPROM 六档",
                java.util.Arrays.equals(SocCapacities.EEPROM_KB, new int[]{4, 8, 16, 32, 64, 128}));
        check("EEPROM 档位每档翻倍",
                SocCapacities.EEPROM_KB[0] * (1 << (SocCapacities.EEPROM_KB.length - 1))
                        == SocCapacities.EEPROM_KB[SocCapacities.EEPROM_KB.length - 1]);
        check("OC 原版的 8KB 在我们的表里（对齐用）", SocCapacities.isEepromTier(8));

        // ---- 2. 存储档位：4K 起每档翻倍、到 1GB ----
        final int[] s = SocCapacities.STORAGE_KB;
        check("存储第一档 = 4K", s[0] == 4);
        check("存储最后一档 = 1GB（1048576 KB）", s[s.length - 1] == 1024 * 1024);
        check("存储共 19 档（4K…1G）", s.length == 19);
        boolean doubling = true;
        for (int i = 1; i < s.length; i++) {
            doubling &= s[i] == s[i - 1] * 2;
        }
        check("存储档位严格翻倍（无缺档、无重复）", doubling);
        check("4K/64K/1M/1G 都在表里",
                SocCapacities.isStorageTier(4) && SocCapacities.isStorageTier(64)
                        && SocCapacities.isStorageTier(1024) && SocCapacities.isStorageTier(1024 * 1024));
        check("非档位容量能被识别出来（不静默取近似）", !SocCapacities.isStorageTier(100));

        // ---- 3. 标签 / id 后缀 ----
        check("标签：4K / 64K", "4K".equals(SocCapacities.label(4)) && "64K".equals(SocCapacities.label(64)));
        check("标签：1M / 512M", "1M".equals(SocCapacities.label(1024))
                && "512M".equals(SocCapacities.label(512 * 1024)));
        check("标签：1G", "1G".equals(SocCapacities.label(1024 * 1024)));
        boolean uniqueIds = true;
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (final int kb : s) {
            uniqueIds &= ids.add(SocCapacities.idSuffix(kb));
        }
        check("id 后缀在整个存储序列里唯一（物品 id 不能撞）", uniqueIds);
        check("id 后缀小写：4k / 1g",
                "4k".equals(SocCapacities.idSuffix(4)) && "1g".equals(SocCapacities.idSuffix(1024 * 1024)));

        // ---- 4. 解析（命令 / AI 工具共用）----
        check("解析 64k ⇒ 64", SocCapacities.parse("64k") == 64);
        check("解析 1M ⇒ 1024", SocCapacities.parse("1M") == 1024);
        check("解析 1g ⇒ 1048576", SocCapacities.parse("1g") == 1024 * 1024);
        check("解析裸数字按 KB", SocCapacities.parse("256") == 256);
        check("解析 512mb ⇒ 512*1024", SocCapacities.parse("512mb") == 512 * 1024);
        boolean bad = false;
        try {
            SocCapacities.parse("abc");
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check("非法容量明确报错（不静默返回 0）", bad);

        // ---- 5. 最大档能真的建成盘：FAT 类型由容量硬判定（不静默降级） ----
        check("1GB 档落到 FAT32（autoFat 硬判定）",
                com.hdf.cryptand.soc.fs.DiskFormatter.autoFat(1024L * 1024 * 1024).name().equals("FAT32"));
        check("4K 档落到 FAT12", com.hdf.cryptand.soc.fs.DiskFormatter
                .autoFat(4L * 1024).name().equals("FAT12"));

        System.out.println("[CAP] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
