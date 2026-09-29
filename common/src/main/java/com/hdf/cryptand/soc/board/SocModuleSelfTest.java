package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.peripheral.PeripheralMap;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 模块清单闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>验证用户定案的模块模型：<b>虚拟机决定 RV 有哪些模块</b>，模块只有三要素
 * （接口 + 速率 + 寄存器映射）。要证明的事：</p>
 * <ol>
 *   <li>基础模块的地址与 {@link OcBoardLayout} 一一对应（不许另起一份）；</li>
 *   <li>模块集随档位变化（MCU 无 QSPI/PCIe、走 8080 驱屏；SOC 起有 GPU；CPU 才有 PCIe/USB）；</li>
 *   <li>三条装配硬规则真的会拦人：窗口重叠、档位不允许的接口、声明有 GPU 却没挂；</li>
 *   <li><b>模块清单与外设映射层的 IO 线预算接得上</b>：MCU 中档（32 线）装得下，
 *       低档（16 线）必须报错并写明差几根。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runSocModuleTest}</p>
 */
public final class SocModuleSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        final List<SocModule> base = SocModules.base();

        // ---- 1. 基础模块的地址必须来自 OcBoardLayout（单一来源）----
        check("基础模块 5 个", base.size() == 5);
        // ⚠ 2026-09-27：UART 从设备区 MMIO 改成 guest RAM 缓存 ⇒ 设备表登记的是缓存窗口
        check("UART0 基址 = OcBoardLayout.UART_CACHE_BASE（guest RAM 缓存，不再是 MMIO 寄存器组）",
                SocModules.byName(base, "UART0").baseAddress() == OcBoardLayout.UART_CACHE_BASE);
        check("UART0 窗口 = OcBoardLayout.UART_CACHE_BYTES",
                SocModules.byName(base, "UART0").spanBytes() == OcBoardLayout.UART_CACHE_BYTES);

        // ---- 1.5 通用 FIFO 模块（2026-09-27 用户定案）----
        // 用户原话："FIFO 也算是芯片模块的一种，可以虚拟机实现通用 FIFO 给实际芯片装载此功能，
        // 并且消耗对应组件资源"。⇒ 设备树里要写清"装载了多大的模块 + 占几个组件槽位"，
        // 装不下就**明确报错**（绝不静默降级成 1 字节 DR）。
        check("UART 装载的 FIFO 模块深度 = 设备布局值（单一来源）",
                SocModules.UART_FIFO_DEPTH == UartRegs.TX_BYTES);
        final java.util.List<SocModules.FifoModule> fifoMods = SocModules.fifoModules();
        check("装载的模块登记在设备树里（名/归属/深度）",
                fifoMods.size() == 1 && "UART0.FIFO".equals(fifoMods.get(0).name())
                        && "UART0".equals(fifoMods.get(0).owner())
                        && fifoMods.get(0).depth() == UartRegs.TX_BYTES);
        check("★ 模块计价 = PeripheralFifo.slotsFor（256 字节 ⇒ 7 个组件槽位），合计 "
                        + SocModules.fifoSlotCost() + " 槽",
                SocModules.fifoModules().get(0).slots() == com.hdf.cryptand.soc.peripheral.PeripheralFifo
                        .slotsFor(UartRegs.TX_BYTES)
                        && SocModules.fifoSlotCost() == 7);
        SocModules.validateFifoBudget(SocCpuTiers.Family.MCU, 16);
        check("★ MCU 16 槽装得下（预算够时静默通过）", true);
        boolean budgetRejected = false;
        String budgetMsg = "";
        try {
            SocModules.validateFifoBudget(SocCpuTiers.Family.MCU, 4);
        } catch (IllegalStateException ex) {
            budgetRejected = true;
            budgetMsg = ex.getMessage();
        }
        check("★ 槽位不够 ⇒ 明确报错（差 3 个），不静默降级", budgetRejected && budgetMsg.contains("差 3 个"));
        // ⑤ 组件槽位预算（能力集基准）：输入只有能力表 + 载体开销两样，都是单一来源 ⇒ 不许编数。
        //    面板「模块清单」的总账上限就是它（此前只能写 -）。
        int mcuBudget = 0;
        for (final PeripheralMap.Carrier c : SocModules.supportedCarriers(SocCpuTiers.Family.MCU)) {
            if (c != PeripheralMap.Carrier.PCIE) {
                mcuBudget += c.sharedSlots() + c.perDeviceSlots();
            }
        }
        check("★ MCU 槽位预算 = 能力集基准（逐载体累加，PCIe 不计）=" + mcuBudget,
                SocModules.slotBudget(SocCpuTiers.Family.MCU) == mcuBudget && mcuBudget > 0);
        // ⚠ 实测出来的事实（第一版断言写错了，被闸门抓住）：能力集**不是层层包含** ——
        //   SOC 用 QSPI(7)+USB(2)+DP(4)=13 换掉了 MCU 的 8080 并口(13) ⇒ MCU 与 SOC 的"单件基准"恰好相等(21)，
        //   只有 CPU（全集，另有 FSMC 24）才明显更大(58)。预算的含义是"挂满自己的能力集"，不是"档位越高越大"。
        check("★ CPU 的基准 > SOC（CPU 是全集）；SOC >= MCU（SOC 用 QSPI/USB/DP 换掉 8080）",
                SocModules.slotBudget(SocCpuTiers.Family.CPU) > SocModules.slotBudget(SocCpuTiers.Family.SOC)
                        && SocModules.slotBudget(SocCpuTiers.Family.SOC)
                        >= SocModules.slotBudget(SocCpuTiers.Family.MCU));
        check("★ PCIe 不计入预算：枚举里代价就是 0/0（逐卡按链路宽度算），预算不会漏它的账",
                PeripheralMap.Carrier.PCIE.sharedSlots() == 0
                        && PeripheralMap.Carrier.PCIE.perDeviceSlots() == 0
                        && SocModules.supportedCarriers(SocCpuTiers.Family.CPU)
                        .contains(PeripheralMap.Carrier.PCIE));
        check("TIMER0 基址 = OcBoardLayout.TIMER_BASE，且带 MTIP 中断 7",
                SocModules.byName(base, "TIMER0").baseAddress() == OcBoardLayout.TIMER_BASE
                        && SocModules.byName(base, "TIMER0").irq() == OcBoardLayout.IRQ_MTIP);
        check("HEARTBEAT0 跨度 = OcBoardLayout.HEARTBEAT_BYTES",
                SocModules.byName(base, "HEARTBEAT0").spanBytes() == OcBoardLayout.HEARTBEAT_BYTES);
        check("控制类模块速率为 0（定时器/调试口/心跳不受带宽限）",
                SocModules.byName(base, "TIMER0").controlOnly()
                        && SocModules.byName(base, "DEBUG0").controlOnly()
                        && SocModules.byName(base, "HEARTBEAT0").controlOnly());
        check("UART0 速率 = 波特率/10（8N1）", SocModules.byName(base, "UART0").bytesPerSecond()
                == OcBoardLayout.UART_BAUD / 10L);

        // ---- 2. 模块集随档位变化 ----
        final List<SocModule> mcu = SocModules.of(SocCpuTiers.Family.MCU);
        final List<SocModule> soc = SocModules.of(SocCpuTiers.Family.SOC);
        final List<SocModule> cpu = SocModules.of(SocCpuTiers.Family.CPU);

        check("MCU 有 8080 并口（它驱屏的方式）", SocModules.byName(mcu, "LCD8080") != null);
        check("MCU 没有 QSPI / PCIe / USB / GPU",
                SocModules.byName(mcu, "QSPI0") == null && SocModules.byName(mcu, "PCIE0") == null
                        && SocModules.byName(mcu, "USB0") == null && SocModules.byName(mcu, "GPU0") == null);
        check("SOC 有 GPU（经 QSPI）与网卡，但没有 PCIe/USB",
                SocModules.byName(soc, "GPU0") != null && SocModules.byName(soc, "GPU0").iface() == PeripheralMap.Carrier.QSPI
                        && SocModules.byName(soc, "NET0") != null && SocModules.byName(soc, "PCIE0") == null);
        check("CPU 有 PCIe 根复合体 + USB + GPU（GPU 走 PCIe）",
                SocModules.byName(cpu, "PCIE0") != null && SocModules.byName(cpu, "USB0") != null
                        && SocModules.byName(cpu, "GPU0").iface() == PeripheralMap.Carrier.PCIE);
        check("每个档位都带基础模块（UART/TIMER/HEARTBEAT）",
                SocModules.byName(mcu, "UART0") != null && SocModules.byName(soc, "HEARTBEAT0") != null
                        && SocModules.byName(cpu, "TIMER0") != null);

        // ---- 3. 窗口两两不重叠（真机装配的第一硬约束）----
        check("MCU 模块窗口两两不重叠", noOverlap(mcu));
        check("SOC 模块窗口两两不重叠", noOverlap(soc));
        check("CPU 模块窗口两两不重叠", noOverlap(cpu));

        // ---- 4. 速率单调（档位/链路差异的唯一表达）----
        // ⚠ 顺序里 UART 在 I2C 之前不是笔误：921600 波特的 UART（≈92KB/s）比 400kHz 的 I2C（≈40KB/s）快
        check("速率单调：PCIE > FSMC > USB > 8080 > QSPI > SPI > UART > I2C",
                SocModules.PCIE_BPS > SocModules.FSMC_BPS && SocModules.FSMC_BPS > SocModules.USB_BPS
                        && SocModules.USB_BPS > SocModules.P8080_BPS && SocModules.P8080_BPS > SocModules.QSPI_BPS
                        && SocModules.QSPI_BPS > SocModules.SPI_BPS
                        && SocModules.SPI_BPS > SocModules.UART_BPS
                        && SocModules.UART_BPS > SocModules.I2C_BPS);

        // ---- 5. 与外设映射层的 IO 线预算接得上 ----
        //     MCU 默认模块集 = UART0(2) + SPI0(4) + I2C0(2) + LCD8080(13) = 21 线
        final var devices = SocModules.asDevices(mcu);
        check("片上模块（MAILBOX 类）不计入组件槽位", devices.size() == 4);
        final var plan32 = PeripheralMap.plan(PeripheralMap.Capacity.mcu(32), devices,
                overridesFor(mcu));
        check("MCU 中档 32 槽位装得下默认模块集（共 21 个槽位）",
                PeripheralMap.totalSlots(plan32) == 21);

        boolean shortIo = false;
        String msg = "";
        try {
            PeripheralMap.plan(PeripheralMap.Capacity.mcu(16), devices, overridesFor(mcu));
        } catch (IllegalStateException e) {
            msg = e.getMessage();
            shortIo = msg.contains("组件槽位不够");
        }
        check("MCU 低档 16 槽位装不下（必须报错）", shortIo);
        check("报错写明差 5 个", msg.contains("还差 5 个"));

        // ---- 6. 装配规则真的会拦人：人为制造重叠 / 越档接口 ----
        final List<SocModule> overlap = new ArrayList<>(mcu);
        overlap.add(new SocModule("BAD", PeripheralMap.Carrier.SPI, SocModules.SPI_BPS,
                OcBoardLayout.UART_CACHE_BASE, 0x1000, -1, null));
        boolean overlapRejected = false;
        try {
            SocModules.validate(overlap, SocCpuTiers.Family.MCU);
        } catch (IllegalStateException e) {
            overlapRejected = e.getMessage().contains("重叠");
        }
        check("窗口重叠 ⇒ 装配报错", overlapRejected);

        final List<SocModule> mcuWithPcie = new ArrayList<>(mcu);
        mcuWithPcie.add(new SocModule("PCIE-BAD", PeripheralMap.Carrier.PCIE, SocModules.PCIE_BPS,
                0x1000_D000L, 0x1000, -1, null));
        boolean pcieRejected = false;
        try {
            SocModules.validate(mcuWithPcie, SocCpuTiers.Family.MCU);
        } catch (IllegalStateException e) {
            pcieRejected = e.getMessage().contains("不支持");
        }
        check("给 MCU 塞 PCIe ⇒ 装配报错（该档不支持这个接口）", pcieRejected);

        // ---- 6b. 档位能力覆盖：CPU 全部 / SOC 几乎所有 / MCU 仅部分 ----
        check("CPU 支持全部接口（含 8080/FSMC/PCIe/USB/DP）",
                SocModules.supports(SocCpuTiers.Family.CPU, PeripheralMap.Carrier.PARALLEL8080)
                        && SocModules.supports(SocCpuTiers.Family.CPU, PeripheralMap.Carrier.FSMC)
                        && SocModules.supports(SocCpuTiers.Family.CPU, PeripheralMap.Carrier.PCIE));
        check("SOC 几乎所有（缺 8080/FSMC，但有 QSPI/PCIe/USB/DP）",
                SocModules.supports(SocCpuTiers.Family.SOC, PeripheralMap.Carrier.PCIE)
                        && SocModules.supports(SocCpuTiers.Family.SOC, PeripheralMap.Carrier.QSPI)
                        && !SocModules.supports(SocCpuTiers.Family.SOC, PeripheralMap.Carrier.PARALLEL8080)
                        && !SocModules.supports(SocCpuTiers.Family.SOC, PeripheralMap.Carrier.FSMC));
        check("MCU 仅部分（UART/I2C/SPI/8080）",
                SocModules.supports(SocCpuTiers.Family.MCU, PeripheralMap.Carrier.UART)
                        && SocModules.supports(SocCpuTiers.Family.MCU, PeripheralMap.Carrier.PARALLEL8080)
                        && !SocModules.supports(SocCpuTiers.Family.MCU, PeripheralMap.Carrier.PCIE)
                        && !SocModules.supports(SocCpuTiers.Family.MCU, PeripheralMap.Carrier.USB));
        check("外部接口覆盖面递增：MCU(4) < SOC(8) < CPU(10)",
                externalCarriers(SocCpuTiers.Family.MCU) == 4
                        && externalCarriers(SocCpuTiers.Family.SOC) == 8
                        && externalCarriers(SocCpuTiers.Family.CPU)
                        == PeripheralMap.Carrier.values().length - 1);   // 减去片上 INTERNAL
        check("每个档位都有片上 INTERNAL（寄存器桥/定时器/调试口/心跳）",
                SocModules.supports(SocCpuTiers.Family.MCU, PeripheralMap.Carrier.INTERNAL)
                        && SocModules.supports(SocCpuTiers.Family.CPU, PeripheralMap.Carrier.INTERNAL));
        check("CPU 能挂 8080 并口（能力覆盖，不是白名单）",
                SocModules.supports(SocCpuTiers.Family.CPU, PeripheralMap.Carrier.PARALLEL8080));

        final List<SocModule> noGpu = new ArrayList<>();
        for (final SocModule m : soc) {
            if (!m.name().startsWith("GPU")) {
                noGpu.add(m);
            }
        }
        boolean gpuMissing = false;
        try {
            SocModules.validate(noGpu, SocCpuTiers.Family.SOC);
        } catch (IllegalStateException e) {
            gpuMissing = e.getMessage().contains("声明与实物必须一致");
        }
        check("声明有显示却没挂 GPU ⇒ 装配报错", gpuMissing);

        System.out.println("[MODULE] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 把每个模块映射到它自己的接口（外设映射层需要显式覆盖才不会用默认载体） */
    private static java.util.Map<String, PeripheralMap.Carrier> overridesFor(List<SocModule> modules) {
        final java.util.Map<String, PeripheralMap.Carrier> out = new java.util.HashMap<>();
        for (final SocModule m : modules) {
            // 与 SocModules.asDevices 用**同一条判据**：只映射占槽位的模块
            // （INTERNAL 片上模块 / MAILBOX 共享内存都不映射，否则会撞"override for unknown device"）
            if (m.iface().firstUseSlots() > 0 || m.iface().perDeviceSlots() > 0) {
                out.put(m.name(), m.iface());
            }
        }
        return out;
    }

    /** 外部接口数量（排除片上的 INTERNAL —— 它每个档位都有，不参与"部分/几乎所有/全部"的比较） */
    private static long externalCarriers(SocCpuTiers.Family family) {
        return SocModules.supportedCarriers(family).stream()
                .filter(c -> c != PeripheralMap.Carrier.INTERNAL)
                .count();
    }

    private static boolean noOverlap(List<SocModule> modules) {
        for (int i = 0; i < modules.size(); i++) {
            for (int j = i + 1; j < modules.size(); j++) {
                if (modules.get(i).overlaps(modules.get(j))) {
                    return false;
                }
            }
        }
        return true;
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
