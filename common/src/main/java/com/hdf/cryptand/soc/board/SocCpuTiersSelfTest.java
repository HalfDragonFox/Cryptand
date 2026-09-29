package com.hdf.cryptand.soc.board;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ===== 处理器档位表自测（离线，2026-09-26）=====
 *
 * <p>跑法：{@code gradlew :common:runCpuTierTest}</p>
 *
 * <p>为什么值得单独一个闸门：档位表是**三处共用的单一来源**（物品注册 / 宿主装配 / 工具显示），
 * 而它的错误形态都很安静 —— 少一档、频率写错一个 0、id 撞名、位宽与后缀不符。
 * 这些东西上了真机只表现为"某某档位看不到"或"频率感觉不对"，极难定位。</p>
 */
public final class SocCpuTiersSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== Soc CPU tier table self test, no MC ===");

        // ---- 1. 三族区间与用户定案一致 ----
        checkFamily(SocCpuTiers.Family.MCU, 1, 64);
        checkFamily(SocCpuTiers.Family.SOC, 32, 1000);
        checkFamily(SocCpuTiers.Family.CPU, 500, 2000);

        // ---- 2. 换算互逆（spec = MHz × 50_000）----
        for (final int mhz : new int[]{1, 4, 32, 500, 1000, 2000}) {
            final int spec = SocCpuTiers.specOfMhz(mhz);
            check("换算互逆 " + mhz + " MHz <-> spec " + spec,
                    spec == mhz * 50_000 && SocCpuTiers.mhzOfSpec(spec) == mhz);
        }
        for (final SocCpuTiers.Tier t : SocCpuTiers.all()) {
            check("档位 " + t.id() + " 的 spec 与 MHz 自洽（" + t.mhz() + " MHz）",
                    t.spec() == t.mhz() * 50_000 && SocCpuTiers.mhzOfSpec(t.spec()) == t.mhz());
        }

        // ---- 3. id 唯一 + 命名规范（_32 / _64 后缀与 xlen 一致）----
        final Set<String> ids = new HashSet<>();
        for (final SocCpuTiers.Tier t : SocCpuTiers.all()) {
            check("id 唯一：" + t.id(), ids.add(t.id()));
            check("id 后缀与位宽一致：" + t.id(), t.id().endsWith("_" + t.xlen()));
            check("id 前缀属于本族的族名：" + t.id(), t.id().startsWith(t.family().idPrefix()));
        }
        check("按 id 反查可用", SocCpuTiers.byId("cpu1_32") != null
                && SocCpuTiers.byId("cpu1_32").mhz() == 500);
        check("未知 id 返回 null（不猜）", SocCpuTiers.byId("cpu9_32") == null);

        // ---- 4. 每族的位宽集合符合定案（MCU 只有 32 位）----
        for (final SocCpuTiers.Tier t : SocCpuTiers.of(SocCpuTiers.Family.MCU)) {
            check("MCU 档只有 RV32：" + t.id(), t.xlen() == 32);
        }
        final List<SocCpuTiers.Tier> soc = SocCpuTiers.of(SocCpuTiers.Family.SOC);
        check("SOC 每档同时有 32 与 64 位",
                soc.size() % 2 == 0 && soc.stream().anyMatch(t -> t.xlen() == 64));
        final List<SocCpuTiers.Tier> cpu = SocCpuTiers.of(SocCpuTiers.Family.CPU);
        check("CPU 每档同时有 32 与 64 位",
                cpu.size() % 2 == 0 && cpu.stream().anyMatch(t -> t.xlen() == 64));
        check("CPU 覆盖 500 / 1000 / 1500 / 2000 MHz", cpu.stream().anyMatch(t -> t.mhz() == 500)
                && cpu.stream().anyMatch(t -> t.mhz() == 1000)
                && cpu.stream().anyMatch(t -> t.mhz() == 1500)
                && cpu.stream().anyMatch(t -> t.mhz() == 2000));
        check("CPU 最高 2000 MHz（用户定案上限）",
                SocCpuTiers.of(SocCpuTiers.Family.CPU).stream()
                        .allMatch(t -> t.mhz() <= 2000));

        // ---- 5. 模块集按族递进（MCU 无 GPU/网络/PCIe；CPU 全有）----
        check("MCU 无 GPU / 网络 / PCIe",
                !SocCpuTiers.Family.MCU.hasGpu() && !SocCpuTiers.Family.MCU.hasNetwork()
                        && !SocCpuTiers.Family.MCU.hasPcie());
        check("SOC 有 GPU 与网络、无 PCIe 根复合体？—— 按 decree：SOC 也可以有 PCIe 设备",
                SocCpuTiers.Family.SOC.hasGpu() && SocCpuTiers.Family.SOC.hasNetwork());
        check("CPU 全有（含 PCIe 根复合体）",
                SocCpuTiers.Family.CPU.hasGpu() && SocCpuTiers.Family.CPU.hasNetwork()
                        && SocCpuTiers.Family.CPU.hasPcie());

        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void checkFamily(SocCpuTiers.Family f, int min, int max) {
        final List<SocCpuTiers.Tier> tiers = SocCpuTiers.of(f);
        check(f.label() + " 有档位（" + tiers.size() + " 个）", !tiers.isEmpty());
        check(f.label() + " 最低 = " + min + " MHz", tiers.get(0).mhz() == min);
        check(f.label() + " 最高 = " + max + " MHz", tiers.get(tiers.size() - 1).mhz() == max);
        check(f.label() + " 声明区间 = " + min + ".." + max,
                f.minMhz() == min && f.maxMhz() == max);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
