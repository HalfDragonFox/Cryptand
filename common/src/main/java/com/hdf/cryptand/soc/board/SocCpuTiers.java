package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 处理器档位表（**common 单一来源**，2026-09-26 定案）=====
 *
 * <p>用户定案（2026-09-25/26）："MCU 从 1 MHz 到最高 64 MHz，SOC 从 32 MHz 到最高 1 GHz，
 * CPU 从 500 MHz 到最高 2 GHz"，"档位表放 common 单一来源"，"档位区间允许重叠 ——
 * 各族内部外设/模块集不同，档位本质是【频率 + 外设/模块集】"。</p>
 *
 * <p>⚠ 为什么必须放在 common：这份表同时被三处读 —— 物品注册（NeoForge 侧）、
 * 宿主 BIOS/沙箱装配（按档位决定设备集与接入方式）、以及离线闸门（不需要 MC 就能断言
 * "每族每档的频率/换算/唯一性"）。三处各抄一份，迟早会出现"物品说 1 GHz、沙箱按 100 MHz 跑"。</p>
 *
 * <h3>换算口径（唯一）</h3>
 * <p>{@code spec = MHz × 50_000}（MC 每秒 20 tick ⇒ 每 tick 预算 = MHz × 1e6 / 20）。
 * 反过来 {@code MHz = spec / 50_000}。这一条以前散落在 SocContent 的注释里，
 * 现在只在这里定义。</p>
 *
 * <h3>档位与"外设集"的关系</h3>
 * <p>档位不只是频率，还是**模块集**：MCU 少外设、无 GPU/网络；SOC 中档起带显示与网络；
 * CPU 齐全（含 PCIe 根复合体）。{@link Family#hasPcie()} / {@link Family#hasGpu()} 等
 * 就是这套区分的最小表达，供 BIOS/装配按档位决定"这台机器上有什么"。</p>
 */
public final class SocCpuTiers {

    /** 处理器族（决定档位区间、位宽集合与模块集） */
    public enum Family {

        /** 微控制器：1–64 MHz；只有 RV32（用户定案：MCU 定位是单片机，没有 64 位变体） */
        MCU("mcu", 1, 64, false, false, false, "MCU"),

        /** 应用处理器：32–1000 MHz；RV32 + RV64；带显示与网络 */
        SOC("soc", 32, 1000, true, true, false, "SOC"),

        /** 高频处理器：500–2000 MHz；RV32 + RV64；设备最全（含 PCIe 根复合体） */
        CPU("cpu", 500, 2000, true, true, true, "CPU");

        private final String idPrefix;
        private final int minMhz;
        private final int maxMhz;
        private final boolean hasGpu;
        private final boolean hasNetwork;
        private final boolean hasPcie;
        private final String label;

        Family(String idPrefix, int minMhz, int maxMhz, boolean hasGpu, boolean hasNetwork,
               boolean hasPcie, String label) {
            this.idPrefix = idPrefix;
            this.minMhz = minMhz;
            this.maxMhz = maxMhz;
            this.hasGpu = hasGpu;
            this.hasNetwork = hasNetwork;
            this.hasPcie = hasPcie;
            this.label = label;
        }

        public String idPrefix() {
            return idPrefix;
        }

        public int minMhz() {
            return minMhz;
        }

        public int maxMhz() {
            return maxMhz;
        }

        /** 带图形/显示模块（SOC 起） */
        public boolean hasGpu() {
            return hasGpu;
        }

        /** 带网络模块（SOC 起） */
        public boolean hasNetwork() {
            return hasNetwork;
        }

        /** 带 PCIe 根复合体（**只有 CPU 档有** —— 与 decree §五 的"CPU 仅 PCIe"一致） */
        public boolean hasPcie() {
            return hasPcie;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一个档位：族 + 序号 + 频率 + 位宽。
     *
     * @param id      物品 id（{@code <族前缀><序号>_<位宽>}，如 {@code cpu2_32}）——
     *                命名规范来自用户定案（RISC-V 用 _32/_64，非 RISC-V 用架构族名后缀）
     * @param xlen    位宽（32 / 64 / 8…）；与 id 后缀一一对应
     */
    public record Tier(Family family, int index, int mhz, int xlen) {

        /** 每 tick 指令预算（宿主与沙箱共用这一个换算） */
        public int spec() {
            return mhz * 50_000;
        }

        public String id() {
            return family.idPrefix() + index + "_" + xlen;
        }

        /** 档位序号越大越快（同一族内） */
        public boolean fasterThan(Tier other) {
            return mhz > other.mhz;
        }
    }

    /** MHz → 每 tick 预算（唯一换算函数：宿主、固件、工具都调它，不许各写一遍） */
    public static int specOfMhz(int mhz) {
        return mhz * 50_000;
    }

    /** 每 tick 预算 → MHz（整数除法，向下取整；与 {@link #specOfMhz} 互为逆） */
    public static int mhzOfSpec(int spec) {
        return spec / 50_000;
    }

    /**
     * 族 → Cryptand 档位（1 基）：MCU=1 / SOC=3 / CPU=4。
     *
     * <p><b>唯一来源</b>：物品注册（{@code SocContent}）与成品芯片规格（{@code SocSpec}）都调它，
     * 绝不再各写一份映射 —— 2026-09-29 之前这处在 {@code SocContent} 是私有方法，成品芯片只好另编一套
     * 「按频率分 1..5 档」的口径，同一槽位上两种物品的 tier 语义不一致（已合并）。</p>
     */
    public static int tierOf(Family family) {
        if (family == null) {
            throw new IllegalArgumentException("family must not be null");
        }
        return switch (family) {
            case MCU -> 1;
            case SOC -> 3;
            case CPU -> 4;
        };
    }

    /**
     * 全部档位（按 族 → 频率升序）。
     *
     * <p>MCU 只有 32 位；SOC / CPU 每档同时给 32 与 64 位两个变体
     * （64 位内核当前未实现 —— 那是"明确拒绝开机"，不是缺档位）。</p>
     */
    private static final List<Tier> ALL = build();

    private static List<Tier> build() {
        final List<Tier> out = new ArrayList<>();
        // MCU：1 → 64 MHz（1/4/8/16/32/64，翻倍铺开）
        int index = 1;
        for (int mhz : new int[]{1, 4, 8, 16, 32, 64}) {
            out.add(new Tier(Family.MCU, index++, mhz, 32));
        }
        // SOC：32 → 1000 MHz
        index = 1;
        for (int mhz : new int[]{32, 64, 128, 256, 500, 1000}) {
            out.add(new Tier(Family.SOC, index, mhz, 32));
            out.add(new Tier(Family.SOC, index, mhz, 64));
            index++;
        }
        // CPU：500 → 2000 MHz
        index = 1;
        for (int mhz : new int[]{500, 1000, 1500, 2000}) {
            out.add(new Tier(Family.CPU, index, mhz, 32));
            out.add(new Tier(Family.CPU, index, mhz, 64));
            index++;
        }
        return List.copyOf(out);
    }

    public static List<Tier> all() {
        return ALL;
    }

    /** 某一族的档位（频率升序） */
    public static List<Tier> of(Family family) {
        final List<Tier> out = new ArrayList<>();
        for (final Tier t : ALL) {
            if (t.family() == family) {
                out.add(t);
            }
        }
        return out;
    }

    /** 按物品 id 找档位；找不到返回 null（调用方据此明确报错，不猜） */
    public static Tier byId(String id) {
        for (final Tier t : ALL) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    private SocCpuTiers() {
    }
}
