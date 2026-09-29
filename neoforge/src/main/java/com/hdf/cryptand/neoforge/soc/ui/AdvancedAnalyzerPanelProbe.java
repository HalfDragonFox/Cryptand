package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.opencomputers.CryptandOcArchitecture;
import com.hdf.cryptand.neoforge.opencomputers.OcComponentBus;
import com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts;
import com.hdf.cryptand.neoforge.soc.content.SocPartItem;
import com.hdf.cryptand.neoforge.soc.content.SocPartKind;
import com.hdf.cryptand.soc.api.SocFault;
import com.hdf.cryptand.soc.board.DisplayTopology;
import com.hdf.cryptand.soc.board.GpuMemory;
import com.hdf.cryptand.soc.board.OcBoardLayout;
import com.hdf.cryptand.soc.board.SandboxInspect;
import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocModule;
import com.hdf.cryptand.soc.board.SocModules;
import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.oc.OcAbi;
import com.hdf.cryptand.soc.peripheral.PeripheralMap;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.MachineHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ===== 高级分析器：把"宿主侧现成的 live API"翻成 {@code SandboxInspect.Snapshot}（2026-09-27）=====
 *
 * <p><b>这是全工程唯一引用 OC 类型的地方</b>（{@code MachineHost} / {@code Machine}）。本子包
 * （{@code soc}）平时**不许引用 OC 的任何类** —— OC 缺席时 {@code soc} 必须照常加载 ——
 * 所以调用方（面板、{@code soc_inspect}）先做 {@code OpenComputersEntry.ocLoaded()} 软判，
 * 再进本类；类加载是惰性的，OC 不在场时本类根本不会被解析。</p>
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li>本类**不算数、不排版**：数值一律取自现成访问器/唯一来源，**展示文本一律由 common 的
 *       {@link SandboxInspect} 拼**（单位换算只走它的 {@code Cap.bytes/rate/hex/...} 工厂）
 *       —— 面板与 MCP 工具因此必然看到同一份数据。唯一的两处例外是
 *       "本身就是文字"的事实：{@code 按挂载设备宽度计} 这类**口径说明**与
 *       {@code 80x25} 这类**分辨率文本**（屏的分辨率是数据，不是我们换算出来的量）；</li>
 *   <li>缺项一律 {@code null} / 负数，由 {@link SandboxInspect} 落成占位符
 *       {@link SandboxInspect#DASH}（"查不到"与"真的是 0"是两件事）；</li>
 *   <li>每个数字都来自**已有访问器**（架构的只读诊断访问器 / OC 公开 API / 盘挂载表），
 *       本类不自己算 MHz、不自己推容量。</li>
 * </ul>
 */
public final class AdvancedAnalyzerPanelProbe {

    /**
     * ⚠ 必须用 log4j（{@code System.out} 进不了 {@code run/logs/latest.log}，无人化测试等于没有日志）。
     * 本类只在"设备树读不回来"这类**查得到但取不到**的情况下记一行，正常零输出。
     */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/soc");

    private AdvancedAnalyzerPanelProbe() {
    }

    // ==================== 找目标机 ====================

    /**
     * 目标机解析：先看 6 个邻居，再在 {@code radius} 内找**最近**的 OC 机器。
     *
     * <p>距离相同时取坐标字典序最小的一台 —— 结果必须可复现（同一次右键不能"这次 A、下次 B"）。</p>
     */
    public static BlockPos findTarget(ServerLevel level, BlockPos origin, int radius) {
        for (final Direction d : Direction.values()) {
            final BlockPos p = origin.relative(d);
            if (isMachine(level, p)) {
                return p;
            }
        }
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    final BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(p) || !isMachine(level, p)) {
                        continue;
                    }
                    final long dist = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (dist < bestDist || (dist == bestDist && best != null && before(p, best))) {
                        bestDist = dist;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static boolean before(BlockPos a, BlockPos b) {
        return a.asLong() < b.asLong();
    }

    /** 坐标处是不是 OC 机器（{@code MachineHost} 是 OC 的公开契约：机箱/服务器/单片机都实现它） */
    public static boolean isMachine(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return false;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        return be instanceof MachineHost host && host.machine() != null;
    }

    /** 坐标 → OC 机器；不是机器返回 null */
    private static Machine machineAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        return be instanceof MachineHost host ? host.machine() : null;
    }

    /** 该坐标的方块实体（内部用；已判过 isLoaded） */
    private static BlockEntity beAt(ServerLevel level, BlockPos pos) {
        return level == null || pos == null || !level.isLoaded(pos) ? null : level.getBlockEntity(pos);
    }

    // ==================== 采集 ====================

    /** 坐标处的机器 → 一份完整快照（不是 OC 机器 ⇒ 全占位，绝不编造） */
    public static SandboxInspect.Snapshot snapshot(ServerLevel level, BlockPos pos) {
        final Machine machine = machineAt(level, pos);
        if (machine == null) {
            return SandboxInspect.empty();
        }
        final CryptandOcArchitecture arch = machine.architecture() instanceof CryptandOcArchitecture a ? a : null;
        // ⚠ 模块清单与资源总账**一次算出来**（一个账只算一遍）：两处各算必然会出现
        //   "模块行说 28 槽、总账说 30 槽"这种对不上账，而那正是本分区最不能有的症状。
        final Inventory inventory = inventory(level, pos, arch);
        return new SandboxInspect.Snapshot(
                cpu(level, pos, arch),
                exec(machine, arch),
                regions(level, pos, arch),
                inventory.modules(),
                inventory.ledgers(),
                devices(arch, machine),
                caches(level, arch),
                msg(arch));
    }

    // ---------- 处理器 ----------

    private static SandboxInspect.Cpu cpu(ServerLevel level, BlockPos pos, CryptandOcArchitecture arch) {
        String family = null;
        String tierId = null;
        int bits = -1;
        String isa = null;
        final ItemStack stack = cpuStack(level, pos);
        if (stack != null && stack.getItem() instanceof SocPartItem part && part.kind() == SocPartKind.CHIP) {
            // 族只从**处理器部件**推一次（familyOfStack 与模块清单共用同一条判据）
            final SocCpuTiers.Family fam = familyOfStack(stack);
            family = fam == null ? null : fam.label();
            final int xlen = part.isa() == null ? -1 : part.isa().xlenBits();
            // 档位 id 从**档位表**反查（族 + 预算 + 位宽三者同时相等才是唯一解）
            for (final SocCpuTiers.Tier t : SocCpuTiers.all()) {
                if (t.family() == fam && t.spec() == part.spec() && t.xlen() == xlen) {
                    tierId = t.id();
                    break;
                }
            }
            if (part.isa() != null) {
                isa = part.isa().id();
            }
            bits = xlen;
        }
        if (arch != null) {
            if (isa == null && arch.isa() != null) {
                isa = arch.isa().id();
            }
            if (bits < 0 && arch.isa() != null) {
                bits = arch.isa().xlenBits();
            }
            return new SandboxInspect.Cpu(family, tierId, bits, isa,
                    arch.nominalMhz(), arch.actualMhz(), arch.load());
        }
        return new SandboxInspect.Cpu(family, tierId, bits, isa, -1, -1, -1);
    }

    /** 机箱里的处理器部件（没有任何处理器 = null） */
    private static ItemStack cpuStack(ServerLevel level, BlockPos pos) {
        final BlockEntity be = beAt(level, pos);
        if (!(be instanceof MachineHost host)) {
            return null;
        }
        try {
            for (final ItemStack stack : host.internalComponents()) {
                if (stack != null && !stack.isEmpty() && stack.getItem() instanceof SocPartItem part
                        && part.kind() == SocPartKind.CHIP) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /** 部件 tier → 档位族（soc 内容注册用的是同一套映射：mcu=1 / soc=3 / cpu=4） */
    private static SocCpuTiers.Family familyOf(int tier) {
        return switch (tier) {
            case 1 -> SocCpuTiers.Family.MCU;
            case 3 -> SocCpuTiers.Family.SOC;
            case 4 -> SocCpuTiers.Family.CPU;
            default -> null;
        };
    }

    // ---------- 执行 ----------

    private static SandboxInspect.Exec exec(Machine machine, CryptandOcArchitecture arch) {
        if (arch == null) {
            // 非 Cryptand 架构（例如 OC 原版 Lua）：只报 OC 公开 API 拿得到的部分
            final String state = machine.isPaused() ? "已暂停（Lua 架构）"
                    : (machine.isRunning() ? "运行中（Lua 架构）" : "停机");
            final String fault = machine.lastError() == null || machine.lastError().isBlank()
                    ? "无" : "OC 报错：" + machine.lastError();
            return new SandboxInspect.Exec(state, fault, -1, -1, -1, -1, -1);
        }
        final String state;
        if (arch.vmFaulted()) {
            state = "故障停机";
        } else if (machine.isPaused()) {
            state = "已暂停";
        } else if (!machine.isRunning() || !arch.vmRunning()) {
            state = "停机";
        } else if (arch.vmWaitingMmio()) {
            state = "等待 MMIO";
        } else if (arch.vmHalted()) {
            state = "固件停机（ECALL）";
        } else {
            state = "运行中";
        }
        final String fault;
        if (arch.vmFaulted()) {
            // 故障码 → 名称走 common 的唯一来源（与芯片面板同一份）
            fault = SocFault.causeName(arch.vmFaultCause())
                    + "（cause=0x" + Integer.toHexString(arch.vmFaultCause()).toUpperCase(Locale.ROOT) + "）";
        } else if (machine.lastError() != null && !machine.lastError().isBlank()) {
            fault = "OC 报错：" + machine.lastError();
        } else {
            fault = "无";
        }
        return new SandboxInspect.Exec(state, fault, arch.vmPc(), arch.vmInstructions(),
                arch.sandboxCycles(), arch.heartbeats(), arch.measuredWindowMillis());
    }

    // ---------- 内存布局（地址/容量唯一来源 = common 的 OcBoardLayout / OcAbi） ----------

    private static List<SandboxInspect.Region> regions(ServerLevel level, BlockPos pos,
                                                       CryptandOcArchitecture arch) {
        if (arch == null) {
            return List.of();
        }
        final List<SandboxInspect.Region> out = new ArrayList<>();
        out.add(new SandboxInspect.Region("ROM（引导 + 系统）",
                OcBoardLayout.ROM_BASE, OcBoardLayout.ROM_BYTES, -1));
        out.add(new SandboxInspect.Region("RAM（guest 映射）",
                OcBoardLayout.RAM_BASE, arch.mappedRamBytes(), -1));
        out.add(new SandboxInspect.Region("邮箱区（虚拟机 → 宿主）",
                OcAbi.MAILBOX_BASE, OcAbi.MAILBOX_SPAN, -1));
        out.add(new SandboxInspect.Region("显存窗口（VRAM）",
                OcBoardLayout.VRAM_BASE, OcBoardLayout.VRAM_BYTES, -1));
        out.add(new SandboxInspect.Region("消息缓存区（宿主 → 虚拟机）",
                OcBoardLayout.MSG_QUEUE_BASE, OcBoardLayout.MSG_QUEUE_BYTES, arch.msgPendingBytes()));
        out.add(new SandboxInspect.Region("心跳/状态区",
                OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES, -1));
        return out;
    }

    // ---------- 设备表 ----------

    private static List<SandboxInspect.Device> devices(CryptandOcArchitecture arch, Machine machine) {
        final List<SandboxInspect.Device> out = new ArrayList<>();
        if (arch != null) {
            // 有序组件表（下标 = 固件的句柄）—— 顺序的唯一来源是组件总线，不在面板里重排
            final List<OcComponentBus.Entry> table = arch.componentTable();
            for (int i = 0; i < table.size(); i++) {
                final OcComponentBus.Entry e = table.get(i);
                out.add(new SandboxInspect.Device(e.component(), e.address(), "#" + i));
            }
            return out;
        }
        try {
            final Map<String, String> raw = machine.components();
            if (raw != null) {
                for (final Map.Entry<String, String> e : raw.entrySet()) {
                    out.add(new SandboxInspect.Device(e.getValue(), e.getKey(), "（架构非 Cryptand：顺序为 OC 原表）"));
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return out;
    }

    // ---------- 模块清单（虚拟机为芯片建立/装载的模块 + 资源总账，2026-09-27 任务 K） ----------

    /** 一次采集的模块清单 + 资源总账（**同一份账**：模块的槽位之和就是总账的占用） */
    private record Inventory(List<SandboxInspect.Module> modules, List<SandboxInspect.Ledger> ledgers) {
    }

    /**
     * 虚拟机（硬件层）为这颗芯片**建立/装载了什么模块**，以及**装得下吗**。
     *
     * <p>数据源与非数据源（"查不到就写 -"，绝不编）：</p>
     * <ul>
     *   <li><b>设备树</b>：common 的 {@link SocModules#of} —— 唯一来源，本类不另列一份模块表
     *       （档位 → 模块集的映射只在那里）；</li>
     *   <li><b>槽位账</b>：common 的 {@code PeripheralMap.plan}（每个模块的 {@code slotsUsed}）+
     *       {@code PeripheralFifo.slotsFor} 的装载计价 —— 与"组件槽位"是同一本账，本类不算第二套；</li>
     *   <li><b>实装载的 FIFO 模块</b>：{@code UartHardware} 的活值（深度/槽位）；</li>
     *   <li><b>内存</b>：{@code OcBoardLayout} 的窗口常量 + 装配算出的 {@code mappedRamBytes()}
     *       + {@code memoryBytes()}（内存条指示器）；</li>
     *   <li><b>显示</b>：{@code GpuMemory.Budget}（卡/通道/屏/显存需求）。</li>
     * </ul>
     *
     * <p>⚠ 上限（组件槽位预算）**虚拟机侧目前没有这张表**（{@code SocCpuTiers} 只定义频率/位宽/模块集，
     * {@code PeripheralMap.Capacity.slots} 只在离线闸门里被赋过值）⇒ 总账如实写 {@code -}，
     * 不拿一个"看着合理"的数凑（见任务 K 报告：需在档位表那一侧定义单一来源）。</p>
     */
    private static Inventory inventory(ServerLevel level, BlockPos pos, CryptandOcArchitecture arch) {
        final List<SandboxInspect.Module> modules = new ArrayList<>();
        final List<SandboxInspect.Ledger> ledgers = new ArrayList<>();
        if (arch == null) {
            return new Inventory(modules, ledgers);     // 不是 Cryptand 沙箱 ⇒ 一条都不编
        }
        final SocCpuTiers.Family family = familyOfStack(cpuStack(level, pos));
        if (family == null) {
            return new Inventory(modules, ledgers);     // 没有处理器部件 ⇒ 模块集无从谈起（分区显示 -）
        }

        // ① 设备树模块（虚拟机为这个档建立的外设组件）：接口 + 速率 + 窗口 + irq + 槽位
        SlotAccount slots = null;
        try {
            final List<SocModule> tree = SocModules.of(family);
            slots = slotAccount(tree, family);
            for (final SocModule m : tree) {
                final List<SandboxInspect.Cap> caps = new ArrayList<>();
                if (m.bytesPerSecond() > 0) {
                    caps.add(SandboxInspect.Cap.rate("速率", m.bytesPerSecond()));
                }
                caps.add(SandboxInspect.Cap.hex("窗口", m.baseAddress()));
                caps.add(SandboxInspect.Cap.bytes("字节", m.spanBytes()));
                if (m.hasIrq()) {
                    // 没有中断线的模块不写这一项（"没有中断"与"查不到中断"是两件事：
                    // 前者在设备树里就是 -1，后者才是缺项）
                    caps.add(SandboxInspect.Cap.count("irq", m.irq()));
                }
                // 取不到槽位账 ⇒ 该参数写 -（"查不到"绝不当 0）
                caps.add(slots.caps().getOrDefault(m.name(), SandboxInspect.Cap.slots(-1)));
                modules.add(new SandboxInspect.Module(m.name(), m.iface().label(), m.deviceId(), caps));
            }
        } catch (Throwable t) {
            LOG.warn("[分析器] 设备树/槽位账读取失败（{}）—— 模块清单只报能取到的部分，账写 -", t.toString());
        }

        // ② 装载的通用 FIFO 模块（**活值**：UartHardware 真的装了什么）
        final int fifoDepth = arch.uartFifoDepth();
        int fifoSlots = 0;
        if (fifoDepth > 1) {
            fifoSlots = arch.uartFifoSlots();
            final List<SocModules.FifoModule> declared = SocModules.fifoModules();
            if (!declared.isEmpty()) {
                // 名字/归属取设备树（现在只有一块 UART FIFO；将来多块时这里要按 owner 精确匹配 —— 见报告）
                final SocModules.FifoModule f = declared.get(0);
                modules.add(new SandboxInspect.Module(f.name(), "通用 FIFO 模块", f.owner(), List.of(
                        SandboxInspect.Cap.bytes("深度", fifoDepth),
                        SandboxInspect.Cap.slots(fifoSlots))));
            }
        }

        // ③ 内存模块：**容量（内存条指示器）vs 映射（装配算出的实际字节）**
        //   ⚠ 与"内存"分区不重复：那一段回答每个窗口在地址空间哪儿、多长（布局），
        //     这里回答内存器件装了多少（容量/映射）—— 想看窗口明细去"内存"分区。
        modules.add(new SandboxInspect.Module("RAM（guest 映射）", "内存", null, List.of(
                SandboxInspect.Cap.hex("窗口", OcBoardLayout.RAM_BASE),
                SandboxInspect.Cap.bytes("字节", arch.mappedRamBytes()),
                SandboxInspect.Cap.bytes("内存条容量", arch.memoryBytes()))));
        modules.add(new SandboxInspect.Module("ROM（引导 + 系统）", "内存（只读）", null, List.of(
                SandboxInspect.Cap.hex("窗口", OcBoardLayout.ROM_BASE),
                SandboxInspect.Cap.bytes("字节", OcBoardLayout.ROM_BYTES))));

        // ④ 显示：显卡（卡数/通道数）+ 每块逻辑屏（分辨率/模式/显存）
        final GpuMemory.Budget gpu = arch.gpuBudget();
        long vramNeed = -1L;
        long memoryKb = -1L;
        if (gpu != null) {
            vramNeed = gpu.vramNeedBytes();
            memoryKb = gpu.memoryKb();
            int channels = 0;
            for (final GpuMemory.Card card : gpu.cards()) {
                channels += card.channels();
            }
            if (!gpu.cards().isEmpty()) {
                modules.add(new SandboxInspect.Module("显卡", "显示", null, List.of(
                        SandboxInspect.Cap.count("卡数", gpu.cards().size()),
                        SandboxInspect.Cap.count("通道", channels),
                        SandboxInspect.Cap.count("已挂屏", gpu.screens().size()))));
            }
            for (final DisplayTopology.Screen screen : gpu.screens()) {
                modules.add(new SandboxInspect.Module("屏幕 " + shortAddress(screen.id()), "显示",
                        screen.gpuName(), List.of(
                        SandboxInspect.Cap.text("分辨率", screen.cols() + "x" + screen.rows()),
                        SandboxInspect.Cap.text("模式", screen.kind().label()),
                        SandboxInspect.Cap.bytes("显存",
                                GpuMemory.screenVramBytes(screen.kind(), screen.cols(), screen.rows(),
                                        screen.bpp())))));
            }
        }

        // ⑤ 资源总账：占用 = 模块槽位之和（**同一个账**：外设链路 + 装载的 FIFO 模块）
        //    上限 = 该族的**能力集基准预算**（`SocModules.slotBudget`：能力表 × 载体开销，单一来源、不编数）。
        final long slotLimit = SocModules.slotBudget(family);
        if (slots == null) {
            ledgers.add(SandboxInspect.Ledger.ofCount("组件槽位", -1L, slotLimit, "槽"));
        } else {
            ledgers.add(SandboxInspect.Ledger.ofCount(
                    slots.pcieExcluded() ? "组件槽位（不含 PCIe）" : "组件槽位",
                    slots.total() + fifoSlots, slotLimit, "槽"));
        }
        if (vramNeed >= 0 && memoryKb >= 0) {
            // 显存账与 GPU bind 闸门（GpuMemory.validate）**同一个口径**：需求 vs 内存条容量
            ledgers.add(SandboxInspect.Ledger.ofBytes("显存 / 内存", vramNeed, memoryKb * 1024L));
        }
        return new Inventory(modules, ledgers);
    }

    /**
     * 模块的组件槽位账：**每个模块的槽位参数 + 合计**（一次算出来，行与总账不可能对不上）。
     *
     * <p>口径 = {@code PeripheralMap.plan} 的 {@code slotsUsed}（不像 {@code SocModuleSelfTest} 那样
     * 自己写一遍"首次占/每设备占"：那正是第二份计价）。这里给**不设上限**的预算
     * （{@code Integer.MAX_VALUE}）—— 我们只要每个模块的槽位账，上限由总账那一行负责
     * （虚拟机侧目前还没有预算表 ⇒ 写 {@code -}）。</p>
     *
     * <p>三类模块要说清楚（"0 槽"与"不在这个账里"是两件事）：</p>
     * <ul>
     *   <li>参与映射的（UART/SPI/I2C/QSPI/USB…）⇒ {@code plan} 给的槽位数（含首次共享）；</li>
     *   <li>片上（INTERNAL）与共享内存邮箱（MAILBOX）⇒ 真的是 **0 槽**（{@code SocModules.asDevices} 就不登记它们）；</li>
     *   <li>PCIe 类（GPU0/NET0/PCIE0）⇒ **模块本身不预扣槽位**（{@code Carrier.PCIE} 的
     *       {@code firstUseSlots()=0}，{@code asDevices} 也不登记）：槽位由**挂在它下面的设备**
     *       按链路宽度付账（x1=4 / x8=18 / x16=34，见 {@code PeripheralMap.pcieSlots}）——
     *       所以这一栏如实写"按挂载设备宽度计"，而不是假装 0 槽。</li>
     * </ul>
     *
     * @param pcieExcluded 清单里有没有 PCIe 类模块（有 ⇒ 总账要注明"不含 PCIe"）
     */
    private record SlotAccount(Map<String, SandboxInspect.Cap> caps, long total, boolean pcieExcluded) {
    }

    private static SlotAccount slotAccount(List<SocModule> tree, SocCpuTiers.Family family) {
        final List<PeripheralMap.Device> devices = SocModules.asDevices(tree);
        final Set<String> mapped = new HashSet<>();
        for (final PeripheralMap.Device d : devices) {
            mapped.add(d.id());
        }
        final Map<String, PeripheralMap.Carrier> overrides = new HashMap<>();
        final Map<String, SandboxInspect.Cap> caps = new HashMap<>();
        boolean pcie = false;
        for (final SocModule m : tree) {
            if (mapped.contains(m.name())) {
                overrides.put(m.name(), m.iface());     // 映射层不猜载体，用模块自己的接口
                caps.put(m.name(), SandboxInspect.Cap.slots(0));        // 先占位，下面由 plan 覆盖
            } else if (m.iface() == PeripheralMap.Carrier.INTERNAL
                    || m.iface() == PeripheralMap.Carrier.MAILBOX) {
                caps.put(m.name(), SandboxInspect.Cap.slots(0));        // 片上/邮箱：真的是 0 槽
            } else {
                pcie = true;                                            // 只剩 PCIe 这一类
                caps.put(m.name(), SandboxInspect.Cap.text("占槽位", "按挂载设备宽度计"));
            }
        }
        final PeripheralMap.Capacity capacity = family == SocCpuTiers.Family.MCU
                ? PeripheralMap.Capacity.mcu(Integer.MAX_VALUE)
                : PeripheralMap.Capacity.soc(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        long total = 0;
        for (final PeripheralMap.Binding b : PeripheralMap.plan(capacity, devices, overrides)) {
            caps.put(b.deviceId(), SandboxInspect.Cap.slots(b.slotsUsed()));
            total += b.slotsUsed();
        }
        return new SlotAccount(caps, total, pcie);
    }

    /** 机箱里那块处理器声明的族（没有处理器 / 不是 Cryptand 处理器 = null，调用方据此明确"查不到"） */
    private static SocCpuTiers.Family familyOfStack(ItemStack stack) {
        // 逐档处理器物品与组装台成品芯片同一口径（族来自 CPU 自己的规格，不再按 tier 回推）
        return com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.family(stack);
    }

    // ---------- 设备缓存 ----------

    private static List<SandboxInspect.Cache> caches(ServerLevel level, CryptandOcArchitecture arch) {
        if (arch == null) {
            return List.of();
        }
        final List<SandboxInspect.Cache> out = new ArrayList<>();

        // GPU / 组件总线：帧与上屏路径的计数（页路径是否真在用，看 pageBlits 与 rowFallbacks）
        out.add(new SandboxInspect.Cache("GPU 总线", List.of(
                row("绑定屏幕", orDash(arch.gpuBoundScreen())),
                row("上屏帧数", number(arch.gpuBlitFrames())),
                row("上屏行数", number(arch.gpuBlitRows())),
                row("显存页上屏帧", number(arch.gpuPageBlits())),
                row("退回逐行帧", number(arch.gpuRowFallbacks())),
                row("失败调用", number(arch.gpuFailedCalls())))));

        // UART：虚拟机建立的一块硬件（2026-09-27）—— 寄存器窗口 + 硬件缓存（DR / 可装载的 FIFO 模块）
        out.add(new SandboxInspect.Cache("UART", List.of(
                row("缓冲", arch.uartFifoDepth() <= 0 ? "1 字节 DR（未装载 FIFO 模块）"
                        : ("通用 FIFO 模块 深度 " + arch.uartFifoDepth() + " / 占 " + arch.uartFifoSlots()
                                + " 组件槽位")),
                row("生效容量", arch.uartFifoEnabled() ? String.valueOf(arch.uartSlots())
                        : "1（FIFO 未使能：CR1.FIFOEN=0）"),
                row("TX 字节", number(arch.uartTxBytes())),
                row("RX 字节（世界侧投进器件）", number(arch.uartRxBytes())),
                row("接收窗口占用", arch.uartAvailable() < 0 ? SandboxInspect.DASH
                        : arch.uartAvailable() + " / " + arch.uartQueueMax()),
                row("溢出 OVR（发/收）", arch.uartOvr() < 0 ? SandboxInspect.DASH
                        : (arch.uartOvrTx() + " / " + arch.uartOvrRx() + "（合计 " + arch.uartOvr() + "）")),
                row("世界侧队列丢弃", number(arch.uartInboxDropped())),
                row("状态位 SR", arch.uartStatusBits() < 0 ? SandboxInspect.DASH
                        : ("0x" + Integer.toHexString(arch.uartStatusBits()))))));

        // 盘：挂载地址 + 用量 + 分区（卷）—— 盘表是 common 的文件系统层给的，不在面板里解析
        for (final String address : OcDiskMounts.mountedAddresses()) {
            final List<SandboxInspect.Row> rows = new ArrayList<>();
            rows.add(row("地址", address));
            final CryptandFileSystem fs = OcDiskMounts.mounted(address);
            if (fs == null) {
                rows.add(row("用量", SandboxInspect.DASH));
            } else {
                // ⚠ 只读 / 系统卷照 OC 语义 spaceTotal()==0（"不报告容量"）⇒ 不能写成 "64 KB / 0 B"
                //   （那会被读成"用了 64KB、容量 0"）。
                final long used = fs.spaceUsed();
                final long total = fs.spaceTotal();
                rows.add(row("用量", total <= 0
                        ? SandboxInspect.bytes(used) + "（容量未报告：只读/系统卷）"
                        : SandboxInspect.bytes(used) + " / " + SandboxInspect.bytes(total)));
            }
            try {
                final List<String> volumes = OcDiskMounts.describeVolumes(level, address, 0, false);
                if (volumes.isEmpty()) {
                    rows.add(row("分区", "（没读到分区表：未格式化 / 无分区）"));
                }
                for (final String volume : volumes) {
                    rows.add(row("分区", volume));
                }
            } catch (Throwable t) {
                rows.add(row("分区", "读分区表失败：" + t.getClass().getSimpleName()));
            }
            out.add(new SandboxInspect.Cache("盘 " + shortAddress(address), rows));
        }
        return out;
    }

    private static SandboxInspect.Row row(String label, String value) {
        return new SandboxInspect.Row("", label, value);
    }

    private static String number(long v) {
        return v < 0 ? SandboxInspect.DASH : String.valueOf(v);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? SandboxInspect.DASH : s;
    }

    private static String shortAddress(String address) {
        return address == null || address.length() <= 8 ? String.valueOf(address) : address.substring(0, 8);
    }

    // ---------- 消息缓存区 ----------

    private static SandboxInspect.Msg msg(CryptandOcArchitecture arch) {
        if (arch == null) {
            return new SandboxInspect.Msg(-1, -1, -1, -1, -1, -1, -1, -1);
        }
        return new SandboxInspect.Msg(
                arch.msgPendingBytes(), arch.msgPendingMessages(), arch.msgCapacity(), arch.msgDropped(),
                arch.msgSeq(), arch.msgConsumedBytes(), arch.msgFlushes(), arch.mailboxPendingCalls());
    }
}
