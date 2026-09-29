package com.hdf.cryptand.soc.board;

import java.util.List;

/**
 * ===== 芯片配置（2026-09-29，用户定案「所有包括芯片种类的等详细信息写入 CPU 中」）=====
 *
 * <p><b>纯数据</b>（零 MC 依赖，放 common）：它就是「一颗芯片是什么」的完整答案 ——
 * 类型（{@link ChipType}）、族（{@link SocCpuTiers.Family}）、ISA / 位宽（{@link SocIsa}）、
 * 主频、内存 / 存储、<b>模块集</b>（沙箱据此展开模块与外设）、配置连接表、扩展卡。</p>
 *
 * <p>生产方是 {@code ChipFactory}（CPU 制作工厂，见 common 的 {@code soc.factory} 包）；
 * 消费方是沙箱（读它展开）与 MC 侧物品（{@code SocSpec} 只是它的 NBT 载体 + UUID）。</p>
 *
 * <p>⚠ 单位口径：这里存的是 <b>MHz</b>；每 tick 预算 {@link #cyclesPerTick()} 由
 * {@code SocCpuTiers.specOfMhz} 唯一换算得出 —— 绝不出现第二份预算是怎么算的。</p>
 */
public record ChipConfig(ChipType type, SocCpuTiers.Family family, SocIsa isa, int mhz,
                         int ramKb, int flashKb, List<String> modules, List<String> links,
                         List<String> cards) {

    public ChipConfig {
        if (type == null) {
            throw new IllegalArgumentException("芯片类型（ChipType）必填");
        }
        if (type.needsIsa() && (family == null || isa == null)) {
            throw new IllegalArgumentException(type.label() + " 必须声明族（Family）与 ISA / 位宽");
        }
        if (family != null && (mhz < family.minMhz() || mhz > family.maxMhz())) {
            throw new IllegalArgumentException(family.label() + " 的频率必须在 "
                    + family.minMhz() + "–" + family.maxMhz() + " MHz 之间（给了 " + mhz + " MHz）");
        }
        if (mhz < 0 || ramKb < 0 || flashKb < 0) {
            throw new IllegalArgumentException("频率 / 内存 / 存储不允许负数");
        }
        modules = modules == null ? List.of() : List.copyOf(modules);
        links = links == null ? List.of() : List.copyOf(links);
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    /** 每 tick 指令预算（唯一换算处） */
    public int cyclesPerTick() {
        return SocCpuTiers.specOfMhz(mhz);
    }

    /** Cryptand 档位（1 基；非 CPU 类型没有族 ⇒ 0） */
    public int tier() {
        return family == null ? 0 : SocCpuTiers.tierOf(family);
    }

    /** 位宽（非 CPU 类型没有 ISA ⇒ 0） */
    public int xlen() {
        return isa == null ? 0 : isa.xlenBits();
    }

    /** 显示名：类型 · 族 · ISA */
    public String label() {
        final StringBuilder sb = new StringBuilder(type.label());
        if (family != null) {
            sb.append(" · ").append(family.label());
        }
        if (isa != null) {
            sb.append(" · ").append(isa.label());
        }
        return sb.toString();
    }

    /** 等效主频（Hz；按 20 tick/s 估算，UI 显示用） */
    public int effectiveHz() {
        return cyclesPerTick() * 20;
    }

    /** 扩展开销：卡越多预算打折（模拟总线共享；最低 60%） */
    public int effectiveCyclesPerTick() {
        final int penalty = Math.min(40, cards.size() * 10);
        return Math.max(1_000, cyclesPerTick() * (100 - penalty) / 100);
    }

    // ==================== 派生副本（不改变其余字段）====================

    public ChipConfig withModules(List<String> m) {
        return new ChipConfig(type, family, isa, mhz, ramKb, flashKb, m, links, cards);
    }

    public ChipConfig withLinks(List<String> l) {
        return new ChipConfig(type, family, isa, mhz, ramKb, flashKb, modules, l, cards);
    }

    public ChipConfig withMhz(int newMhz) {
        return new ChipConfig(type, family, isa, newMhz, ramKb, flashKb, modules, links, cards);
    }
}
