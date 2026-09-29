package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.peripheral.PeripheralFifo;
import com.hdf.cryptand.soc.peripheral.PeripheralMap;
import com.hdf.cryptand.soc.peripheral.PeripheralMap.Carrier;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 模块清单工厂（虚拟机侧决定 RV 有哪些模块，2026-09-18）=====
 *
 * <p>用户定案："虚拟机可以定义创建 RV 的模块，比如 UART、SPI 等各种模块，然后 RV 有哪些模块也是
 * 虚拟机控制，然后底层就是接口 + 速率，不管 PCIE 等，不用模仿真实，只需要定义接口 + 速率 +
 * 寄存器映射之类的即可"。</p>
 *
 * <p>所以这一层只做一件事：<b>按处理器族把模块清单列出来</b>（族本身就带模块集语义，
 * 见 {@code SocCpuTiers.Family#hasGpu/hasNetwork/hasPcie}），并在装配前校验三条硬规则：</p>
 * <ol>
 *   <li><b>窗口不重叠</b>：寄存器映射是固件唯一认的东西，重叠 = 有一个设备永远写不进去；</li>
 *   <li><b>档位允许集</b>：MCU 不该出现 PCIe/USB/QSPI（那些是 SOC/CPU 的链路）；</li>
 *   <li><b>声明与实物一致</b>：族声明有显示模块（hasGpu）就必须真的挂上 GPU 模块。</li>
 * </ol>
 *
 * <p>速率数字是**工程近似**：只为"档位/链路之间的差异"服务，不追求协议细节
 * （用户定案："不用模仿真实"）。要调就改这里一处 —— 它是速率表的唯一来源。</p>
 */
public final class SocModules {

    // ==================== 速率表（唯一来源）====================

    /** 8N1：每字节 10 位 ⇒ 波特率 / 10 */
    public static final long UART_BPS = OcBoardLayout.UART_BAUD / 10L;
    /** I2C 标准模式 400 kHz */
    public static final long I2C_BPS = 40_000L;
    /** SPI 10 MHz @ 1 线 */
    public static final long SPI_BPS = 1_250_000L;
    /** QSPI 40 MHz @ 4 线 */
    public static final long QSPI_BPS = 5_000_000L;
    /** 8080 并口 8 位 @ 16 MHz */
    public static final long P8080_BPS = 16_000_000L;
    /** FSMC 16 位 @ 50 MHz */
    public static final long FSMC_BPS = 100_000_000L;
    /** USB 2.0 480 Mbps（≈60 MB/s，实际包开销另算，这里按裸速率） */
    public static final long USB_BPS = 60_000_000L;
    /** PCIe x1 Gen3 量级（不建模链路训练/通道细节） */
    public static final long PCIE_BPS = 1_000_000_000L;

    // ==================== 通用 FIFO 模块（2026-09-27 用户定案）====================
    //
    // 用户原话："芯片设计时缓存多大就多大；一般 UART 这种大于 1 字节都是使用硬件 FIFO，
    // FIFO 也算是芯片模块的一种，可以虚拟机实现通用 FIFO 给实际芯片装载此功能，
    // 并且消耗对应组件资源"。
    //
    // ⇒ 设备树里除了"有哪些外设"，还要写清"每个外设装载了多大的通用 FIFO 模块"：
    //   · 默认不装载 = 经典 1 字节保持寄存器（DR），0 槽位；
    //   · 装载 = 占组件槽位（计价口径 PeripheralFifo.slotsFor：4→1 … 256→7）。

    /** 本芯片为 UART 装载的通用 FIFO 模块深度（单一来源 = 设备布局 {@link UartRegs#TX_BYTES}） */
    public static final int UART_FIFO_DEPTH = UartRegs.TX_BYTES;

    /** 一块装载到芯片上的通用 FIFO 模块（芯片内模块：不占地址窗口，但**占组件槽位**） */
    public record FifoModule(String name, String owner, int depth) {
        /** 占用的组件槽位（与 PeripheralMap 的槽位预算同一个账） */
        public int slots() {
            return PeripheralFifo.slotsFor(depth);
        }

        @Override
        public String toString() {
            return name + "（深度 " + depth + " 字节，占 " + slots() + " 个组件槽位）";
        }
    }

    /** 本芯片装载的通用 FIFO 模块清单（设备树的一部分，与 UART0 的窗口一起登记） */
    public static List<FifoModule> fifoModules() {
        return List.of(new FifoModule("UART0.FIFO", "UART0", UART_FIFO_DEPTH));
    }

    /** 装载的 FIFO 模块占用的组件槽位合计 */
    public static int fifoSlotCost() {
        int total = 0;
        for (final FifoModule m : fifoModules()) {
            total += m.slots();
        }
        return total;
    }

    /**
     * 校验装载的 FIFO 模块装得进该档的组件槽位预算。
     *
     * <p>装不下就**明确报错**，绝不静默降级成 1 字节（那会表现成"日志时不时丢一段"，
     * 比直接说"资源不够"难查得多）。</p>
     *
     * @throws IllegalStateException 槽位不够
     */
    public static void validateFifoBudget(SocCpuTiers.Family family, int slotsBudget) {
        final int cost = fifoSlotCost();
        if (cost > slotsBudget) {
            throw new IllegalStateException("装载通用 FIFO 模块（" + fifoModules() + "）要 " + cost
                    + " 个组件槽位，而 " + (family == null ? "该档" : family.label()) + " 的预算只有 "
                    + slotsBudget + " 个（还差 " + (cost - slotsBudget) + " 个）——"
                    + "要么用更大的芯片/档位，要么不装 FIFO（不装就是经典 1 字节 DR）");
        }
    }

    // ==================== 槽位（与 OcBoardLayout 的既有分区对齐）====================

    /** 每个模块的窗口跨度：4KB，与 {{@code OcBoardLayout}} 既有分区一致 */
    private static final int SPAN = 0x1000;

    /** 低速外设槽（REG/TIMER/DEBUG/HEARTBEAT 占 0x1000_0000..0x1000_5000；
     *  UART 自 2026-09-27 起在 guest RAM 的缓存窗口里，不占设备区槽位） */
    private static final long SLOT_SPI = 0x1000_5000L;
    private static final long SLOT_QSPI = 0x1000_6000L;
    private static final long SLOT_I2C = 0x1000_7000L;
    private static final long SLOT_GPU = 0x1000_8000L;
    private static final long SLOT_NET = 0x1000_9000L;
    private static final long SLOT_PCIE = 0x1000_A000L;
    private static final long SLOT_P8080 = 0x1000_B000L;
    private static final long SLOT_USB = 0x1000_C000L;

    // ==================== 档位的能力覆盖（用户 2026-09-18 定案）====================
    //
    // 用户原话："所有模块都可以挂，比如 CPU 支持所有模块，然后 SOC 几乎所有，MCU 仅部分"。
    // 所以能力不是零散白名单，而是**按档位覆盖**：
    //   · CPU —— 全部接口（含 PCIe / USB / DP / 并口）；
    //   · SOC —— 几乎所有：除 MCU 那套直连并口总线（8080 / FSMC）外的全部；
    //   · MCU —— 仅部分：UART / I2C / SPI / 8080（它没有 QSPI / DP / USB / PCIe / mailbox）。
    // 挂不挂得上还要看**组件槽位够不够**（见 PeripheralMap）——能力是"能不能"，槽位是"够不够"。

    /** MCU 支持的接口（仅部分；片上 INTERNAL 每个档位都有） */
    private static final List<Carrier> MCU_CARRIERS =
            List.of(Carrier.INTERNAL, Carrier.UART, Carrier.I2C, Carrier.SPI, Carrier.PARALLEL8080);

    /** SOC 支持的接口（几乎所有：缺 MCU 那套并口/外部总线） */
    private static final List<Carrier> SOC_CARRIERS =
            List.of(Carrier.INTERNAL, Carrier.UART, Carrier.I2C, Carrier.SPI, Carrier.QSPI,
                    Carrier.USB, Carrier.DP, Carrier.PCIE, Carrier.MAILBOX);

    /** CPU 支持**全部**接口 */
    private static final List<Carrier> CPU_CARRIERS = List.of(Carrier.values());

    private SocModules() {
    }

    /** 该档位支持的接口清单（能力覆盖的唯一来源） */
    public static List<Carrier> supportedCarriers(SocCpuTiers.Family family) {
        if (family == null) {
            throw new IllegalArgumentException("family must not be null");
        }
        return switch (family) {
            case MCU -> MCU_CARRIERS;
            case SOC -> SOC_CARRIERS;
            case CPU -> CPU_CARRIERS;
        };
    }

    /**
     * 这一族的**组件槽位预算（能力集基准）**：把该族**能挂的全部外部载体各挂一件**所需槽位相加。
     *
     * <p>为什么这样定（而不是编一个数字）：预算的输入只有两样，且两样都已是单一来源 ——
     * 能力表 {@link #supportedCarriers(SocCpuTiers.Family)} 与载体自身的一次性开销
     * （{@link Carrier#sharedSlots()} / {@link Carrier#perDeviceSlots()}，定义见 PeripheralMap.Carrier 的定案）。
     * 于是「能挂满自己的能力集」就是这条预算的自然含义，面板上的「占用 / 预算」才可比、才有意义。</p>
     *
     * <p>⚠ <b>PCIe 不计入</b>：它的槽位按链路宽度逐卡计算（{@code PeripheralMap.pcieSlots}），
     * 往预算里塞一个「代表宽度」就是第二份数。PCIe 卡的账仍在挂载时单独扣（面板的槽位占用已含它，
     * 因此那一行会注明「不含 PCIe」）。</p>
     */
    public static int slotBudget(SocCpuTiers.Family family) {
        int sum = 0;
        for (final Carrier c : supportedCarriers(family)) {
            if (c == Carrier.PCIE) {
                continue;
            }
            sum += c.sharedSlots() + c.perDeviceSlots();
        }
        return sum;
    }

    /** 该档位能不能挂这种接口（"能不能"；"够不够"由组件槽位预算回答） */
    public static boolean supports(SocCpuTiers.Family family, Carrier carrier) {
        return carrier != null && supportedCarriers(family).contains(carrier);
    }

    /** 所有档位都有的基础模块（地址与 OcBoardLayout 一一对应，谁都不许另起一份） */
    public static List<SocModule> base() {
        // ⚠ 片上模块用 Carrier.INTERNAL（不占槽位、不受档位能力限制）：
        //   它们不是"挂在哪条总线上的设备"，用 MAILBOX 表示会导致 MCU 档"没有 mailbox"而误报。
        return List.of(
                new SocModule("REGBRIDGE", Carrier.INTERNAL, 0,
                        OcBoardLayout.REG_BASE, SPAN, -1, null),
                new SocModule("TIMER0", Carrier.INTERNAL, 0,
                        OcBoardLayout.TIMER_BASE, SPAN, OcBoardLayout.IRQ_MTIP, null),
                // ⚠ 2026-09-27：UART 不再是设备区里的 MMIO 寄存器组（那条逐字节事务路径已删除），
                //   而是 **guest RAM 里的外设缓存** ⇒ 设备表如实登记新窗口（0x2002_4820 起 2368 字节）。
                new SocModule("UART0", Carrier.UART, UART_BPS,
                        OcBoardLayout.UART_CACHE_BASE, OcBoardLayout.UART_CACHE_BYTES, -1, null),
                new SocModule("DEBUG0", Carrier.INTERNAL, 0,
                        OcBoardLayout.DEBUG_BASE, SPAN, -1, null),
                new SocModule("HEARTBEAT0", Carrier.INTERNAL, 0,
                        OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES, -1, null));
    }

    /**
     * 按处理器族生成模块清单（这就是"RV 有哪些模块由虚拟机控制"的落点）。
     *
     * @throws IllegalStateException 校验不过（窗口重叠 / 档位不允许该接口 / 声明与实物不一致）
     */
    public static List<SocModule> of(SocCpuTiers.Family family) {
        if (family == null) {
            throw new IllegalArgumentException("family must not be null");
        }
        final List<SocModule> out = new ArrayList<>(base());

        // 低速外设：每个档位都有 SPI + I2C
        out.add(new SocModule("SPI0", Carrier.SPI, SPI_BPS, SLOT_SPI, SPAN, -1, null));
        out.add(new SocModule("I2C0", Carrier.I2C, I2C_BPS, SLOT_I2C, SPAN, -1, null));

        if (family == SocCpuTiers.Family.MCU) {
            // MCU 驱屏走 8080 并口（它没有 QSPI/PCIe）
            out.add(new SocModule("LCD8080", Carrier.PARALLEL8080, P8080_BPS, SLOT_P8080, SPAN, -1, "lcd0"));
        } else {
            out.add(new SocModule("QSPI0", Carrier.QSPI, QSPI_BPS, SLOT_QSPI, SPAN, -1, null));
        }

        // 显示：SOC 起（SOC 经 QSPI 输出，CPU 经 PCIe 根复合体）
        if (family.hasGpu()) {
            out.add(new SocModule("GPU0", family.hasPcie() ? Carrier.PCIE : Carrier.QSPI,
                    family.hasPcie() ? PCIE_BPS : QSPI_BPS, SLOT_GPU, SPAN, -1, "screen0"));
        }
        // 网络：SOC 起
        if (family.hasNetwork()) {
            out.add(new SocModule("NET0", family.hasPcie() ? Carrier.PCIE : Carrier.QSPI,
                    family.hasPcie() ? PCIE_BPS : QSPI_BPS, SLOT_NET, SPAN, -1, null));
        }
        // PCIe 根复合体 + USB：只有 CPU 档有
        if (family.hasPcie()) {
            out.add(new SocModule("PCIE0", Carrier.PCIE, PCIE_BPS, SLOT_PCIE, SPAN, -1, null));
            out.add(new SocModule("USB0", Carrier.USB, USB_BPS, SLOT_USB, SPAN, -1, null));
        }

        validate(out, family);
        return out;
    }

    /**
     * 装配前的三条硬规则（真机与沙盒共用同一份 —— 沙盒过了真机不过的事不许再发生）。
     *
     * @throws IllegalStateException 任一条不满足
     */
    public static void validate(List<SocModule> modules, SocCpuTiers.Family family) {
        // ① 窗口不重叠
        for (int i = 0; i < modules.size(); i++) {
            for (int j = i + 1; j < modules.size(); j++) {
                if (modules.get(i).overlaps(modules.get(j))) {
                    throw new IllegalStateException("模块窗口重叠：" + modules.get(i)
                            + " 与 " + modules.get(j));
                }
            }
        }
        // ② 档位能力覆盖（用户定案："CPU 支持所有模块 / SOC 几乎所有 / MCU 仅部分"）
        for (final SocModule m : modules) {
            if (!supports(family, m.iface())) {
                throw new IllegalStateException(m.name() + ": " + family.label() + " 档不支持 "
                        + m.iface().label() + "（该档支持：" + supportedCarriers(family) + "）");
            }
        }
        // ③ 声明与实物一致：族说有显示模块，就必须真的挂上 GPU
        if (family.hasGpu()) {
            boolean hasGpu = false;
            for (final SocModule m : modules) {
                if (m.name().startsWith("GPU")) {
                    hasGpu = true;
                    break;
                }
            }
            if (!hasGpu) {
                throw new IllegalStateException(family.label()
                        + " 档声明有显示模块（hasGpu），但模块清单里没有 GPU —— 声明与实物必须一致");
            }
        }
    }

    /** 按名字找模块（诊断/工具用） */
    public static SocModule byName(List<SocModule> modules, String name) {
        for (final SocModule m : modules) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** 模块清单 → 人类可读清单（日志/UI 用；一行一个模块） */
    public static List<String> describe(List<SocModule> modules) {
        final List<String> out = new ArrayList<>(modules.size());
        for (final SocModule m : modules) {
            out.add(m.toString());
        }
        return out;
    }

    /**
     * 把模块清单折算成外设映射层的"设备"列表（用于 IO 线预算核算）。
     *
     * <p>为什么要有这一步：模块清单说"有哪些模块"，而"这些模块要占几个组件槽位、够不够"
     * 是 {@link PeripheralMap} 的事 —— 两者必须接得上，否则 IO 约束就只是纸面规则。</p>
     */
    public static List<PeripheralMap.Device> asDevices(List<SocModule> modules) {
        final List<PeripheralMap.Device> out = new ArrayList<>(modules.size());
        for (final SocModule m : modules) {
            // ⚠ 只统计**要占槽位**的模块：INTERNAL（寄存器桥/定时器/调试口/心跳区）与
            //   MAILBOX（SOC 档走共享内存的设备）都不占组件槽位，也不该被外设映射层
            //   当成"挂在某条总线上的设备"——否则 MCU 档会因为"没有 mailbox 载体"而误报。
            if (m.iface().firstUseSlots() == 0 && m.iface().perDeviceSlots() == 0) {
                continue;
            }
            out.add(new PeripheralMap.Device(m.name(), m.iface().name().toLowerCase(java.util.Locale.ROOT)));
        }
        return out;
    }
}
