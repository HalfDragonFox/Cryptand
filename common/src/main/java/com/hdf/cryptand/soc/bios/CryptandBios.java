package com.hdf.cryptand.soc.bios;

import com.hdf.cryptand.soc.os.BootPlan;
import com.hdf.cryptand.soc.os.Programs;

import java.util.List;
import java.util.function.Consumer;

/**
 * ===== Cryptand BIOS（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-24）："EEPROM 这个改名叫做 Cryptand BIOS 更合适。这个 BIOS 可以支持官方
 * lua 或者我们的启动，或者以后定义其他芯片了都能进行支持，就和真正的 BIOS 一样，
 * 独立的程序独立的芯片"，以及"EEPROM 可以不进入沙箱，纯 java"。</p>
 *
 * <h3>与真机 BIOS 的对应</h3>
 * <ul>
 *   <li><b>不进沙箱</b>：它不跑在 guest CPU 上，所以不受 guest 代码影响
 *       （项目刚吃过这个亏：guest 的心跳任务把计数器写进了组件桥 ABI 区，害得引导请求被
 *       反复兑现 300 次，查了十几轮）；</li>
 *   <li><b>与架构解耦</b>：BIOS 只依赖 {@link BootTarget} 这个很窄的接口 ——
 *       "把一段程序交给某个架构并启动它"。RV32 是一种实现，官方 Lua 是另一种，
 *       将来的芯片再加一种，BIOS 本身不动；</li>
 *   <li><b>排序规则只有一份</b>：软盘优先 → 槽位升序 → 第一个真有程序的盘，
 *       由 {@link BootPlan#choose} 唯一定义（用户规格："先从软盘开始，再搜索硬盘，
 *       然后对第一个有效程序进行加载"）。</li>
 * </ul>
 *
 * <p>⚠ 为什么它在本项目里必须"宿主侧"：引导阶段的日志/断点/单测全在 Java 侧才顺手。
 * 之前 BIOS 是沙箱里的 RV32 程序时，我们花了十几轮才靠"调试口 + 计数标记"看清它的行为。</p>
 */
public final class CryptandBios {

    /**
     * 一个可引导目标：把"要跑的程序"交给**某个 CPU 架构**并启动它。
     *
     * <p>实现方（MC 侧）才知道"怎么把字节写进 guest"：RV32 是刷 ROM 系统区 + 设置入口，
     * 官方 Lua 是把程序交给 Lua 解释器。BIOS 不关心这些差别。</p>
     */
    public interface BootTarget {

        /** 架构名（日志与诊断用，如 {@code rv32imac} / {@code lua}） */
        String archName();

        /**
         * 启动决策里的程序。
         *
         * @return 是否成功交出控制权（false ⇒ BIOS 继续尝试下一块盘，或最终失败）
         */
        boolean boot(BootPlan.Decision decision);
    }

    /** 设备来源：这台机器上有哪些盘（MC 侧把 ItemStack 翻译成 {@link BootPlan.Disk}） */
    public interface DeviceSource {

        List<BootPlan.Disk> disks();

        /**
         * 这台机器上承载引导记录的 **EEPROM 容量（KB）** —— 引导记录的字节上限由它决定
         * （见 {@link BootPlan#limitBytes}）。
         *
         * <p>默认按最大档：不是每台机器都插了我们的 EEPROM 部件（OC 原版机箱、老存档），
         * 而那些机器此前是能开机的 —— "按最大档放行"比"升级后突然不可引导"合理；
         * 引导区 64KB 这条物理边界仍然在拦。</p>
         */
        default int eepromKb() {
            return BootPlan.defaultEepromKb();
        }
    }

    /** 一次引导尝试的结果（给日志/UI/MCP 工具回显） */
    public record Result(BootPlan.Decision decision, String arch, boolean started) {
    }

    private CryptandBios() {
    }

    /**
     * 走一遍 BIOS 流程：枚举设备 → 按 boot order 选盘 → 交给架构启动。
     *
     * @param log 诊断输出（可为 null）；BIOS 的每一步都可观测，这是它待在宿主侧的主要理由之一
     */
    public static Result run(DeviceSource source, BootTarget target, Consumer<String> log) {
        return run(source, target, BiosConfig.defaults(), log);
    }

    /**
     * 带 BIOS 配置（= CMOS）的引导。
     *
     * <p>配置的作用与真机 BIOS Setup 一致：换启动顺序、或强制从某个槽位启动。
     * 强制槽位**优先于** boot order，但那一槽没有可引导程序时会退回 boot order
     * （并打日志）—— 静默改用别的盘是最容易让人以为"配置没生效"的行为。</p>
     */
    public static Result run(DeviceSource source, BootTarget target, BiosConfig config, Consumer<String> log) {
        final Consumer<String> out = log == null ? s -> {
        } : log;
        final BiosConfig cfg = config == null ? BiosConfig.defaults() : config;
        final List<BootPlan.Disk> disks = source.disks();
        out.accept("Cryptand BIOS: " + disks.size() + " drive(s), arch=" + target.archName()
                + ", order=" + cfg.order() + (cfg.hasForcedSlot() ? ", forcedSlot=" + cfg.forcedSlot() : "")
                + ", eeprom=" + source.eepromKb() + "KB (loader limit " + BootPlan.limitBytes(source.eepromKb())
                + " bytes)");
        if (disks.isEmpty()) {
            out.accept("Cryptand BIOS: no drive in the case - no bootable device");
            return new Result(null, target.archName(), false);
        }
        BootPlan.Decision decision = null;
        if (cfg.hasForcedSlot()) {
            for (final BootPlan.Disk d : disks) {
                if (d.slot() == cfg.forcedSlot()) {
                    // ⚠ 走 BootPlan.on：强制槽位与 boot order 扫描必须用**同一份**阶段选择逻辑
                    decision = BootPlan.on(d, source.eepromKb());
                    break;
                }
            }
            if (decision == null) {
                out.accept("Cryptand BIOS: forced slot " + cfg.forcedSlot()
                        + " has no bootable program - falling back to boot order");
            }
        }
        if (decision == null) {
            decision = BootPlan.choose(disks, cfg.order(), source.eepromKb());
        }
        if (decision == null) {
            // 判据只有"盘上有引导记录"一条之后，"不可引导"有好几种成因 —— 逐盘说出来。
            // 现实中的 BIOS 只会讲 "no bootable device"，但用户需要知道该修哪一块盘。
            for (final BootPlan.Disk d : BootPlan.ordered(disks, cfg.order())) {
                out.accept("Cryptand BIOS: slot " + d.slot() + " (" + d.label() + ") -> "
                        + BootPlan.explain(d, source.eepromKb()));
            }
            out.accept("Cryptand BIOS: no bootable device — 盘上需要引导记录 " + Programs.LOADER_PATH);
            return new Result(null, target.archName(), false);
        }
        out.accept("Cryptand BIOS: booting from slot " + decision.slot() + " (" + decision.label()
                + "), bootloader " + decision.program().length + " bytes"
                + " -> guest 0x" + Long.toHexString(decision.loadAddress()));
        final boolean started = target.boot(decision);
        out.accept("Cryptand BIOS: " + (started ? "control handed over" : "target refused to start"));
        return new Result(decision, target.archName(), started);
    }
}
