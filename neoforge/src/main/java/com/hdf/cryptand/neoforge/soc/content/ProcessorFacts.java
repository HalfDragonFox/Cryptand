package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.SocIsa;

import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocModule;
import com.hdf.cryptand.soc.board.SocModules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 处理器实况（2026-09-29，用户定案「所有模块 / 架构信息在 CPU 里」）=====
 *
 * <p>把「这颗 CPU 是什么」收敛成<b>一条读取路径</b>，两种处理器物品都走它：</p>
 * <ul>
 *   <li><b>逐档处理器物品</b>（{@link SocPartItem}，{@code kind = CHIP}）：族由物品 id 回查
 *       {@link SocCpuTiers#byId}，ISA 是构造期必填字段；</li>
 *   <li><b>组装台成品芯片</b>（{@link SocAssembledItem}）：族 / ISA / 模块集 / 配置连接全部来自它自己的
 *       {@link SocSpec}（数据组件），不靠 id 回查。</li>
 * </ul>
 *
 * <p>为什么必须统一：2026-09-29 之前的实况是 —— 开机（{@code CryptandOcArchitecture.initialize}）只认
 * {@link SocPartItem}，成品芯片被整段跳过（{@code cpuIsa} 恒 null ⇒ ISA 闸门失灵、照跑默认 RV32 内核，
 * {@code cpuMhz} 停默认值）；驱动侧又另编了一套「按频率分 1..5 档」。两条口径现在合并到本类。</p>
 *
 * <p><b>模块集因族而异</b>（{@code SocModules.supportedCarriers}）：MCU 仅 INTERNAL/UART/I2C/SPI/8080；
 * SOC 另加 QSPI/USB/DP/<b>PCIE</b>/MAILBOX；CPU 全部 —— 即「PCIE 只有 SOC 与 CPU 持有」。
 * 逐档物品的模块集由族推出（{@link #moduleNames}），成品芯片把同一份<b>快照</b>写进规格，
 * 因此换 CPU 就是换能力集。</p>
 */
public final class ProcessorFacts {

    private ProcessorFacts() {
    }

    /** 成品芯片的规格（不是成品芯片返回 null） */
    public static SocSpec assembledSpec(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof SocAssembledItem)) {
            return null;
        }
        return SocAssembledItem.spec(stack);
    }

    /** Cryptand 部件（非部件返回 null） */
    public static SocPartItem part(ItemStack stack) {
        return stack != null && stack.getItem() instanceof SocPartItem p ? p : null;
    }

    /** 这是「能插 OC CPU 槽的处理器」吗：处理器部件（CHIP）或成品芯片 */
    public static boolean isProcessor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final SocPartItem p = part(stack);
        if (p != null) {
            return p.kind() == SocPartKind.CHIP;
        }
        final SocSpec spec = assembledSpec(stack);
        // 只有 CPU 类型能进 CPU 槽（GPU 类型走显卡槽，见 ChipType）
        return spec != null && spec.type() == com.hdf.cryptand.soc.board.ChipType.CPU;
    }

    /** ISA / 位宽（缺失返回 null ⇒ 调用方明确报错，绝不猜） */
    public static SocIsa isa(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        final SocPartItem p = part(stack);
        if (p != null) {
            return p.isa();
        }
        final SocSpec spec = assembledSpec(stack);
        return spec == null || !spec.type().needsIsa() ? null : spec.isa();
    }

    /** 族（MCU / SOC / CPU）；取不到返回 null */
    public static SocCpuTiers.Family family(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        final SocPartItem p = part(stack);
        if (p != null) {
            // 逐档物品：id 就是档位 id（{@code SocCpuTiers.all()} 逐条注册），据此拿族
            final String path = BuiltInRegistries.ITEM.getKey(p).getPath();
            final SocCpuTiers.Tier t = SocCpuTiers.byId(path);
            return t == null ? null : t.family();
        }
        final SocSpec spec = assembledSpec(stack);
        return spec == null ? null : spec.family();
    }

    /** 每 tick 指令预算（未知返回 0） */
    public static int cyclesPerTick(ItemStack stack) {
        final SocPartItem p = part(stack);
        if (p != null) {
            return p.spec();
        }
        final SocSpec spec = assembledSpec(stack);
        return spec == null ? 0 : spec.cyclesPerTick();
    }

    /** 标称主频（整数 MHz；未知返回 0）—— 口径来自 {@code SocCpuTiers.mhzOfSpec} 唯一换算 */
    public static int mhz(ItemStack stack) {
        return SocCpuTiers.mhzOfSpec(cyclesPerTick(stack));
    }

    /** Cryptand 档位（1 基；MCU=1 / SOC=3 / CPU=4）—— 两种物品同一映射 */
    public static int tier(ItemStack stack) {
        final SocCpuTiers.Family family = family(stack);
        return family == null ? 1 : SocCpuTiers.tierOf(family);
    }

    /** 该族能装载的模块名（单一来源 {@code SocModules.of(family)}；族为 null 返回空表） */
    public static List<String> moduleNames(SocCpuTiers.Family family) {
        if (family == null) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final SocModule m : SocModules.of(family)) {
            out.add(m.name());
        }
        return out;
    }

    /**
     * 这颗 CPU 的模块集：逐档物品按族取（能力是族的属性），成品芯片取规格里的快照。
     * 两者同源（{@link SocModules#of(SocCpuTiers.Family)}），成品芯片只是把那一刻固化下来。
     */
    public static List<String> modules(ItemStack stack) {
        final SocSpec spec = assembledSpec(stack);
        if (spec != null) {
            return spec.modules();
        }
        return moduleNames(family(stack));
    }

    /** 配置连接表（成品芯片才有；逐档物品的连接存在实物链路层） */
    public static List<String> links(ItemStack stack) {
        final SocSpec spec = assembledSpec(stack);
        return spec == null ? List.of() : spec.links();
    }

    /** 人话描述（分析器 / 日志 / 工具提示共用；未知写「-」不猜） */
    public static String describe(ItemStack stack) {
        if (!isProcessor(stack)) {
            return "不是处理器";
        }
        final SocIsa isa = isa(stack);
        final SocCpuTiers.Family family = family(stack);
        return (family == null ? "族=?" : family.label())
                + " · " + (isa == null ? "ISA=?" : isa.label() + "（" + isa.xlenBits() + " 位）")
                + " · " + mhz(stack) + " MHz · 模块 " + modules(stack).size() + " 项";
    }

    // ==================== 离线闸门：:neoforge:runChipFactsTest ====================

    /**
     * 纯逻辑闸门（不碰 MC 世界、不需要客户端）：族→档位映射、MHz↔预算、族间模块集差异
     * （PCIE 只有 SOC/CPU）、ISA 回查、以及成品芯片规格的 NBT 往返。
     */
    public static void main(String[] args) {
        int pass = 0;
        final List<String> fail = new ArrayList<>();

        // 1. 族 → 档位（唯一映射）
        check(fail, "tier(MCU)=1", SocCpuTiers.tierOf(SocCpuTiers.Family.MCU) == 1);
        check(fail, "tier(SOC)=3", SocCpuTiers.tierOf(SocCpuTiers.Family.SOC) == 3);
        check(fail, "tier(CPU)=4", SocCpuTiers.tierOf(SocCpuTiers.Family.CPU) == 4);
        pass += 3;

        // 2. MHz ↔ 预算（唯一换算，互为逆）
        for (final int mhz : new int[]{1, 32, 500, 2000}) {
            check(fail, "spec/mhz round-trip " + mhz,
                    SocCpuTiers.mhzOfSpec(SocCpuTiers.specOfMhz(mhz)) == mhz);
            pass++;
        }

        // 3. 族间模块集不同：PCIE 只有 SOC 与 CPU 持有（用户 2026-09-18 定案）
        check(fail, "MCU 无 PCIE", !SocModules.supports(SocCpuTiers.Family.MCU,
                com.hdf.cryptand.soc.peripheral.PeripheralMap.Carrier.PCIE));
        check(fail, "SOC 有 PCIE", SocModules.supports(SocCpuTiers.Family.SOC,
                com.hdf.cryptand.soc.peripheral.PeripheralMap.Carrier.PCIE));
        check(fail, "CPU 有 PCIE", SocModules.supports(SocCpuTiers.Family.CPU,
                com.hdf.cryptand.soc.peripheral.PeripheralMap.Carrier.PCIE));
        pass += 3;
        for (final SocCpuTiers.Family f : SocCpuTiers.Family.values()) {
            check(fail, f + " 模块集非空", !SocModules.of(f).isEmpty());
            pass++;
        }
        check(fail, "CPU 模块集 ⊋ MCU 模块集",
                SocModules.of(SocCpuTiers.Family.CPU).size() > SocModules.of(SocCpuTiers.Family.MCU).size());
        pass++;

        // 4. ISA 回查（缺/错必须 null，绝不默认）
        check(fail, "byId(rv32im)", SocIsa.byId("rv32im") == SocIsa.RV32IM);
        check(fail, "byId(不存在)=null", SocIsa.byId("rv999") == null);
        check(fail, "byId(null)=null", SocIsa.byId(null) == null);
        check(fail, "RV32IM 可开机", SocIsa.RV32IM.kernelRunnable());
        check(fail, "RV64IMAC 不可开机", !SocIsa.RV64IMAC.kernelRunnable());
        check(fail, "MCS51 不可开机", !SocIsa.MCS51.kernelRunnable());
        pass += 5;

        // 5. 成品芯片规格 NBT 往返（族 / ISA / 模块 / 配置连接 全部可还原）
        // 芯片类型（ChipType）闸门：CPU 必带族+ISA；GPU 不吃族；byId 回查
        check(fail, "ChipType.byId(cpu)", com.hdf.cryptand.soc.board.ChipType.byId("cpu")
                == com.hdf.cryptand.soc.board.ChipType.CPU);
        check(fail, "ChipType.byId(gpu)", com.hdf.cryptand.soc.board.ChipType.byId("gpu")
                == com.hdf.cryptand.soc.board.ChipType.GPU);
        check(fail, "ChipType.byId(?) = null", com.hdf.cryptand.soc.board.ChipType.byId("npu") == null);
        check(fail, "CPU 类型需要 ISA", com.hdf.cryptand.soc.board.ChipType.CPU.needsIsa());
        check(fail, "GPU 类型不需要 ISA", !com.hdf.cryptand.soc.board.ChipType.GPU.needsIsa());
        pass += 5;
        try {
            new com.hdf.cryptand.soc.board.ChipConfig(com.hdf.cryptand.soc.board.ChipType.CPU,
                    null, null, 1000, 64, 64, List.of(), List.of(), List.of());
            check(fail, "CPU 类型缺族/ISA 必须抛异常", false);
        } catch (IllegalArgumentException expected) {
            pass++;
        }

        final SocSpec spec = SocSpec.of(new com.hdf.cryptand.soc.board.ChipConfig(
                com.hdf.cryptand.soc.board.ChipType.CPU, SocCpuTiers.Family.SOC,
                SocIsa.RV32IMAC, 256, 512, 4096,
                moduleNames(SocCpuTiers.Family.SOC), List.of("link:gpu0-dp1"), List.of("CARD_GPU")));
        final SocSpec back = SocSpec.fromTag(spec.toTag());
        check(fail, "SocSpec 往返非空", back != null);
        if (back != null) {
            check(fail, "往返 类型", back.type() == com.hdf.cryptand.soc.board.ChipType.CPU);
            check(fail, "往返 族", back.family() == SocCpuTiers.Family.SOC);
            check(fail, "往返 ISA", back.isa() == SocIsa.RV32IMAC);
            check(fail, "往返 MHz", back.mhz() == 256);
            check(fail, "往返 tier=3", back.tier() == 3);
            check(fail, "往返 位宽=32", back.xlen() == 32);
            check(fail, "往返 模块集", back.modules().equals(spec.modules()));
            check(fail, "往返 配置连接", back.links().equals(spec.links()));
            check(fail, "往返 扩展卡", back.cards().equals(spec.cards()));
            pass += 8;
        }
        // 缺 Family / Isa 必须 null（不猜架构）
        final net.minecraft.nbt.CompoundTag bare = new net.minecraft.nbt.CompoundTag();
        bare.putUUID("Id", java.util.UUID.randomUUID());
        check(fail, "缺 Type/Family/Isa ⇒ null", SocSpec.fromTag(bare) == null);
        pass++;

        System.out.println("CHIP-FACTS PASS " + pass + " / FAIL " + fail.size());
        for (final String f : fail) {
            System.out.println("  ✖ " + f);
        }
        if (!fail.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(List<String> fail, String name, boolean ok) {
        if (!ok) {
            fail.add(name);
        }
    }
}
