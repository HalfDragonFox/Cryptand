/**
 * ===== Cryptand C/RV32 架构（OC 的 Architecture 实现，2026-09-16）=====
 *
 * <p>这是「我们的 CPU 插进 OC 机箱、但架构为 C 而不是 Lua」的落点：
 * OC 的 {@code Machine} 会通过 {@code DriverCPU.architecture(stack)} 拿到本类，
 * 用反射 {@code getConstructor(api.machine.Machine)} 构造，然后按
 * {@code runThreaded / runSynchronized} 驱动它。</p>
 *
 * <h3>分层（用户 2026-09-16 约束：核心脱离 MC）</h3>
 * <ul>
 *   <li><b>核心</b>在 common 的 {@code com.hdf.cryptand.soc.oc}（纯 Java 零 MC）：
 *       {@code ComponentBus} / {@code OcAbi} / {@code OcArchitectureCore}；</li>
 *   <li><b>本类</b>只做平台接线：把 OC 的调用转成核心调用、把 OC 的组件表喂给核心；</li>
 *   <li>因此"OC 不加载也能单独跑芯片"天然成立（本类不会被实例化）。</li>
 * </ul>
 *
 * <h3>P1 范围（本文件）</h3>
 * <p>先把**绑定与生命周期**打通：能被 OC 认作 CPU 架构、能开关机、能按组件重算内存。
 * 真正的指令执行（把 RV32 沙箱接到 {@code runThreaded}）与组件调用兑现留到 P2/P3。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.soc.board.SocIsa;

import com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers;
import com.hdf.cryptand.neoforge.soc.SocResources;
import com.hdf.cryptand.neoforge.soc.content.SocPartItem;
import com.hdf.cryptand.neoforge.soc.content.SocPartKind;
import com.hdf.cryptand.soc.board.DisplayPresenter;
import com.hdf.cryptand.soc.board.DisplayTopology;
import com.hdf.cryptand.soc.board.DisplayWindow;
import com.hdf.cryptand.soc.board.GpuMemory;
import com.hdf.cryptand.soc.board.HostMessageRing;
import com.hdf.cryptand.soc.board.OcBoardLayout;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.api.CpuCore;
import com.hdf.cryptand.soc.device.DebugConsoleDevice;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.device.TimerDevice;
import com.hdf.cryptand.soc.os.BootPlan;
import com.hdf.cryptand.soc.oc.OcAbi;
import com.hdf.cryptand.soc.oc.OcArchitectureCore;
import com.hdf.cryptand.soc.oc.OcKeyboardInput;
import com.hdf.cryptand.soc.oc.OcSandboxBridge;
import com.hdf.cryptand.soc.nativebridge.NativeRv32;
import com.hdf.cryptand.soc.nativebridge.SandboxCpuCore;
import com.hdf.cryptand.soc.nativebridge.SandboxVm;
import li.cil.oc.api.driver.item.Memory;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class CryptandOcArchitecture implements Architecture {

    /**
     * ⚠ 必须用 log4j：{@code System.out} 只在开发控制台可见，**不会**进 {@code run/logs/latest.log}，
     * 无人化测试时等于没有日志（2026-09-17 踩过）。
     */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    /** OC 的机器接口（构造期注入；不要在此做重活） */
    private final Machine machine;

    /** 统计到的内存字节数（由 recomputeMemory 得出） */
    private long memoryBytes;

    /**
     * 本机的**显示预算**：内存池（KB）+ 卡表 + 已挂上的逻辑屏 —— 全项目唯一一份显存口径
     * （{@link GpuMemory.Budget}，数值算法也只在 common 的 {@link GpuMemory} 那一处）。
     *
     * <p>为什么由架构持有：池（= 内存条容量）与卡表（= 机箱里的显卡物品）**只有这里算得出来**；
     * gpu 组件在 bind 一块屏之前必须拿同一份数去判"放得下吗"，而组件自己没有机箱视野
     * （{@code EnvironmentHost} 只给世界与坐标）。所以架构算一次、把对象交给组件只读 ——
     * 让组件回去再翻一遍机箱就是第二份口径，改一处漏一处，症状还只是"屏幕亮不亮"。</p>
     *
     * <p>⚠ 每次内存重算/开机都**换一份新对象**（屏表随之从零开始）：组件按对象身份认出
     * "本机换预算了"，会把自己已 bind 的屏重新登记进去（见 {@code CryptandGpuEnvironment.bind}）。</p>
     */
    private volatile GpuMemory.Budget gpuBudget = new GpuMemory.Budget(0, List.of());

    /** 本机显示预算（{@code CryptandOcDrivers} 建 gpu 组件时注入的就是它；见该类的延迟求值说明） */
    public GpuMemory.Budget gpuBudget() {
        return gpuBudget;
    }

    /** 组件快照（每次 recomputeMemory 刷新） */
    private final List<ItemStack> components = new ArrayList<>();

    private boolean initialized;
    private boolean running;

    /** {@code runThreaded} 被调用的次数（诊断：证明 OC 工作线程在驱动我们） */
    private long threadTicks;

    /** 上次打日志的组件数 / 内存字节数（用于节流：OC 每 tick 都会重算内存） */
    private int loggedCount = -1;
    private long loggedBytes = -1;

    // ==================== 显存窗口（VRAM，恒提供） ====================
    // 窗口地址**不是开关**：它是图像/真彩输出的**唯一传输介质**（DisplayWindow + presentImage +
    // TrueScreenGraphics），地址固定取 OcBoardLayout.VRAM_BASE（紧跟在邮箱区之后），开机时经
    // 系统配置块 disp.base/disp.bytes 注入一次 ⇒ 改地址布局要重启机器（与"配置块只注入一次"同一件事）。
    // 旧口径（"宿主可选是否接窗口；关掉时写 disp.base=0 ⇒ 固件改走 gpu_blit 组件调用"）已整条删除：
    // 关掉窗口 = 把整条图像链路静默关死，所以不再存在第二条路（用户 2026-09-27 定案）。

    /** 已经搬上屏的门铃值（== 固件当前门铃 ⇒ 没有新帧）。初始 0 与固件 {@code disp_init} 后的初值一致 */
    private int vramSeq;

    /** 已经搬上屏多少帧（诊断节流用） */
    private long vramFrames;

    /** 上屏失败次数（上屏出问题绝不能把机器弄停机；日志只打前几次） */
    private int vramErrors;

    // ==================== 消息缓存区（主线程 → 虚拟机；2026-09-27 接线） ====================

    /**
     * **统一消息缓存区**：键盘 / 串口 / 宿主通知 / 外部事件都从这里进虚拟机。
     *
     * <p>布局与语义的**唯一来源**是 common 的 {@link HostMessageRing}（纯 Java、离线可测）：
     * 本类一个偏移都不自己算 —— 写进 guest RAM 的每一段都取自它的
     * {@link HostMessageRing#guestHeaderA()} / {@link HostMessageRing#guestHeaderB()} /
     * {@link HostMessageRing#window()}，地址也取自它的常量。平台层另抄一套偏移，
     * 迟早与固件错位，而"错位"在真机上只表现为"键盘没反应"（最难查的那种）。</p>
     *
     * <p>方向与邮箱区（{@link OcAbi}，虚拟机 → 主线程）相反、职责对称；两者都是 guest RAM 里的一段，
     * 零 MMIO 往返（落在映射区之外的话，宿主每写一个字节就变成一次 ≈0.44ms 的设备事务）。</p>
     *
     * <p>⚠ 每次开机重建（{@link #initialize()}）：上一轮没被消费的消息不该跨过一次关机
     * —— 重启就是从干净状态开始，否则"上次塞了一半的键盘"会在新系统里突然执行。</p>
     */
    private HostMessageRing guestMessages = new HostMessageRing();

    /** 已发布进 guest RAM 的入队计数：只有"新入队"才需要重写数据字节（消费只改头里的指针） */
    private long msgDataSeq = -1L;

    /** 已发布次数（诊断） */
    private long msgFlushes;

    /** 上一次已上报的"固件写回 tail 不自洽"次数（异常才打日志，正常零输出） */
    private long msgReportedAnomalies;

    /** 发布（写 guest RAM）失败的次数（异常才打日志，正常零输出） */
    private long msgPublishErrors;

    /** 上一次已上报的"UART 缓存指针不自洽"次数（异常才打日志，正常零输出） */
    private long uartReportedAnomalies;

    /** 上一次已上报的"UART 缓存那 12 字节头读不回来"次数 */
    private long uartReportedMissing;

    /** UART 缓存同步失败的次数（异常才打日志，正常零输出） */
    private long uartSyncErrors;

    /** 上一次"邮箱对账不一致"诊断的纳秒时间（节流：正常情况一次都不打） */
    private long lastMailboxAccountNanos;

    /** 本次开机是否已经打过"邮箱账本"小结（每次开机一行，见 logMailboxLedgerOnce） */
    private boolean mailboxLedgerLogged;

    // ==================== 硬件布局（必须与 firmware/cryptand-os/cryptand_os.ld 一致） ====================

    private static final long ROM_BASE = com.hdf.cryptand.soc.board.OcBoardLayout.ROM_BASE; // 0x0000_0000L;
    /**
     * ROM 区总大小（native 内核按它划 ROM）：里面**分区**用 ——
     * 0x0000_0000 起 64KB 是 Boot 区（EEPROM 镜像跑在这）、0x0001_0000 起是系统区
     * （Boot 把系统盘镜像烧到这里再跳过去；见 {@code OcBootLoader#LOAD_BASE}）。
     * UI OS 有 406KB ⇒ 512KB 是下限（0x10000 + 406KB < 0x80000）。
     */
    private static final int ROM_BYTES = 512 * 1024;
    private static final long REG_BASE = com.hdf.cryptand.soc.board.OcBoardLayout.REG_BASE; // 0x1000_0000L;
    private static final long TIMER_BASE = com.hdf.cryptand.soc.board.OcBoardLayout.TIMER_BASE; // 0x1000_1000L;
    private static final long RAM_BASE = com.hdf.cryptand.soc.board.OcBoardLayout.RAM_BASE; // 0x2000_0000L;

    /**
     * 内核要求的最小 RAM。
     *
     * <p>三个系统的 BSS 实测：① 仅 FreeRTOS ≈ 13KB；② FreeRTOS+LVGL+shell ≈ 67KB；
     * ③ Cryptand UI OS ≈ 68KB（含 LVGL 自带堆 48KB + FreeRTOS 堆 12KB + 绘制缓冲）。
     * 链接脚本按 128KB 排布（栈顶 0x2002_0000）⇒ 至少映射 128KB，否则系统②③ 会踩空内存。</p>
     *
     * <p>⚠ 它**不是拒绝开机的判据**（用户 2026-09-27 定案：内存归虚拟机自己管，EEPROM/装机阶段
     * 不做内存账）：低于它只在 {@link #initialize()} 末尾打一行警告并照常映射 128KB —— 开不开机
     * 由 ISA/处理器与"有没有可引导的盘"决定，不由内存决定。</p>
     */
    private static final int MIN_RAM_BYTES = 128 * 1024;

    /** 机器定时器中断号：RISC-V 标准 MTIP=7（FreeRTOS 的 RISC-V port 只开 mie 的 1<<7） */
    private static final int IRQ_MTIP = 7;

    // ==================== 运行时（initialize 装配；runThreaded 推进） ====================

    /** 纯 Java 核心：RV32 沙箱 + OC-ABI 组件调用泵（common/com.hdf.cryptand.soc.oc） */
    private OcArchitectureCore core;

    /**
     * 组件总线（宿主实现）：把核心的 {@code "#<方法id>"} 调用兑现成 OC 组件调用
     * （{@code Machine.invoke}）—— 固件画的字就是从这条路过到屏幕上的。
     */
    private OcComponentBus componentBus;

    /** 板级部件（诊断用；同时持有引用避免被回收） */
    private SocBoard board;
    private RegBankDevice bridge;
    private TimerDevice timer;
    /** 宿主侧 16550 模型：**只做发送侧**（波特率节流 + 日志出口），不再挂在 guest 地址空间上 */
    /**
     * **虚拟机为芯片建立的 UART 硬件**（2026-09-27 定案：虚拟机 = 硬件层，只对芯片建立模拟外设组件）。
     *
     * <p>它持有寄存器文件语义 + 硬件缓存（默认 1 字节 DR；本芯片装载了通用 FIFO 模块 ⇒ 256 字节）
     * + 收发装配（波特率节流 / 移位寄存器）+ 状态位（TXE TC RXNE OVR）。芯片（固件）只读写映射到
     * guest RAM 的寄存器窗口（{@link com.hdf.cryptand.soc.board.UartRegs}），本类每 tick 与它整块同步
     * （见 {@link #pumpUart}）——**这是唯一写那段 guest 内存的东西**（开机那次 {@link #publishUartWindow}
     * 是同一线程、芯片放行之前的一次性初始化）。</p>
     *
     * <p>宿主只从**外部世界**那一侧供料：{@link #postSerial} 把世界侧字节交给这块硬件、
     * {@link #onUartByte} 把硬件发出的字节送到控制台/日志。芯片侧看不到宿主。</p>
     */
    private com.hdf.cryptand.soc.peripheral.UartHardware uart;

    /** UART 输出的整行缓冲（FreeRTOS 的启动横幅靠它进日志） */
    private final java.io.ByteArrayOutputStream uartBytes = new java.io.ByteArrayOutputStream();

    /** 最近一次故障（诊断用：跑飞了要能一眼看出原因） */
    private String lastFault;

    /** 本机用的 CPU 内核（native 时需要显式释放） */
    private CpuCore cpuCore;

    /** 是否真的用上了 C++ native 内核（诊断/日志） */
    private boolean nativeKernel;

    /**
     * 本机处理器的 ISA / 位宽声明（来自插入机箱的 CPU 部件；无处理器时为 null）。
     *
     * <p>用户 2026-09-18："位宽由 <b>CPU 部件</b> 决定（不是 EEPROM、不是盘）" ——
     * 所以位宽只从这里读，且 {@link #initialize()} 必须<b>先判定它能不能跑</b>再装配沙箱。</p>
     */
    private SocIsa cpuIsa;

    /**
     * MCU 档选中的 flash 盘**槽位**（{@code -1} = 不是 MCU 档 / 没选中）。
     *
     * <p>为什么要有它：boot 服务现在是**全档位统一挂上**的（用户 2026-09-27 定案），而 MCU 那一档
     * 没有 BIOS 这层，{@code OcBootLoader} 不会从 BIOS 决策里知道引导盘 —— 平台自己挑的这块 flash
     * 盘就是引导盘，在这里告诉引导器（见 {@code initialize} 的 ④）。</p>
     */
    private int mcuFlashSlot = -1;

    // ==================== 执行模型（用户 2026-09-18 / 09-25：**只有沙箱自驱动一套**） ====================

    /**
     * 沙箱（**唯一的执行模型**）：周期由沙箱<b>内部时钟</b>按"每秒多少周期"推进，
     * 宿主每 tick 只发一次心跳，不再计算也不再下发"每 tick 多少周期"。
     *
     * <p>用户 2026-09-25 定案：旧口径（宿主喂预算 + {@code cyclesPerTick} 配置 +
     * {@code sandboxSelfDriven} 开关）<b>整条删除</b> —— 只有一套操作，没有回退路径
     * （纪律：绝不静默降级）。启动失败就明确报错停机。</p>
     */
    private SandboxVm sandbox;

    /** 自驱动沙箱的 {@link CpuCore} 适配（回答"谁持有 guest 内存"） */
    private SandboxCpuCore sandboxCore;

    /** 沙箱设备总线：地址翻译 + 设备时间惰性推进 + 固件写 CALL 时触发组件泵 */
    private OcSandboxBridge sandboxBridge;

    /**
     * 上一轮心跳算出的设备中断位图（本轮心跳下发）。
     *
     * <p>⚠ 这是**有意的 1 tick 延迟**，与旧同步路径一致：旧路径也是在 {@code step()}
     * 开头推"上一轮结束时的设备状态"，然后才跑指令。</p>
     */
    private int pendingIrqBits;

    /** 沙箱自报的累计周期（算实测 MHz 用 —— 不数宿主 tick、不猜调度） */
    private long sandboxCycles;
    private long lastInstret;

    /** guest RAM 实际映射字节数（{@code initialize()} 里算出的 ramBytes，只供诊断读取；0 = 未开机） */
    private int mappedRamBytes;

    // ==================== 频率与负载统计（用户 2026-09-17：MHz 口径 + 实测对比） ====================

    /** 本机处理器的标称主频（MHz，来自处理器规格或配置覆盖） */
    private int cpuMhz = 4;

    /** 实测主频的核算基准：累计周期 / 累计真实纳秒（用户 2026-09-25：按**时间**折算，不按 tick） */
    private long measuredCycles;
    private long measuredNanos;
    private long sampledAtNanos;
    private long sampledAtCycles;

    public int nominalMhz() {
        final int forced = ConfigOpenComputers.clockMhz();
        return forced > 0 ? forced : cpuMhz;
    }

    /**
     * 实际达到的主频（MHz）—— <b>按真实经过时间折算</b>（用户 2026-09-25 定案）。
     *
     * <p>口径：沙箱自报的累计周期（native 的 {@code instret}，权威计数、含等设备访问时的停顿）
     * ÷ **真实秒数** ÷ 1e6。旧写法是 {@code 周期 / 宿主 tick 数 / 50_000}，隐含
     * "每 tick 正好 1/20 秒" —— 自驱动下宿主 tick 与沙箱周期无关，那样算出来的是
     * "每 tick 跑了多少周期"，<b>不是频率</b>。</p>
     */
    public double actualMhz() {
        if (measuredNanos <= 0) {
            return 0.0;
        }
        return (double) measuredCycles / ((double) measuredNanos / 1e9) / 1e6;
    }

    /**
     * 采一次"周期 / 真实时间"样本（每 tick 心跳后调用一次）。
     *
     * <p>只把 ≥50 ms 的窗口计入：短窗口的量化噪声会让读数乱抖，长窗口才接近真实吞吐。
     * 首个样本只立基准（不产生窗口）。</p>
     *
     * @param cycles 沙箱自报的累计周期（{@link #sandboxCycles}）
     */
    private void sampleClock(long cycles) {
        final long now = System.nanoTime();
        if (sampledAtNanos == 0) {
            sampledAtNanos = now;
            sampledAtCycles = cycles;
            return;
        }
        final long dNanos = now - sampledAtNanos;
        if (dNanos < 50_000_000L) {
            return;
        }
        measuredNanos += dNanos;
        measuredCycles += Math.max(0L, cycles - sampledAtCycles);
        sampledAtNanos = now;
        sampledAtCycles = cycles;
    }

    /**
     * CPU 负载 = 实测 MHz / 标称 MHz（1.0 = 完全达到标称）。
     *
     * <p>明显低于 1 说明标称频率超出宿主解释能力：沙箱会一直追自己的时钟预算
     * （表现为这个核满负荷），而真实吞吐受宿主线程分配器与解释器限制。</p>
     */
    public double load() {
        final int nominal = nominalMhz();
        return nominal <= 0 ? 0.0 : actualMhz() / nominal;
    }

    /** 是否检测到 Cryptand 的 C 启动介质（EEPROM 槽） */

    /**
     * ⚠ OC 用 {@code clazz.getConstructor(api.machine.Machine).newInstance(this)} 反射构造
     * ⇒ <b>这个构造器必须存在且是 public</b>，否则 OC 会打 warning "Failed instantiating a CPU architecture"。
     */
    public CryptandOcArchitecture(Machine machine) {
        this.machine = machine;
        LOG.info("[OpenComputers] ★ OC 已实例化 Cryptand C/RV32 架构（CPU = Cryptand 处理器，架构 ≠ Lua）");
    }

    // ==================== 生命周期 ====================

    /** OC 用它判断"机器是否初始化完成"（未完成时不派发 component_added 信号） */
    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 组件变化时重算内存（OC 会在装配/拆装组件后调用）。
     *
     * <p>统计规则：把每个组件里实现 {@link Memory} 的驱动的 {@code amount(stack)} 求和；
     * 这正是 OC 原生架构的算法口径（见 {@code LuaJLuaArchitecture.memoryInBytes}）。</p>
     *
     * <p>★ 语义 = **更新指示器**（用户 2026-09-27 定案："内存变更等内存由虚拟机接管管理，
     * 外部比如内存只是指示器"）：插拔内存条只改这个数值，**下次开机生效** —— 运行中的沙箱尺寸
     * 在 {@link #initialize()} 里一次性映射好，这里不改它、也**不做任何拒绝判定**（没有内存账要结）。</p>
     *
     * @return 是否至少有一块内存（false ⇒ 机器报 "no memory" 并蜂鸣；这是 OC 自己的装配语义）
     */
    @Override
    public boolean recomputeMemory(Iterable<ItemStack> incoming) {
        components.clear();
        long total = 0;
        for (final ItemStack stack : incoming) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            components.add(stack);
            final var driver = li.cil.oc.api.Driver.driverFor(stack);
            if (driver instanceof Memory memory) {
                // ⚠ OC 的 Memory.amount() 单位是 **KB**（见 Settings.ramSizes 注释 "in kilobytes"，
                //   OC 自己的架构也是 ×1024 才是字节）—— 实测直接当字节用会把 256KB 记成 256B。
                final double amount = memory.amount(stack) * 1024.0;
                if (amount > 0 && Double.isFinite(amount)) {
                    total += (long) amount;
                }
            }
        }
        this.memoryBytes = total;
        refreshGpuBudget();
        // ⚠ OC 的 Machine.update() 每 tick 都会 verifyComponents() → 调到这里（实测 20 秒刷了 649 行），
        //   所以只在**结果变化**时打日志，否则会把整个 latest.log 淹掉。
        if (components.size() != loggedCount || total != loggedBytes) {
            loggedCount = components.size();
            loggedBytes = total;
            LOG.info("[OpenComputers] Cryptand 架构重算内存：组件 {} 个 → {} B（{} KB）",
                    components.size(), total, total / 1024);
        }
        return total > 0;
    }

    /**
     * 重算**显示预算**（内存池 = 内存条容量，卡表 = 机箱里的显卡物品）。
     *
     * <p>内存池那份数就是 {@link #memoryBytes}（{@code recomputeMemory} 从 OC 的 Memory 驱动求和得来，
     * 单位字节）—— 显存口径里没有第二份"内存容量"，本方法一个字节都不自己算。</p>
     *
     * <p>结果没变就**不换对象**：OC 每 tick 都重算内存，而换对象会清空屏表（已 bind 的屏会被
     * 组件重新登记，但那一瞬间的口径是空的不必制造）。</p>
     */
    private void refreshGpuBudget() {
        final List<GpuMemory.Card> cards = scanGpuCards();
        final int memoryKb = (int) Math.min(Integer.MAX_VALUE, memoryBytes / 1024L);
        final GpuMemory.Budget current = gpuBudget;
        if (current.memoryKb() == memoryKb && current.cards().equals(cards)) {
            return;
        }
        gpuBudget = new GpuMemory.Budget(memoryKb, cards);
    }

    /**
     * 从机箱物品扫出**卡表**（显存口径的"硬件那一半"）。
     *
     * <ul>
     *   <li><b>我们的显卡</b>（{@code SocPartItem} 且 kind = {@code CARD_GPU}）：通道数 = 物品规格
     *       {@code spec()}（{@code SocPartKind.CARD_GPU} 的规格栏就是"输出通道数"：
     *       {@code graphicscard1/2/3} = 1/2/4 —— 用户 2026-09-26："1 个通道则支持 1 个屏幕
     *       （包括拼接的屏幕算一个），4 个则表示 4 个"）。名字用物品 id 的 **path**，
     *       与 {@code CryptandGpuEnvironment} 的 cardId 逐字相同：屏的 {@code gpuName()} 就是卡名，
     *       两边不一致屏就会被判成"悬空"。</li>
     *   <li><b>OC 原版卡</b>（{@code opencomputers:graphicscard1..4}）：一样是真显卡、一样吃显存，
     *       按 {@link DisplayTopology.GpuKind#OC_VANILLA} 计入。通道数 = 1，依据是 OC 自己的源码
     *       （{@code GraphicsCard.scala:56-58}：{@code screenAddress/screenInstance} 都是
     *       {@code Option[...]} —— 一个 gpu 组件一次只驱一块屏）。OC 的 Quad 卡在源码上是
     *       **4 个独立的 gpu 组件**（{@code QuadGraphicsCard.scala:13-18} {@code HeadCount = 4}），
     *       所以按 4 块 1 通道的卡计入 —— 它是 4 张卡，不是 1 张 4 通道的卡（不编通道数）。</li>
     * </ul>
     *
     * <p>⚠ OC 卡的名字用 **id 全名**（{@code opencomputers:graphicscard1}）而不是 path：两边的 path
     * 逐字相同（我们都是 {@code graphicscard1}），同名会让通道与屏归属张冠李戴。</p>
     */
    private List<GpuMemory.Card> scanGpuCards() {
        final List<GpuMemory.Card> cards = new ArrayList<>();
        for (final ItemStack stack : components) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (stack.getItem() instanceof SocPartItem p && p.kind() == SocPartKind.CARD_GPU) {
                cards.add(new GpuMemory.Card(id.getPath(), Math.max(1, p.spec()),
                        DisplayTopology.GpuKind.CRYPTAND));
                continue;
            }
            // OC 原版显卡：按 id 判（不是我们的物品），别把别的模组的卡也算进来
            if (!"opencomputers".equals(id.getNamespace())) {
                continue;
            }
            final String path = id.getPath();
            if ("quadgraphicscard".equals(path)) {
                for (int head = 1; head <= 4; head++) {
                    cards.add(new GpuMemory.Card(id + "#" + head, 1, DisplayTopology.GpuKind.OC_VANILLA));
                }
            } else if (path.startsWith("graphicscard")) {
                cards.add(new GpuMemory.Card(id.toString(), 1, DisplayTopology.GpuKind.OC_VANILLA));
            }
        }
        return cards;
    }

    /** 开机：装配 SoC 板（ROM + RAM + 组件桥 + 定时器@IRQ7 + UART），载入 Cryptand OS 内核镜像 */
    @Override
    public boolean initialize() {
        cpuIsa = null;
        mcuFlashSlot = -1;                  // 每次开机重新判定（见字段说明）
        // 执行模型每次开机重新判定（改配置 + 关机再开机 = 生效），并顺手释放上一次的沙箱 ——
        // OC 的重启流程是 close() → initialize()，这里再做一次幂等释放，避免"先建后放"错序。
        releaseSandbox();
        nativeKernel = false;
        // 消息缓存区也重来一份（见字段说明：上一次开机的残留消息不该在新系统里被执行）
        guestMessages = new HostMessageRing();
        msgDataSeq = -1L;
        mailboxLedgerLogged = false;        // 邮箱账本小结每次开机一行（见 logMailboxLedgerOnce）

        // ① 处理器：标称主频 + **ISA / 位宽判定**（用户 2026-09-18："位宽由 CPU 部件决定"）。
        //
        //    ⚠ 位宽判定必须发生在**装配沙箱之前**，不满足就 return false：
        //      OC 收到 initialize()=false 会 beep("--") 并保持关机（见 Machine.start:256
        //      `else if (!init()) { beep("--"); false }`）—— 这正是我们要的"明确报错"，
        //      绝不会出现"64 位芯片跑 32 位程序"的静默降级。
        for (final ItemStack stack : components) {
            // 两种处理器物品同一口径（逐档 SocPartItem 与组装台成品芯片），权威 = CPU 里的规格
            if (!com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.isProcessor(stack)) {
                continue;
            }
            final SocIsa isa = com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.isa(stack);
            if (isa == null) {
                // SocPartItem 的构造器已经拦住"处理器漏声明 ISA"；这里是运行期防线（例如第三方塞进机箱的伪造物品）
                LOG.error("[OpenComputers] ✖ 拒绝开机：处理器「{}」**没有声明 ISA / 位宽**（SocIsa 缺失）⇒ "
                                + "无法判定该按 32 位还是 64 位跑，绝不猜测；请使用 cryptand:mcu1_32 / soc1_32 / cpu1_32 等正规处理器",
                        stack.getHoverName().getString());
                return false;
            }
            if (cpuIsa != null) {
                // 多处理器：**只认第一个**（用户 2026-09-29 定案「架构/模块信息在 CPU 里」）。
                // 旧口径是"ISA 取首个非空、主频每轮覆盖" ⇒ 可能 ISA 来自 A、主频来自 B，已废。
                LOG.warn("[OpenComputers] ⚠ 机箱里有多个处理器，只按第一个「{}」判定 ISA 与主频；"
                                + "其余处理器不参与（CPU 信息是唯一权威）", stack.getHoverName().getString());
                break;
            }
            cpuIsa = isa;
            if (!isa.kernelRunnable()) {
                // ★ 核心闸门：位宽 / 寄存器模型 / 指令集与内核不同 ⇒ 直接拒绝开机（日志 + 工具提示 + OC 蜂鸣）
                //   标题按缺失层面自动取「该位宽内核未实现」（RV64/RV32E）或「该架构内核未实现」（8051）
                LOG.error("[OpenComputers] ✖ 拒绝开机：处理器「{}」的 ISA/架构 = {}（{} 位，{}）—— **{}**（{}）。"
                                + "当前沙箱只有 RV32I+M 一种内核（纯 Java Rv32Core 与 C++ native 均为 32 位、固定 32 个通用寄存器）⇒ "
                                + "绝不静默按 RV32 运行；请换用 RV32 芯片（cryptand:mcu1_32 / cryptand:mcu2_32 / "
                                + "cryptand:soc1_32 / cryptand:cpu1_32）",
                        stack.getHoverName().getString(), isa.id(), isa.xlenBits(), isa.registerModel(),
                        isa.unimplementedHeadline(), isa.supportNote());
                return false;
            }
            // 标称主频：整数 MHz 口径（CPU 规格里的每 tick 预算 ÷ 50_000，20 tick/s）
            // ⚠ mcu0_32 的 0.2 MHz 需要更细的表示，但该档属"未实现占位"、根本到不了这里
            //   （RV32E 内核未实现 ⇒ 在 ISA 闸门就被拒绝开机）。
            // ★ 两种处理器物品（逐档 SocPartItem / 组装台成品芯片）都走 ProcessorFacts.mhz：
            //   成品芯片以前在这里被整段跳过（cpuIsa=null、cpuMhz 停默认）——现已并入同一口径。
            cpuMhz = Math.max(1, com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.mhz(stack));
            break;
        }
        if (cpuIsa != null && cpuIsa.support() == SocIsa.KernelSupport.EXTENSIONS_MISSING) {
            // 可开机，但声明的扩展内核没有：用到就触发非法指令陷阱（明确报错），不算静默降级 ⇒ 只警告
            LOG.warn("[OpenComputers] ⚠ 处理器 ISA = {}：内核未实现 {} —— 当前内核 = RV32I+M，"
                            + "用到这些扩展指令会触发非法指令陷阱并停机（明确报错，不是静默错跑）",
                    cpuIsa.id(), cpuIsa.supportNote());
        }

        // ①.2 **显存口径 = 指示器（只写一行日志，绝不参与"开不开机"）**
        //      用户 2026-09-27 定案："内存变更等内存由虚拟机接管管理，虚拟机（沙箱）是一台完整电脑，
        //      外部比如内存只是指示器……方便的话 eeprom 阶段可以不管内存"。
        //      ⇒ 上一版在这里的"内存不够就 return false"（引导期显存闸门）**删掉**：那份账归虚拟机
        //        自己管，引导/EEPROM 阶段不做内存账。这里只把本机的显存口径写进日志当指示器看：
        //        池 = 内存条**声明的**容量（recomputeMemory 求和），卡表 = 机箱里的显卡物品。
        //      ⚠ 内存不够的表现点在**渲染侧**，不在引导侧（用户："内存不够会导致渲染出问题"）：
        //        gpu.bind 时按**同一份** GpuMemory 口径拒绝那块屏，机器照常跑（见
        //        CryptandGpuEnvironment.bind —— 两边用的是**同一个** Budget 对象）。
        refreshGpuBudget();                     // 保证卡表/池与本轮机箱一致（与 recomputeMemory 同一份实现）
        LOG.info("[OpenComputers] 显存口径 {}", GpuMemory.summary(gpuBudget.memoryKb(),
                gpuBudget.cards(), gpuBudget.screens()));

        // ①.5 **"谁把什么放到 0x0" 按处理器族选**（用户定案：交接 §四-5 "MCU 档：第一块有效盘当
        //      flash、复位 0x0，不走 BIOS"；2026-09-27 追加："虚拟机运行基本都是从 0x0 开始的，
        //      不管 mcu 还是 soc，如果 0x0 部分是 bootloader 就是 boot 启动"）。
        //      ⇒ 两条链路**只是搬运者不同**：MCU = 平台直接把第一块有效 flash 盘的内容放到 0x0；
        //        SOC/CPU = BIOS 枚举 → 选第一有效盘 → 把第一有效文件加载进 0x0。
        //      ⚠ 判定与"拒绝开机"都必须发生在**装配沙箱之前**：MCU 没有 BIOS 可退，
        //        没有有效 flash 盘就只能明确拒绝（OC 收到 initialize()=false 会蜂鸣并保持关机），
        //        绝不静默跑内置镜像 —— 那会让"盘里没程序"表现成"跑的是旧程序"。
        final boolean mcuFlash = isMcuFamily();
        final byte[] mcuFlashImage = mcuFlash ? resolveMcuFlashImage() : null;
        if (mcuFlash && mcuFlashImage == null) {
            return false;                       // 原因已由 resolveMcuFlashImage 逐块写入日志
        }

        initialized = true;
        running = true;

        // ② 镜像（两条**并存但互不干扰**的链路，按上面的档位选择走其中一条）：
        //    · SOC / CPU 档：EEPROM **只承载 Cryptand Boot**（BIOS / bootloader）；其余系统
        //      （Cryptand OS / UI OS / …）放在硬盘里，由 Boot 逐块扫描后跳转执行
        //      —— 与官方 OC 一致（EEPROM=BIOS，系统在磁盘上）。
        //    · MCU 档：**没有 BIOS 这一层**（EEPROM 与 BIOS 都不参与）⇒ 镜像就是第一块有效
        //      flash 盘里的程序（上一步已判定并读出），直接进 ROM 起始 0x0；
        //      复位向量本来就是 0x0（见 createCpu），所以"载入地址 = 链接地址 = 入口 = 0x0"。
        //      ⚠ "谁是 bootloader"是**内容**决定的、不是档位决定的（用户 2026-09-27）：flash 里那段
        //        程序若自带 bootloader 语义，它照样能经引导服务 ABI 两段式拉系统本体。
        final byte[] image;
        if (mcuFlash) {
            image = mcuFlashImage;
        } else {
            image = SocResources.bootImage();
            if (image == null || image.length == 0) {
                LOG.error("[OpenComputers] Cryptand Boot 镜像缺失（{}）⇒ 保持关机；"
                                + "请先跑 excode/firmware/build-all.ps1",
                        SocResources.FIRMWARE_BOOT);
                running = false;
                initialized = false;
                return false;
            }
        }

        // ⚠ 额外多分配一段给**外部设备邮箱区**（OcAbi.MAILBOX_BASE / MAILBOX_SPAN）。
        //   理由见 OcAbi 里那段说明：邮箱必须落在 native 内核**自持 RAM 的范围内**才算"普通内存访问"
        //   （零往返）；落在范围外一律变 MMIO 事务，每次读写都要跨宿主一次（≈0.44ms），
        //   等于把"固件自旋把 100MHz 拖成 0.01MHz"那个病直接请回来。
        //   固件链接脚本只认前 128KB（RAM 段 + 栈 + 末尾 4KB 配置块），多出来的这段它天然用不到。
        // ★ **不再从 RAM 里替虚拟机扣显存**（用户 2026-09-27 定案："内存归虚拟机（沙箱）接管管理，
        //   外部内存条只是指示器"）：内存条声明的容量就是虚拟机的内存，显存与 RAM 都由虚拟机自己管；
        //   宿主再减一次显存需求就是替它记了**第二份账**（"宿主借出去再交还"的旧语义随定案作废）。
        //   ⇒ ramBytes = max(MIN_RAM_BYTES, memoryBytes) + 邮箱段 + 显存窗口 + 消息缓存区。
        //   ⚠ 内核链接下限 MIN_RAM_BYTES **先 clamp 再叠加**：栈顶 0x2002_0000 与末尾 4KB 配置块
        //      （OcBoardLayout.CONFIG_BLOCK = 0x2001F000，固定地址、不随 ramBytes 变）都在前 128KB 里。
        //      它只是"别映射得比内核链接下限还小"，**不是**拒绝开机的判据（低于它只打一行警告）。
        //   窗口总字节数是 common 的**单一来源**（{@code OcBoardLayout.GUEST_WINDOWS_BYTES}
        //   = 邮箱段 + 显存窗口 + 消息缓存区 + UART 缓存）——离线闸门用同一个常量断言
        //   "每个窗口都落在映射区里"，少算任何一个都会在真机上表现成"宿主写窗口 = 未映射访问"。
        final int ramBytes = (int) Math.max(MIN_RAM_BYTES, memoryBytes)
                + OcBoardLayout.GUEST_WINDOWS_BYTES;
        mappedRamBytes = ramBytes;      // 诊断只读视图（见 mappedRamBytes()）

        // ③ 装配板子（地址必须与 firmware/cryptand-os/cryptand_os.ld 一致）
        bridge = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        timer = new TimerDevice("OC-TIMER");
        // RX FIFO 深度 16 = 真实 16550A 的深度（原来写 4，实测踩坑：
        // oc_key 一次注入 "help"+CR 共 5 字节会把回车挤掉 ⇒ 命令永不执行；
        // 真人逐键打字碰不到，但快速输入/粘贴/自动化注入一定会踩）。
        // 本芯片的 UART"硬件"（虚拟机侧）：**装载通用 FIFO 模块 256 字节**（照真实芯片：>1 字节就得装
        // FIFO 模块 + 软件使能 + 阈值；不装就是经典 1 字节 DR）。装载要占组件槽位 —— 计价口径见
        // PeripheralFifo.slotsFor / SocModules 的设备树（本芯片这一项 = 7 槽）。
        // ⚠ 它**不挂在 guest 地址空间上**（没有 MMIO 寄存器组）：芯片只读写映射进 guest RAM 的
        //   寄存器窗口（OcBoardLayout.UART_CACHE_BASE，已含在 RAM 窗口常量里）。
        uart = new com.hdf.cryptand.soc.peripheral.UartHardware("OC-UART",
                com.hdf.cryptand.soc.board.UartRegs.TX_BYTES);
        uart.setByteSink(this::onUartByte);      // 世界侧出口：控制台/latest.log

        // ⚠ Rv32Core.Config 的默认复位向量是 0x8000_0000（真实芯片的惯例），而我们的 ROM 在 0x0
        //   —— 不显式设 resetVector 的话，第一条指令都取不到就 InstructionAccessFault 停机
        //   （实测 PC=0x80000000 epc=0x80000000）。游戏内的 soc 芯片也是这么设的。
        //
        //   内核实现（用户 2026-09-17："直接一步到 C++ 内核"；09-25 收敛为唯一一套）：
        //     C++ native 沙箱（cryptand_rv32.dll，excode/cryptand-rv32/）—— 设备访问走消息总线，
        //     RegBank/Timer/UART 仍然是 Java 对象（soc 子包零改动）。
        //     ⚠ 没有"回落纯 Java 解释器"这条路（用户 2026-09-25）：库缺失 ⇒ 明确拒绝开机。
        final CpuCore cpu = createCpu(ramBytes, image);
        if (cpu == null) {
            return false;                       // 原因已由 createCpu 打日志（拒绝开机，不静默降级）
        }
        cpuCore = cpu;
        // ⚠ .rom()/.ram() 在这里是**声明式的指示器**（用户 2026-09-27 定案："虚拟机（沙箱）是一台
        //   完整电脑，外部比如内存只是指示器"）：真正持有这两段存储的是 native 沙箱自己 malloc 的
        //   ROM/RAM 向量，固件的访存**不经过**这两个 Java 设备。
        //   ⇒ 这两行只把布局（地址 + 大小，与固件链接脚本同一份常量）告诉宿主与 UI；
        //   宿主要读写 guest 内存一律走 vm.loadImage / readMemory / writeMemory，
        //   绝不直接碰这两个设备对象（它们只是影子）。
        board = SocBoard.builder(cpu)
                .rom(ROM_BASE, image, ROM_BYTES)
                .ram(RAM_BASE, ramBytes)
                .deviceWithIrq(REG_BASE, bridge, "OC-BRIDGE")
                .deviceWithIrqAt(TIMER_BASE, timer, "TIMER", IRQ_MTIP)   // ★ FreeRTOS 的 tick 在 IRQ 7
                // ⚠ **没有 UART 设备了**（2026-09-27 定案）：16550 的寄存器组是旧的逐字节 MMIO 路径，
                //   每个字符都让 native 内核停下等宿主兑现（实测最热的一条）。现在 UART 是 guest RAM
                //   里的缓存（OcBoardLayout.UART_CACHE_BASE，已含在上面那个 RAM 窗口常量里）——
                //   固件只做普通内存读写，本类每 tick 整块同步一次。旧的 UART_BASE 常量也一起删了。
                // ⚠ 调试口（2026-09-24）：现在只剩引导服务调用标记（0x01）与"UART 窗口没就绪"报警
                //   （0xEE）两处用途；逐字节 UART 镜像已删除。
                .device(OcBoardLayout.DEBUG_BASE, new DebugConsoleDevice("DEBUG-CONSOLE"), "DEBUG-CONSOLE")
                // ⚠ 心跳 / 运行状态区（固件的第 5 个 MMIO 区，见 OcBoardLayout.HEARTBEAT_BASE）：
                //   不声明它，固件的心跳任务第一次写就是未映射访问 —— Java 内核下直接异常停机。
                .ram(OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES)
                .build();

        // 组件总线：把固件的 "#<方法id>" 调用翻成 OC 的真实方法名后经 Machine.invoke 兑现
        // （⚠ 以前这里传的是 ComponentBus.EMPTY ⇒ 所有组件调用都以 "no component bus" 失败，
        //   屏幕自然永远没有输出）。首次绘制前会自动 gpu.bind(屏幕地址)，见 OcComponentBus。
        componentBus = new OcComponentBus(machine);
        core = new OcArchitectureCore(board, bridge, componentBus);
        core.start();
        logComponentTable();

        // ④ 引导服务：**全档位统一挂上**（用户 2026-09-27 定案："虚拟机运行基本都是从 0x0 开始的，
        //    不管 mcu 还是 soc，如果 0x0 部分是 bootloader 就是 boot 启动" ⇒ "0x0 上是 bootloader"
        //    对 MCU 同样成立，之前挂起的 MCU 两段式问题的答案 = **支持**）。
        //    提供者同是平台（{@link OcBootLoader}）—— 只有平台知道引导盘是哪一块。
        //    ⚠ 一律经内核的 loadImage —— 谁持有内存谁负责写：native 内核自持 RAM/ROM，
        //    Java 内核的 RAM/ROM 挂在 MemoryMap 上（RomDevice 只读，只有"烧写"能写它）。
        //    ⚠ 2026-09-27 重塑：它现在是 **BIOS 的读盘服务**（= 现实 INT 13h），不再是"宿主替 guest
        //    取系统本体"（那个 loadSystem 已删除）。取哪个文件、装到哪个地址都由 guest 说 ——
        //    宿主只按 path/loadAddr 从 BIOS 选定的那块盘读出字节写进 guest 内存。
        //    fsOfDisk 把物品翻成"这块盘的文件系统"，惰性求值：盘在 Boot 真正要读时才挂载。
        final OcBootLoader bootLoader = new OcBootLoader(components,
                (addr, data) -> cpu.loadImage(addr, data), this::fsOfDisk);
        core.setBootLoader(bootLoader);

        if (mcuFlash) {
            // MCU 档：**0x0 = 第一块有效 flash 盘的内容**（平台在上一步直接载入，判据与地址见
            // BootPlan.flash）—— 没有 BIOS 这一层，所以**不跑** CryptandBios.run（平台不再枚举选盘）。
            // ⚠ 但读盘服务照挂：flash 里的程序若**本身就是 bootloader**，它经 ABI 请 BIOS 读盘时走的是
            //   **同一条**链路（旧口径的 "boot service unavailable" 随定案作废）。
            //   平台知道引导盘是哪一块 ⇒ 在这里交给引导器（declareBootDisk = 现实 INT 19h 传 DL：
            //   只回答"引导盘是哪一块"，**不装载任何东西**）。
            final int bootDisk = bootLoader.declareBootDisk(mcuFlashSlot);
            LOG.info("[OpenComputers] MCU 档：0x0 = 第一块有效 flash 盘的内容（平台直接载入，无 BIOS 选盘）；"
                    + "BIOS 读盘服务已挂上（引导盘 = 候选 #{}，= INT 19h 传 DL）—— flash 里的程序"
                    + "自带 bootloader 时可经它把系统文件读进来",
                    bootDisk);
        } else {
            // SOC/CPU 档：**EEPROM/BIOS 枚举 → 选第一有效盘 → 把第一有效文件加载进虚拟机 0x0**。
            //      Cryptand BIOS 是**上电第一步**（用户 2026-09-24 定案的分层引导；BIOS 在宿主侧、不进沙箱）：
            //        · 盘上有 /boot/loader.bin ⇒ 写 bootloader 到 ROM 引导区 0x0
            //          （与内置固件同一编译产物，属幂等覆盖）
            //      之后 BIOS 的角色只剩**读盘服务**（INT 13h）：guest 里的 bootloader 自己点名
            //      要哪个文件、装到哪个地址（OcBootLoader.readFile）—— 策略在 guest，宿主只读字节。
            //      ⚠ CMOS（BiosConfig 持久化进方块实体 + Setup 界面）尚未接线 ⇒ 先用默认 boot order
            //        （软盘优先 → 硬盘），与用户规格一致。
            final com.hdf.cryptand.soc.bios.CryptandBios.Result bios =
                    bootLoader.runBios(null, s -> LOG.info("[OpenComputers] {}", s));
            if (!bios.started()) {
                LOG.warn("[OpenComputers] Cryptand BIOS: 没有可引导的盘 —— 机箱里放一块装好系统的盘"
                        + "（/cryptand soc disk install <地址> cryptand-os）后重新开机");
            }
        }

        // ④ 显存窗口：**恒提供**（没有开关）。地址就是布局常量 OcBoardLayout.VRAM_BASE，
        //    窗口紧跟在邮箱区之后；它必须在 injectOsConfig **之前**定下来 —— 固件是从系统配置块
        //    里读 disp.base/disp.bytes 才知道窗口在哪的（读到 0 = 宿主没提供，固件明确报 NO-WINDOW）。
        vramSeq = 0;
        vramFrames = 0L;
        vramErrors = 0;
        LOG.info("[OpenComputers] 显存窗口：guest 0x{} 起 {} 字节（字符/前景/背景三平面 + 门铃）；"
                        + "固件按 disp.base/disp.bytes 使用，宿主每 tick 比门铃后整屏上屏",
                Long.toHexString(OcBoardLayout.VRAM_BASE), OcBoardLayout.VRAM_BYTES);

        // ④.1 注入系统配置：把 config/cryptand/cryptand-os.cfg 写进 guest RAM 顶端保留的 4KB 块。
        //    用户要的"改文件 + 重启机器即生效"就靠这一步（固件用 hal_config_* 直接读）。
        injectOsConfig(cpu);

        // ④.2 消息缓存区（主线程 → 虚拟机）：把窗口的**初始镜像**写进 guest RAM。
        //      ⚠ 必须在沙箱放行（{@link #startSandbox()} → resume）之前 —— 固件一起来就会读魔数，
        //        读到 0 会明确报"窗口没就绪"（绝不静默当作没有输入）。
        publishMessageWindow(cpu);
        // ④.2.1 UART 硬件的寄存器窗口（同一条纪律、同一时机）：固件的 hal_uart_hw_init 起来就读魔数，
        //        读到 0 会把输出按"窗口没就绪"丢弃并在调试口打一笔（明确报错，不静默）。
        publishUartWindow(cpu);
        // ④.3 宿主通知（同一条通道的第三种来源）：开机一条。
        //      固件控制台把它打出来（"[host] ..."），于是"宿主通知也走这条通道"有无可辩驳的证据。
        postToGuest(HostMessageRing.KIND_NOTICE,
                ("Cryptand host online: message queue ready (" + guestMessages.capacity() + " bytes)")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // ⑤ 执行模型（用户 2026-09-18 定案；2026-09-25 收敛为**唯一一套**）：沙箱自驱动。
        //    频率在这里配置一次（SandboxVm.clock），此后沙箱按 native 内部时钟自己推进、
        //    自己限速（跑满就睡）—— 宿主不再计算也不再下发"每 tick 多少周期"。
        //    旧口径（宿主喂预算 + cyclesPerTick 配置 + sandboxSelfDriven 开关）已整条删除。
        startSandbox();

        LOG.info("[OpenComputers] ★ Cryptand C/RV32 架构开机：处理器 ISA={}（{} 位 · {}）标称 {} MHz；"
                        + "组件={} 内存={}B（映射 RAM={}KB）执行模型={} 线程分配器={}",
                cpuIsa == null ? "无处理器（异常装配）" : cpuIsa.id(),
                cpuIsa == null ? 0 : cpuIsa.xlenBits(),
                cpuIsa == null ? "—" : cpuIsa.registerModel(),
                nominalMhz(),
                components.size(), memoryBytes, ramBytes / 1024,
                "沙箱自驱动（每 tick 一次心跳；主频由沙箱自己的时钟限制）",
                ConfigOpenComputers.useThreadAllocator());
        LOG.info("[OpenComputers] 启动介质={} 内核镜像={} {} 字节；"
                        + "板级：ROM@0x0 RAM@0x20000000 REG@0x10000000 TIMER@0x10001000(IRQ {})"
                        + " UART 硬件@0x{}（寄存器窗口在 guest RAM：{} 字节 DR/FIFO + FIFO 模块 {}，"
                        + "占 {} 组件槽位；零 MMIO 事务）",
                mcuFlash ? "MCU flash 盘（第一块有效盘，直载 0x0；无 BIOS 层，读盘服务照挂 = INT 13h）"
                        : "内置 Boot 镜像（common 资源 cryptand-boot.bin → guest ROM；EEPROM 物品槽在 C 架构下不参与）",
                mcuFlash ? "flash 程序" : "Cryptand Boot（BIOS 选盘 → 写 0x0 → 读盘服务）",
                image.length, IRQ_MTIP, Long.toHexString(OcBoardLayout.UART_CACHE_BASE),
                uart.capacity(), uart.moduleLoaded() ? ("深度 " + uart.moduleDepth()) : "未装载",
                uart.moduleSlots());
        if (memoryBytes < MIN_RAM_BYTES) {
            LOG.warn("[OpenComputers] ⚠ 机箱内存只有 {}KB，而内核按 {}KB 链接（栈顶 0x20020000）⇒ 已按 {}KB 映射。"
                            + "系统②③（LVGL/UI OS）需要 ≥128KB：请多插内存条（T5 内存条 64KB 起）",
                    memoryBytes / 1024, MIN_RAM_BYTES / 1024, MIN_RAM_BYTES / 1024);
        }
        return true;
    }

    // ==================== 引导方式（按处理器族选；两条链路并存但互不干扰） ====================

    /**
     * 本机处理器是不是 **MCU 族**（决定引导方式：flash 直载 vs BIOS 分层）。
     *
     * <p>判据不另立：档位 id（{@code mcu1_32}）在这里回查 common 的 {@link SocCpuTiers} ——
     * 物品注册（{@code SocContent}）与面板显示（高级分析器）读的是同一份表，所以
     * "什么算 MCU"全项目只有一处定义。查不到档位的处理器（如占位的 {@code mcu0_32}）
     * 一律按非 MCU 处理：它本来就会先在 ISA 闸门被拒绝开机。</p>
     */
    private boolean isMcuFamily() {
        for (final ItemStack stack : components) {
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SocPartItem p)
                    || p.kind() != SocPartKind.CHIP) {
                continue;
            }
            final net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            final com.hdf.cryptand.soc.board.SocCpuTiers.Tier tier =
                    com.hdf.cryptand.soc.board.SocCpuTiers.byId(id.getPath());
            if (tier != null && tier.family() == com.hdf.cryptand.soc.board.SocCpuTiers.Family.MCU) {
                return true;
            }
        }
        return false;
    }

    /**
     * MCU 档的 flash 镜像：**第一块有效盘**（boot order：软盘优先 → 槽位升序）上的程序本体。
     *
     * <p>与 BIOS 那条链路的关系（用户要求"不引入第二套路径"）：**候选枚举只有一份**
     * （{@code OcBootLoader.enumerateDisks}，2026-09-27 收敛 —— 以前这里自己遍历了一遍机箱），
     * **选盘规则也只有一份**（{@link BootPlan}）—— 决策与排序交给 {@code BootPlan.flash/ordered}。
     * 两条链路的差别只有判据与搬运者：BIOS 认 {@code /boot/loader.bin} 并刷 0x0（第二级 0x1_0000）；
     * MCU 认 {@code /boot/system.bin} 并由**平台自己**把它当 flash 载入 0x0。
     * ⚠ 引导服务对 MCU **照挂**（用户 2026-09-27 定案）：flash 里那段程序若自带 bootloader 语义，
     * 它照样能经引导服务 ABI 两段式拉系统本体 —— flash 判据不看 {@code /boot/loader.bin} 这一点不变。</p>
     *
     * <p>逐块候选都会打一行"为什么能/不能当 flash"——"没有有效盘"时必须能一眼看出该修哪块盘
     * （现实中 BIOS 只会说一句 no bootable device，我们没必要跟着含糊）。</p>
     *
     * @return 镜像字节；{@code null} = 一块有效盘都没有（**已写明原因**，调用方据此拒绝开机）
     */
    private byte[] resolveMcuFlashImage() {
        // ★ 候选枚举复用 **BIOS 链路那一份**（OcBootLoader.enumerateDisks）：软盘优先 → 槽位升序、
        //   跳过挂载失败的盘，两处不再各写一遍。选盘规则（认哪个文件）仍然只有 BootPlan 一份。
        final List<BootPlan.Disk> disks = OcBootLoader.enumerateDisks(components, this::fsOfDisk,
                msg -> LOG.warn("[OpenComputers] MCU flash：{}", msg));
        // flash 窗口 = 这台机器 guest ROM 的实际大小（与 createCpu 声明的 ROM 同一个常量）
        // ⚠ 选哪个文件**不在这里判**：第一有效文件的规则只有 BootPlan.entryOf 一份（loader 优先、退系统），
        //   这里只负责"把平台选中的那块盘与那份内容交给虚拟机"，与档位无关。
        final int window = OcBoardLayout.ROM_BYTES;
        for (final BootPlan.Disk d : BootPlan.ordered(disks, BootPlan.BootOrder.FLOPPY_FIRST)) {
            LOG.info("[OpenComputers] MCU flash 候选：槽 {}「{}」⇒ {}",
                    d.slot(), d.label(), BootPlan.flashExplain(d, window));
        }
        final BootPlan.Flash flash =
                BootPlan.flash(disks, BootPlan.BootOrder.FLOPPY_FIRST, window);
        if (flash == null) {
            LOG.error("[OpenComputers] ✖ 拒绝开机：MCU 档没有有效 flash 盘（判据 = 盘上有**第一有效文件**"
                            + "（{} 优先，没有才用 {}）且不超过 flash 窗口 {} 字节）—— 上面每块候选都写了原因；"
                            + "用程序加载器把固件烧进一块盘再来（判据与 SOC/CPU 档是同一份，见 BootPlan.entryOf）",
                    com.hdf.cryptand.soc.os.Programs.LOADER_PATH,
                    com.hdf.cryptand.soc.os.Programs.BOOT_PATH, window);
            return null;
        }
        LOG.info("[OpenComputers] ★ MCU 档引导：flash 盘 = 槽 {}「{}」，0x0 ← {}（{} 字节）→ guest ROM 0x{}"
                        + "（载入地址 = 链接地址 = 入口 = 0x0，复位向量 0x0；BIOS 读盘服务同样挂着 ——"
                        + "0x0 上是 bootloader 时它会经该服务把系统文件取进来，即 boot 启动）",
                flash.slot(), flash.label(), flash.entryPath(), flash.program().length,
                Long.toHexString(flash.loadAddress()));
        // ★ MCU 档没有 BIOS，但 0x0 上执行的**同样是"介质那一份"** ⇒ 同一份同源性护栏
        //   （2026-09-27 真机事故：介质上旧 bootloader + 新宿主 ABI ⇒ 固件误报 "host bridge not wired"）
        OcBootLoader.warnStaleBootMedium(flash.program(), flash.slot(), flash.entryPath());
        // 平台知道引导盘是哪一块 ⇒ 直接告诉引导器（boot 服务全档位统一挂上，见 initialize 的 ④）
        mcuFlashSlot = flash.slot();
        return flash.program();
    }

    /**
     * MC 概念 → 纯数据："这块盘的文件系统"。
     *
     * <p>为什么独立成方法（2026-09-27）：BIOS 引导服务（{@code OcBootLoader} 的 fsOf）与
     * MCU flash 读取读的是**同一块盘**，必须走同一份挂载口径 —— 两处各写一个 lambda，
     * 迟早一边忘了只读、另一边忘了容量。盘怎么挂载只有这一处定义。</p>
     *
     * <p>世界是**惰性取**的：构造这个架构时节点还没建好（{@code machine.node() == null}），
     * 而真正要读盘时早就连网了 —— 在这里取才拿得到 host。世界来源两级（真机实测逼出来的）：
     * ① 节点宿主 —— 仅当它实现了 EnvironmentHost 时可用；② 当前 server 的 overworld。
     * ⚠ 为什么需要第②级：OC 的 {@code Machine.node().host()} 返回的是**架构对象自己**
     * （一个 Environment，不是 EnvironmentHost）—— 实测日志："取不到服务端世界"。
     * 这不是兜底补丁，而是"架构对象不是宿主"这个事实的直接后果。</p>
     */
    private com.hdf.cryptand.soc.fs.CryptandFileSystem fsOfDisk(ItemStack stack) {
        net.minecraft.server.level.ServerLevel lvl = null;
        final var node = machine.node();
        final Object host = node == null ? null : node.host();
        if (host instanceof li.cil.oc.api.network.EnvironmentHost envHost
                && envHost.getEnvironmentLevel() instanceof net.minecraft.server.level.ServerLevel s) {
            lvl = s;
        }
        if (lvl == null) {
            final var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                lvl = server.overworld();
            }
        }
        if (lvl == null) {
            throw new IllegalStateException("boot: 取不到服务端世界，无法挂载盘");
        }
        final SocPartItem p = (SocPartItem) stack.getItem();
        final String addr = p.ensureAddress(stack, true);
        return OcDiskMounts.of(lvl, addr, Math.max(1024L, (long) p.spec() * 1024L), false);
    }

    /** 关机 */
    @Override
    public void close() {
        running = false;
        initialized = false;
        // ⚠ 频率/负载这件事必须在 releaseSandbox() **之前**读：它会清掉核算基准（之后实测 MHz 归零）
        LOG.info("[OpenComputers] Cryptand 架构关机（runThreaded {} 次，内核={} 沙箱自驱动，实测 {} MHz / 标称 {} MHz，{}）",
                threadTicks, nativeKernel ? "C++ native" : "Java",
                String.format("%.2f", actualMhz()), nominalMhz(),
                lastFault == null ? "无故障" : ("最近故障：" + lastFault));
        releaseSandbox();
        cpuCore = null;
    }

    /**
     * 释放自驱动沙箱（幂等）。
     *
     * <p>{@link SandboxVm#close()} 自己会先 {@code stop()} 让 {@code run()} 返回、
     * 等两个 pinned 线程退出，再 {@code destroy()} 释放 native 机器 —— <b>顺序不能颠倒</b>
     * （run 还在跑时销毁会崩）。本方法只负责把引用摘干净，让第二次调用成为无操作。</p>
     */
    private void releaseSandbox() {
        final SandboxVm vm = sandbox;
        final OcSandboxBridge bridge = sandboxBridge;
        sandbox = null;
        sandboxCore = null;
        sandboxBridge = null;
        pendingIrqBits = 0;
        sandboxCycles = 0;
        mappedRamBytes = 0;
        lastInstret = 0;
        measuredCycles = 0;
        measuredNanos = 0;
        sampledAtNanos = 0;
        sampledAtCycles = 0;
        if (vm == null) {
            return;
        }
        LOG.info("[OpenComputers] 沙箱自驱动收摊：设备访问 {} 次 / 组件调用 {} 次 / 未映射访问 {} 次 / 消息 {} 条",
                bridge == null ? 0 : bridge.deviceAccesses(),
                bridge == null ? 0 : bridge.callTriggers(),
                bridge == null ? 0 : bridge.unmappedAccesses(),
                vm.messageCount());
        vm.close();
    }

    /** UART 字节 → 整行日志（FreeRTOS 的启动横幅、断言信息全靠它进 latest.log） */
    private void onUartByte(int b) {
        if (b == '\n') {
            if (uartBytes.size() > 0) {
                LOG.info("[Cryptand OS/UART] {}",
                        new String(uartBytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).stripTrailing());
                uartBytes.reset();
            }
            return;
        }
        if (b == '\r') {
            return;
        }
        // ⚠ 固件的中文日志是 **UTF-8 多字节**：逐字节 append 再让日志框架按平台编码解释，
        //   读出来就是 [FS] ???????¤±è?????é??è?????è§???? 这种（2026-09-25 实测）。
        //   UART 是字节流、不是字符格 ⇒ 攒字节、整行按 UTF-8 解码即可（屏幕那边就没这个余地，
        //   所以屏幕文本一律 ASCII）。
        if (uartBytes.size() < 512) {
            uartBytes.write(b & 0xFF);
        }
    }

    // ==================== 执行（P1：空转；P2 接 RV32 沙箱）====================

    /**
     * 工作线程里跑一轮（OC 的 worker thread 调用；见评估文档路线 A）。
     *
     * <p>⚠ <b>在自驱动模型下这一轮不是"执行指令"，而是心跳</b>：沙箱自己在另外的线程上按内部时钟
     * 推进周期，宿主每 tick 只做三件事 —— 下发设备中断位图、取回沙箱状态、把它翻译成
     * {@link ExecutionResult}。<b>这里一处都不再计算"本 tick 该跑多少周期"</b>
     * （用户 2026-09-18：「不需要每 tick 发送预算」）。</p>
     */
    @Override
    public ExecutionResult runThreaded(boolean isSynchronizedReturn) {
        if (!running) {
            return new ExecutionResult.Shutdown(false);
        }
        threadTicks++;
        // ⓪ 有待兑现的组件调用 ⇒ 把控制权交回 OC，由它在**主线程**调 runSynchronized() 兑现。
        //    依据 OC API 原文（ExecutionResult.SynchronizedCall 的 javadoc）：
        //      "…allows the next call to be to Architecture#runSynchronized() instead of
        //       runThreaded(boolean). This is used to perform calls from the server's main
        //       thread, to avoid threading issues when interacting with other objects in the world."
        //    ⚠ 这是**唯一正确**的组件调用上下文。另外两条路都实测踩过：
        //      · 放在沙箱消息泵线程（外挂 pinned、不受 OC 调度）⇒ 与主线程真并发争 Machine 的锁，
        //        主线程读机器状态直接超时（oc_machine_state / oc_machine_power 全废，
        //        E2E 的每次 PowerCycle 连带失效 ⇒ 假失败）；
        //      · 放在本方法里直接调 Machine.invoke ⇒ 机器线程自等，心跳第 3 次后**永久卡死**。
        //    额外收益：此时 OC 的 inSynchronizedCall = true，Machine.scala:310 的 direct 预算检查
        //    `if (architecture.isInitialized && !inSynchronizedCall)` 会跳过 ⇒ 组件调用不吃预算。
        final OcArchitectureCore c = core;
        if (c != null && c.hasPendingCalls()) {
            return new ExecutionResult.SynchronizedCall();
        }
        return sandboxHeartbeat();
    }

    /**
     * 自驱动心跳：**一次 JNI** 换回本轮需要的全部信息（追加需求⑥）。
     *
     * <p>为什么能只调一次：native 侧新增的 {@code heartbeat} 把"下发设备中断位图"和
     * "取回沙箱状态"合成一个调用，而且**不带 32 个寄存器**（那是调试面板的活，
     * 走 {@code snapshot()}）。状态取回后缓存在 {@link SandboxVm} 里，
     * {@link #actualMhz()} / {@link #load()} / 故障日志读的都是缓存，零跨语言开销。</p>
     *
     * <h3>返回值语义（依据 OC 源码 {@code Machine.scala:1086-1135}）</h3>
     * <ul>
     *   <li>{@code Sleep(1)} —— 机器仍然"活着"：沙箱在跑、或在等宿主兑现设备访问、
     *       或固件自己停机了。OC 会切到 Sleeping 并把 {@code remainIdle} 置 1，
     *       下个 tick 再来心跳一次。
     *       ⚠ <b>固件停机（ECALL from M）这里同样回 Sleep</b>：OC 的 {@code Shutdown}
     *       语义是"断电关机"（会走 {@code computer.stopped}、清屏幕、卸载组件），
     *       而 C 系统的停机只是 CPU 停机、机器仍通电 —— 回 Shutdown 会把屏幕内容清掉，
     *       那是语义错误。</li>
     *   <li>{@code Shutdown(false)} —— 只有架构自己被关掉（{@code running == false}）时才回，
     *       那才是真正的关机。</li>
     *   <li>{@code SynchronizedCall} —— <b>不用</b>：组件调用在沙箱消息泵线程上就地兑现
     *       （OC 官方 Lua 架构同样在工作线程直调 {@code Machine.invoke}，见
     *       {@code luaj/ComponentAPI.scala:67}），只有"必须回主线程碰世界"的操作才需要它。</li>
     *   <li>{@code Error} —— <b>不用</b>：沙箱故障是"CPU 挂了但机器还在"（可诊断、可复位），
     *       OC 的 {@code Error} 会 beep + crash 整台机器，反而把现场抹掉。</li>
     * </ul>
     */
    private ExecutionResult sandboxHeartbeat() {
        final SandboxVm vm = sandbox;
        if (vm == null) {
            return new ExecutionResult.Shutdown(false);
        }
        try {
            // ⓪ **扫描外部设备邮箱区**（用户 2026-09-17 定案的内存映射模型）。
            //    固件写参数是普通 sw、轮询 STATE 也是普通 lw —— 邮箱落在 native 内核自持的 RAM 里
            //    （OcAbi.MAILBOX_BASE，紧跟 128KB 主 RAM 之后），所以**零 MMIO 往返**；
            //    宿主这边每 tick 读几十字节就能发现请求，认领后进 core 的 pendingCalls，
            //    再由 runSynchronized() 在主线程批量消化。固件侧想阻塞就阻塞、想稍后再看就稍后再看，
            //    这就是"所有外设当作耗时操作"的落点。
            //    ⚠ 组件调用本身**不在这里**兑现 —— 唯一正确的地点是 runSynchronized()，
            //    理由见 runThreaded 里 SynchronizedCall 那一段。
            if (core != null) {
                core.pollMailbox();
            }
            // ① 一次 JNI：下发上一轮算出的中断位图 + 取回状态（instret / PC / 标志 / 故障）
            vm.heartbeat(pendingIrqBits);
            // ② 用刚取回的 instret 把**设备时间**推进到位，并算出下一轮要下发的位图。
            //    ⚠ 位图晚一个 tick 生效 —— 与旧同步路径完全同构（旧路径也是在 step 开头
            //    推"上一轮结束时的设备状态"）。设备时间不再需要宿主喂预算：它直接等于
            //    沙箱已执行周期数（见 OcSandboxBridge 的惰性 catch-up）。
            pendingIrqBits = sandboxBridge == null ? 0 : sandboxBridge.pendingInterrupts();
            // ③ 频率统计：只认沙箱自报的 instret（不数宿主 tick、不猜调度质量）
            final long instret = vm.instructionsRetired();
            sandboxCycles += instret - lastInstret;
            lastInstret = instret;
            // 实测主频按**真实时间**折算（用户 2026-09-25）：这里采一次"周期 / 纳秒"样本
            sampleClock(sandboxCycles);
            reportFaultIfAny();
            // ④.2 显存窗口：门铃变了就把新帧搬上屏（没有新帧时只读一次 32 字节控制块，零上屏开销）。
            //    ⚠ 内部把一切异常都吃掉 —— 心跳里抛异常 = 架构停机，屏幕反而彻底黑了。
            presentVramWindow();
            // ④.3 消息缓存区（主线程 → 虚拟机）：① 读回固件写回的 tail（回收它已消费的空间）；
            //    ② 有新消息就把数据窗口与头部发布进 guest RAM。没变更时只花一次 4 字节读。
            pumpMessageQueue();
            // ④.4 UART 外设缓存（2026-09-27 定案）：读回固件写出的 tx_head/rx_tail →
            //    取走 TX 日志、投递 RX → 发布宿主那两段头（就绪位）。**每 tick 一次整块同步**，
            //    UART 链上因此没有任何 MMIO 事务（固件那边只是普通内存读写）。
            pumpUart(instret);
        } catch (Throwable t) {
            LOG.error("[OpenComputers] 沙箱心跳异常（架构停机）", t);
            running = false;
            return new ExecutionResult.Shutdown(false);
        }
        if (threadTicks <= 3 || threadTicks % 200 == 0) {
            // ⚠ 这里原来写成 `{:.2f}` / `{:.0f}` —— 那是 **Python 风格**，SLF4J 不认，会被当普通文本，
            //   导致后面所有参数整体错位（"标称"位置显示的其实是 actualMhz 的值）。实测中这个假象
            //   让我误判过一轮（以为标称频率变成 0.003 MHz），所以改成先用 String.format 定型再传。
            //   `等待设备访问=` 是本轮定位"机器 running 却不推进"的关键诊断：沙箱处于 waitingMmio 时
            //   是不执行的，若它长期为 true ⇒ 宿主没及时 mmioResult。
            // ⚠ PC / 故障 / 停机位是 2026-09-17 真机排障加上的：当时固件"心跳在涨、
            //   主频却是 0.03 MHz、UART 一行都不出"，只有把 guest 的 PC 打出来才能判定
            //   是"卡在某个设备轮询循环"还是"跑飞到野地址"（后者实测未映射地址是
            //   0xff85051f 这类垃圾 ⇒ 立刻指向固件早期启动，而不是设备桥）。
            // ⚠ 2026-09-27：这一行的占位符原来**比参数少一个**（少了"设备桥"那一格），于是从
            //   定时器起每一项都印在上一项的标签下（日志里表现为"定时器[OcSandboxBridge[...]]、
            //   下发中断位图=0xmtime=..."）。本次接消息缓存区时把格子补齐，并加上消息队列那一格。
            LOG.info("[OpenComputers] 沙箱心跳第 {} 次（内核={} 自驱动，累计 {} 周期，实际 {} MHz / 标称 {} MHz，负载 {}%，guest PC=0x{} 故障={} 停机={} 等待设备访问={}，入队回读 STATUS={}，固件读到 STATUS={}（读 {} 次），CALL 回读 写后={} pump后={}，设备桥[{}] 定时器[{}] 下发中断位图=0x{}，消息队列[{}]，UART 缓存[{}]）",
                    threadTicks, nativeKernel ? "C++ native" : "Java",
                    sandboxCycles, String.format("%.2f", actualMhz()), nominalMhz(),
                    String.format("%.0f", load() * 100.0),
                    Integer.toHexString(vm.pc()),
                    vm.isFaulted() ? ("cause=0x" + Integer.toHexString(vm.faultCause())
                            + " tval=0x" + Integer.toHexString(vm.faultTval())
                            + " epc=0x" + Integer.toHexString(vm.faultEpc())) : "无",
                    vm.isHalted(),
                    vm.isWaitingMmio(),
                    core == null ? -1 : core.probeStatus(),
                    sandboxBridge == null ? -1 : sandboxBridge.lastStatusRead(),
                    sandboxBridge == null ? 0L : sandboxBridge.statusReads(),
                    sandboxBridge == null ? -1 : sandboxBridge.writeBackAfterStore(),
                    sandboxBridge == null ? -1 : sandboxBridge.writeBackAfterPump(),
                    sandboxBridge == null ? "无设备桥" : sandboxBridge,
                    // 🔍 定时器/中断诊断（2026-09-18）：真机上"FreeRTOS 任务从未运行"，
                    //    这一组数是唯一能分清"比较值没被写""定时器没推进""位图没算出"
                    //    还是"guest 没开中断"的地方 —— tick 中断是任务调度的命脉。
                    clintText(),
                    Integer.toHexString(pendingIrqBits),
                    // 🔍 消息通道诊断（2026-09-27）：无人化只需看这一段就能分清
                    //    "宿主没发"（seq/待发为 0）/"固件没取"（待发压着不动）/"塞满丢过"（丢弃 > 0）。
                    msgStats(),
                    // 🔍 UART 缓存诊断（2026-09-27）：这一段是"UART 还停不停 CPU"的现场 ——
                    //    "待宿主取"长期不为 0 说明固件写满了、在等宿主回收；"指针异常 > 0"
                    //    说明两侧布局版本不一致（那种情况下固件是等不到空位的）。
                    uartStats());
        }
        return new ExecutionResult.Sleep(1);
    }

    /**
     * 扫描显存窗口并把新帧搬上屏（用户 2026-09-18 定案："CPU 通过 PCIE 或内存直接写入 GPU"）。
     *
     * <p>两步走，为的是"没有新帧时几乎不花钱"：先只读 <b>32 字节控制块</b>比对门铃
     * {@code frame_seq}，只有它变了才把整屏取出来、展开成上屏调用 —— 门铃存在的意义就在这里
     * （协议见 {@link DisplayWindow}）。</p>
     *
     * <p>⚠ 门铃只表达"变过"，不表达"变了几次"：宿主记的是自己搬过的那个值，中间错过的帧直接跳过
     * （丢帧不排队）—— 这正是显示该有的语义（画面永远追最新，不补旧帧）。</p>
     *
     * <p>⚠ 一切异常都吞掉：心跳里抛异常 = 架构停机，屏幕反而彻底黑了 —— 上屏失败只丢一帧，
     * 下一帧门铃还会变。</p>
     */
    private void presentVramWindow() {
        final long base = OcBoardLayout.VRAM_BASE;
        final CpuCore cpu = cpuCore;
        final OcComponentBus bus = componentBus;
        if (cpu == null || bus == null) {
            return;
        }
        int seq = -1;
        try {
            final byte[] header = cpu.readMemory(base, DisplayWindow.HEADER_BYTES);
            if (header == null || header.length < DisplayWindow.HEADER_BYTES) {
                return;
            }
            if (DisplayWindow.read32(header, DisplayWindow.OFF_MAGIC) != DisplayWindow.MAGIC) {
                return;                 // 固件还没跑到 disp_init（窗口由它自己初始化，不是错误）
            }
            seq = DisplayWindow.read32(header, DisplayWindow.OFF_FRAME_SEQ);
            if (seq == vramSeq) {
                return;                 // 没有新帧 —— 绝大多数 tick 走这里，代价只有一次 32 字节读
            }
            // 程序画的是什么（谁在画）由窗口的 format 字段给出：字符三平面 ⇒ 字符面；
            // RGB565 ⇒ 图形面（"程序直接画图像"），由屏链路按目标屏能力自动决定"直接画/转字符"。
            // 两条各自解析、各自上屏，没有互相兜底（见 DisplayWindow.parse / parseImage）。
            final int format = DisplayWindow.read32(header, DisplayWindow.OFF_FORMAT);
            final int backend;
            final int frameCols;
            final int frameRows;
            final String reason;
            if (format == DisplayWindow.FORMAT_RGB565) {
                final DisplayWindow.ImageFrame image =
                        DisplayWindow.parseImage(cpu.readMemory(base, OcBoardLayout.VRAM_BYTES));
                backend = bus.presentImage(image);
                frameCols = image.width();
                frameRows = image.height();
                reason = "图形面（像素）：输出面由 ScreenOutputFace 派生（直接画 / 转字符）";
            } else {
                final DisplayWindow.Frame frame = DisplayWindow.parse(cpu.readMemory(base, OcBoardLayout.VRAM_BYTES));
                final int flags = DisplayWindow.read32(header, DisplayWindow.OFF_FLAGS);
                final DisplayPresenter.Capability cap = bus.presentCapability();
                final DisplayPresenter.Plan plan =
                        DisplayPresenter.plan(frame, cap.gpuUsable(), cap.pageCells(), flags);
                backend = bus.presentFrame(frame, plan);
                frameCols = frame.cols();
                frameRows = frame.rows();
                reason = plan.reason();
            }
            vramSeq = seq;
            // 回写后端：固件读它打 UART，无人化据此断言这一帧走的是 GPU 还是 CPU
            cpu.writeMemory(base + DisplayWindow.OFF_BACKEND,
                    new byte[]{(byte) backend, 0, 0, 0});
            vramFrames++;
            if (vramFrames <= 5 || vramFrames % 200 == 0) {
                LOG.info("[OpenComputers] 显存窗口上屏第 {} 帧（门铃 {}）：{}x{} 后端={} —— {}",
                        vramFrames, seq, frameCols, frameRows,
                        backend == DisplayWindow.BACKEND_GPU ? "GPU（显存页/VRAM 直写）"
                                : backend == DisplayWindow.BACKEND_CPU ? "CPU（逐行 set）" : "未知",
                        reason);
            }
        } catch (Throwable t) {
            if (seq >= 0) {
                vramSeq = seq;      // 记下这一帧，别卡在同一帧上每 tick 抛一次
            }
            if (vramErrors++ < 3) {
                LOG.warn("[OpenComputers] 显存窗口上屏失败（第 {} 次；之后不再刷日志）", vramErrors, t);
            }
        }
    }

    /**
     * 起自驱动沙箱：挂设备总线/事件 → 配频（**一次**）→ 起两个 pinned 线程 → 放行。
     *
     * <p>时序要点（与 {@code SandboxVmSelfTest} 一致）：</p>
     * <ol>
     *   <li>固件镜像、复位、系统配置块都是**命令队列**里的条目（{@link SandboxVm#loadImage} 等），
     *       在 run 线程起来之前投递也安全 —— 它们在队列里排队，run 线程起来了按 FIFO 执行；</li>
     *   <li>{@link SandboxVm#clock(int)} 只在**这里**调一次：这是"配置一次、沙箱自己限制运行"
     *       的落点（用户 2026-09-18）。改频率 = 改配置 + 重启机器，不存在每 tick 下发；</li>
     *   <li>{@link SandboxVm#resume()} 放在最后：确保镜像/复位/配置都已入队，固件一跑起来
     *       看到的就是完整的板子。</li>
     * </ol>
     */
    private void startSandbox() {
        final SandboxVm vm = sandbox;
        if (vm == null || core == null || board == null) {
            // 纪律：不静默降级 —— "回退到宿主喂预算"这条路已经删除，装配不完整就明确停机
            LOG.error("[OpenComputers] ✖ 沙箱启动失败：装配不完整（vm={} core={} board={}）⇒ 架构停机",
                    vm, core, board);
            running = false;
            return;
        }
        // 设备总线：地址 → 板级设备；并把"固件写 CALL=1"变成一次组件调用泵（事件驱动）
        sandboxBridge = OcSandboxBridge.create(board, REG_BASE, vm::instructionsRetired, core::pump);
        vm.bus(sandboxBridge);
        // 事件：故障/停机只落日志（机器保持通电，现场留给 oc_machine_state）
        vm.events(new SandboxVm.Events() {
            @Override
            public void onFault(int cause, int tval, int epc) {
                LOG.warn("[OpenComputers] ⚠ 沙箱硬件故障：cause={} tval=0x{} epc=0x{}",
                        cause, Integer.toHexString(tval), Integer.toHexString(epc));
            }

            @Override
            public void onHalted(int pc) {
                LOG.warn("[OpenComputers] 固件已停机（ECALL from M，PC=0x{}）—— 机器仍通电、屏幕保留；"
                        + "要重新运行请关机再开机", Integer.toHexString(pc));
            }
        });

        vm.clock(nominalMhz());
        /* 心跳与看门狗（用户 2026-09-28 定案）：宿主每 heartbeatSeconds 秒发一次空心跳，
         * 断了 silenceTimeoutSeconds 秒 ⇒ 虚拟机自己暂停（宿主卡顿它不管）。 */
        vm.heartbeatIntervalMs(ConfigOpenComputers.heartbeatMs());
        vm.pauseOnSilenceMs((int) ConfigOpenComputers.silenceTimeoutMs());
        vm.start();
        lastInstret = vm.instructionsRetired();
        vm.resume();
        LOG.info("[OpenComputers] ★ 执行模型 = **沙箱自驱动**：主频 {} MHz 已**配置一次**（每秒 {} 周期），"
                        + "此后由 native 内部时钟自行推进与限速；宿主每 tick 只发一次心跳，不再下发周期预算",
                nominalMhz(), (long) nominalMhz() * 1_000_000L);
    }

    /**
     * 定时器诊断文本：native 沙箱下读**虚拟机自己的 CLINT**（2026-09-28 定案后宿主只是看客，
     * Java 侧的 TimerDevice 不再是权威）；纯 Java 内核兜底时才回落到 Java 设备的影子值。
     */
    private String clintText() {
        final SandboxVm vm = sandbox;
        if (vm != null) {
            final long[] c = vm.clint();
            return "mtime=" + c[0] + " cmp=" + c[1] + " en=" + (c[2] != 0)
                    + " pend=" + (c[3] != 0) + " ticks=" + c[4] + "（虚拟机自建 CLINT）";
        }
        return timer == null ? "无定时器" : ("mtime=" + timer.getMtime()
                + " cmp=" + timer.getMtimecmp()
                + " en=" + timer.isEnabled() + " pend=" + timer.isPending());
    }

    /**
     * 开机时把组件表打进日志：E2E 排查"机箱里到底有没有显卡/屏幕"最直接的一行。
     *
     * <p>下标就是固件的组件句柄（{@code OcAbi.HANDLE_GPU=0} / {@code HANDLE_SCREEN=1} /
     * {@code HANDLE_KEYBOARD=2}）—— 排序策略见 {@link OcComponentBus#components()}。</p>
     */
    private void logComponentTable() {
        final var table = componentBus.components();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < table.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('#').append(i).append(' ').append(table.get(i).component())
                    .append('@').append(table.get(i).address());
        }
        LOG.info("[OpenComputers] Cryptand 组件桥：可见组件 {} 个{}", table.size(),
                table.isEmpty() ? "（一个都没有：机箱里插显卡/内存了吗？）" : " ⇒ " + sb);
        if (table.stream().noneMatch(e -> OcComponentBus.NAME_GPU.equals(e.component()))) {
            LOG.warn("[OpenComputers] ⚠ 没有可见的 {} 组件（我们的显卡）⇒ 固件的画字调用会失败："
                    + "机箱里要插一张 Cryptand 显卡", OcComponentBus.NAME_GPU);
        }
        // ⚠ 这里原来找的是 OC 原版的 "screen"，屏改名 rc_screen 之后它永远匹配不上 ⇒
        //   每次开机都误报一次「没有可见的 screen」（2026-09-30 随 rc_gpu 改名一并修正）。
        if (table.stream().noneMatch(e -> OcComponentBus.NAME_RC_SCREEN.equals(e.component()))) {
            LOG.warn("[OpenComputers] ⚠ 没有可见的 {} 组件 ⇒ GPU 无法 bind，屏幕不会有输出："
                    + "机箱旁边要放一块 Cryptand 真彩屏", OcComponentBus.NAME_RC_SCREEN);
        }
    }

    /** 把系统配置块注入 guest RAM 顶端（地址固定，与链接脚本 _config_block / CONFIG_RESERVE 对齐） */
    private void injectOsConfig(CpuCore cpu) {
        try {
            final byte[] base = CryptandOsConfig.block();
            if (base.length == 0) {
                return;
            }
            // 追加"盘句柄"：盘是 OC 组件表里的一项，下标运行时才知道 —— 固件开机读一次
            // （hal_config_int("disk.fs_handle")）就不需要"枚举组件"的能力。见 fs_* 的说明。
            final byte[] cfg = withRuntimeFacts(base);
            // ⚠ 地址取自 CryptandOsConfig（= 链接脚本布局），**不是** RAM_BASE + ramBytes；
            //   写内存同样只走内核（native 自持 RAM / Java 走 RamDevice，两条路都在内核后面）
            final long addr = CryptandOsConfig.CONFIG_BLOCK_ADDR;
            cpu.writeMemory(addr, cfg);
            LOG.info("[OpenComputers] 已注入系统配置 {} 字节 @ 0x{}（含盘句柄={}；改 {} 后 **重启机器** 即生效）",
                    cfg.length, Long.toHexString(addr), diskComponentIndex(), CryptandOsConfig.FILE);
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] 系统配置注入失败（用固件默认值继续）", t);
        }
    }

    /**
     * 组件表里 filesystem 组件的下标（= 固件侧 {@code disk.fs_handle}）；没插盘返回 -1。
     *
     * <p>排序策略见 {@link OcComponentBus#components()}：gpu/screen/keyboard 被顶到最前，
     * 其余按名字+地址字典序 —— 所以下标**只有运行时才知道**，不能写死。</p>
     */
    private int diskComponentIndex() {
        try {
            final var table = componentBus.components();
            for (int i = 0; i < table.size(); i++) {
                if ("filesystem".equals(table.get(i).component())) {
                    return i;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] 读组件表失败，盘句柄按 -1 注入（固件会认为没插盘）", t);
        }
        return -1;
    }

    /**
     * 把**运行时事实**追加进系统配置块：盘句柄 + 处理器真实标称频率与 ISA。
     *
     * <p>⚠ 为什么必须由宿主给（2026-09-26 用户实测："100 MHz 被识别成 20 MHz"）：
     * 固件里原本用的是**编译期常量** {@code configCPU_CLOCK_HZ}（写死 20 MHz）与写死字符串
     * {@code "RV32IM"} —— 与芯片实际规格（cpu1_32 = 1000 MHz / RV32IMAC）毫无关系；
     * 宿主侧还有第三份数值 {@code OcBoardLayout.CPU_HZ}。**三处各说各话**，
     * 所以 sysinfo 永远在谎报。现在只留一份来源：宿主按**插着的处理器规格**算出标称频率，
     * 经配置块交给固件；固件读不到就打 unknown（**绝不编造**）。</p>
     *
     * <p>⚠ 长期方案见 decree §七：这些硬件参数最终应来自沙箱**设备表（DEVTREE）**，
     * 配置块只放用户偏好。当前这一步是"先把谎报消掉"的最小改动。</p>
     *
     * <p>固件侧读法：{@code hal_config_int("disk.fs_handle", -1)} / {@code hal_config_int("cpu.mhz", 0)} /
     * {@code hal_config_get("cpu.isa", "")}。</p>
     *
     * <p>⚠ {@code CryptandOsConfig.block()} 返回的块**以 NUL 结尾**（{@code hal_config_*} 靠它
     * 定界），所以追加前要先摘掉那个 NUL、追加完再留一个 —— 直接拼会把配置块弄成
     * "NUL 后面还有内容"，固件读到第一个 NUL 就停了（表现为"配置里就是没有这一项"，
     * 排查起来很费劲）。</p>
     */
    private byte[] withRuntimeFacts(byte[] block) {
        final int index = diskComponentIndex();
        if (block.length == 0) {
            return block;
        }
        final StringBuilder sb = new StringBuilder();
        if (index >= 0) {
            sb.append("disk.fs_handle=").append(index).append('\n');
        }
        // 处理器真实规格（标称 MHz + ISA）：固件的 sysinfo 只认这两项，不再用编译期常量
        sb.append("cpu.mhz=").append(nominalMhz()).append('\n');
        if (cpuIsa != null) {
            sb.append("cpu.isa=").append(cpuIsa.id()).append('\n');
        }
        // 显存窗口：**恒给**（它是图像/真彩输出的唯一传输介质）。固件把 disp.base 读成 0 只会
        // 明确报 NO-WINDOW（绝不静默降级），所以这里不存在"不给"的分支。
        // ⚠ 必须十进制 —— 固件 hal_config_int 只认十进制（十六进制会被解成 0 或不认识）。
        sb.append("disp.base=").append(OcBoardLayout.VRAM_BASE).append('\n');
        sb.append("disp.bytes=").append(OcBoardLayout.VRAM_BYTES).append('\n');
        // 控制台的**字符分辨率**：宿主是权威（显示拓扑在它手里）。固件优先读这两项，
        // 其次是问显卡 —— 而没有绑定屏时显卡会回 1x1（不是报错），固件就变成 1x1 终端了。
        // 拿不到屏幕（没插/没绑）时**不写**这两项：固件明确回落到它自己的默认值并打 UART 证据。
        final int[] screenText = componentBus == null ? null : componentBus.boundScreenTextSize();
        if (screenText != null && screenText[0] > 0 && screenText[1] > 0) {
            sb.append("disp.cols=").append(screenText[0]).append('\n');
            sb.append("disp.rows=").append(screenText[1]).append('\n');
        }
        // ⚠ 格式必须与 cryptand-os.cfg 一致：**key=value，等号两边没有空格** ——
        //   固件 hal.c 的 configFind 匹配完 key 之后要求**紧接** '='（写成 "key = value"
        //   会被当成"这一行没有这个键"，表现是固件一直读到默认值 -1）。
        final byte[] tail = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final int body = block[block.length - 1] == 0 ? block.length - 1 : block.length;
        // ⚠ 先拔掉文件里可能自带的 disp.base / disp.bytes：固件 hal.c 的 configFind 返回**第一处**
        //   匹配，而这一块的前半正是玩家可编辑的 {@code cryptand-os.cfg} —— 文件里写一行
        //   disp.base=0 就能把宿主后面追加的这份顶掉，固件于是又报 NO-WINDOW。
        //   窗口地址只允许**一个**来源（宿主布局常量 OcBoardLayout.VRAM_BASE），故先拔后追加；
        //   拔掉时明确告警，不静默忽略（纪律：绝不静默降级）。
        final byte[] head = stripDisplayWindowKeys(block, body);
        final byte[] out = new byte[head.length + tail.length + 1];   // 末位留 0（数组初值即 0）
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /**
     * 从配置块体（前 {@code length} 字节，不含末位 NUL）里删掉 {@code disp.base} / {@code disp.bytes} 两行。
     *
     * <p>理由见 {@link #withRuntimeFacts}：固件 {@code configFind} 只认**第一处**匹配，
     * 所以玩家可编辑的 {@code cryptand-os.cfg} 里的旧键必须让位给宿主注入的布局常量。</p>
     *
     * <p>⚠ 匹配规则与固件一致：**行首就是 key、紧跟 '='**（"disp.base = 0" 那种写法固件本来也读不到，
     * 不属于本条要拔的对象）。'#' 开头的注释行天然不匹配，原样保留。</p>
     */
    private static byte[] stripDisplayWindowKeys(byte[] block, int length) {
        final String text = new String(block, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        if (text.indexOf("disp.") < 0) {
            return java.util.Arrays.copyOf(block, length);      // 绝大多数情况：一个字节都不动
        }
        final String[] lines = text.split("\n", -1);
        final StringBuilder kept = new StringBuilder(text.length());
        final StringBuilder dropped = new StringBuilder();
        for (final String line : lines) {
            if (line.startsWith("disp.base=") || line.startsWith("disp.bytes=")
                    || line.startsWith("disp.cols=") || line.startsWith("disp.rows=")) {
                if (dropped.length() > 0) {
                    dropped.append(" / ");
                }
                dropped.append(line);
                continue;                                       // 该行连同它前面的换行一起丢掉
            }
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(line);
        }
        if (kept.length() > 0 && kept.charAt(kept.length() - 1) != '\n') {
            kept.append('\n');                                 // 追加 tail 前必须换行结尾，否则两行会粘成一行
        }
        LOG.warn("[OpenComputers] {} 里的 {} 已忽略：显存窗口地址只有一个来源（宿主布局常量 disp.base={}）",
                CryptandOsConfig.FILE, dropped, Long.toHexString(OcBoardLayout.VRAM_BASE));
        return kept.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 创建 CPU 内核：**整台机器**（CPU + 内存 + 时钟 + 消息队列）交给沙箱。
     *
     * <p>用户 2026-09-25 定案：执行模型只有"沙箱自驱动"<b>一套</b> —— 旧的二维选择
     * （执行模型 × 内核实现）已收敛，{@code sandboxSelfDriven} / {@code cyclesPerTick}
     * 两个配置项一并删除。native 库不可用时<b>不回落</b>纯 Java 解释器（它没有内部时钟，
     * 也没有消息队列 ⇒ 承担不了唯一执行模型），而是明确拒绝开机（纪律：绝不静默降级）。</p>
     *
     * @return 沙箱内核；装配条件不满足时返回 {@code null}（调用方据此拒绝开机）
     */
    private CpuCore createCpu(int ramBytes, byte[] image) {
        if (!ConfigOpenComputers.useNativeKernel()) {
            LOG.error("[OpenComputers] ✖ 拒绝开机：配置 useNativeKernel=false，而唯一的执行模型（沙箱自驱动）"
                    + "本身就住在 cryptand_rv32.dll 里（内部时钟 + 消息队列 + guest 内存）⇒ 没有替代内核。"
                    + "请把 useNativeKernel 设回 true");
            return null;
        }
        if (!NativeRv32.available()) {
            LOG.error("[OpenComputers] ✖ 拒绝开机：native 库不可用（{}）—— 沙箱外壳（内部时钟/消息队列/guest 内存）"
                    + "都在 cryptand_rv32.dll 里，没有它就没有执行模型（纪律：绝不静默降级）",
                    NativeRv32.loadError());
            return null;
        }
        // ★ 复位向量 = **0x0，全档位一致**（用户 2026-09-27 定案："虚拟机运行基本都是从 0x0 开始的，
        //   不管 mcu 还是 soc，如果 0x0 部分是 bootloader 就是 boot 启动，虚拟机只需要从 0x0
        //   开始执行代码即可"）。
        //   ⇒ 虚拟机的执行模型只有一条：**复位到 0x0 并执行那里的代码**；档位差别只体现在
        //     "开机前谁把什么放到 0x0"（MCU = 第一块有效 flash 盘的内容，平台直接载入；
        //     SOC/CPU = BIOS 枚举 → 选第一有效盘 → 把第一有效文件加载进 0x0；见 initialize ①②④）。
        //   ⚠ 0x0 只是虚拟机的**起始地址**：它"算 ROM 地址还是 RAM 地址"不是设计问题 ——
        //     内存归虚拟机（沙箱自持的两段向量）、外部内存条只是指示器，宿主不在这里分谁是谁。
        final int resetVector = (int) ROM_BASE;
        final SandboxVm vm = SandboxVm.create("oc-" + Integer.toHexString(System.identityHashCode(machine)),
                resetVector, (int) RAM_BASE, ramBytes, (int) ROM_BASE, ROM_BYTES);
        if (vm == null) {
            LOG.error("[OpenComputers] ✖ 拒绝开机：沙箱创建失败（库加载成功但机器分配不出来 —— 内存不足？）");
            return null;
        }
        // 镜像走命令队列（run 线程起来后按 FIFO 执行）；复位/配置块同理，
        // 所以这里的投递顺序 = 固件看到的开机顺序。
        vm.loadImage((int) ROM_BASE, image);
        sandbox = vm;
        sandboxCore = new SandboxCpuCore(vm, resetVector);      // 复位向量与上面同一个值（0x0）
        nativeKernel = true;
        LOG.info("[OpenComputers] 内核 = **C++ native 沙箱**（cryptand_rv32.dll）：CPU + 内存 + 时钟 + 消息队列"
                + "都在沙箱里；设备访问走消息总线，宿主每 tick 只发一次心跳");
        return sandboxCore;
    }

    /**
     * 每 tick 发布/回收消息缓存区（主线程 → 虚拟机）。
     *
     * <h3>单写者纪律（谁写队列 / 谁写镜像）</h3>
     * <p><b>队列</b>（{@link HostMessageRing} 的宿主侧状态）任何线程都可以追加 —— 入口只有
     * {@link #postToGuest(int, byte[])} 一个，它内部加锁；<b>guest 内存里的那段镜像只有本方法写</b>
     * （开机那次 {@link #publishMessageWindow} 是同一线程、沙箱放行之前的一次性初始化）。</p>
     * <p>⇒ 镜像天然单写者，不需要任何锁；本方法用
     * {@link HostMessageRing#snapshotForPublish()} 在锁内一次取齐"数据 + 偏移 + 头"，
     * 再到锁外写 guest RAM（见 {@link HostMessageRing} 类说明）。<b>任何绕过它的 guest 内存写入
     * 都会破坏这条纪律</b>，必须改成 {@code postToGuest}。</p>
     *
     * <h3>为什么是"发布"而不是"推给固件"</h3>
     * <p>固件只认"读 guest RAM 的那段内存"（{@code hal_msg_take}），所以宿主这一侧的职责只有两件：
     * 把新消息写进去、把固件已消费的指针读回来。两边各写各的字段（宿主写 head/used/dropped/...，
     * 固件只写 tail）⇒ 字段级仍然是单写者。</p>
     *
     * <h3>写序（不能反）</h3>
     * <ol>
     *   <li><b>先写数据</b>（待发窗口 {tail, head) 的连续拷贝；跨环尾时分两段）；
     *   <li><b>再发布 A 段</b>（magic + head）与 <b>B 段</b>（used / capacity / dropped / state / seq）。
     * </ol>
     * <p>反过来（先发布 head 再写数据）会让固件读到一条还没写完的帧 —— 那是"偶发乱码命令"的典型来源。
     * ⚠ 头部<b>分两段写</b>的原因见 {@link HostMessageRing#HOST_A_OFFSET}：tail 归固件独占，
     * 宿主整块写下去会把固件刚推进的 tail 倒回去（帧被消费两次）。</p>
     *
     * <h3>代价</h3>
     * <p>没变更时只有一次 4 字节读（tail），与显存窗口的 32 字节门铃读同一量级；
     * 有变更时写头部 32 字节 + 待发窗口（常见就是几个字节的键盘输入）。</p>
     */
    private void pumpMessageQueue() {
        final CpuCore cpu = cpuCore;
        if (cpu == null) {
            return;
        }
        final HostMessageRing ring = guestMessages;
        try {
            // ① 读回固件写回的 tail（只有它改这个字段）→ 回收空间。
            final byte[] tailBytes = cpu.readMemory(OcBoardLayout.MSG_QUEUE_BASE + HostMessageRing.OFF_TAIL, 4);
            if (tailBytes != null && tailBytes.length == 4) {
                final int guestTail = (tailBytes[0] & 0xFF) | ((tailBytes[1] & 0xFF) << 8)
                        | ((tailBytes[2] & 0xFF) << 16) | ((tailBytes[3] & 0xFF) << 24);
                ring.syncGuestTail(guestTail);
            }
            if (ring.syncAnomalies() != msgReportedAnomalies) {
                msgReportedAnomalies = ring.syncAnomalies();
                LOG.warn("[OpenComputers] 消息缓存区：固件写回的 tail 不自洽（tail={} 待发={}/{} 字节）——"
                                + "本次不回收空间（绝不把没发出去的数据判成已消费），累计 {} 次；"
                                + "通常是固件/宿主布局版本不一致",
                        ring.tail(), ring.pendingBytes(), ring.capacity(), ring.syncAnomalies());
            }
            if (!ring.isDirty()) {
                return;
            }
            // ② **一次原子快照**：数据窗口 + 写入偏移 + A/B 段头 + seq（锁内取齐，见 HostMessageRing 的
            //    "谁写队列 / 谁写镜像"）。取齐之后再到锁外写 guest RAM ⇒ 数据与 head 一定配套，
            //    不会被"发布途中又追加进来一条"撕裂。
            final HostMessageRing.Snapshot snap = ring.snapshotForPublish();
            // ③ 只有"新入队"才需要重写数据字节：消费只改头里的指针，数据区原样不动
            if (snap.seq() != msgDataSeq) {
                msgDataSeq = snap.seq();
                final byte[] win = snap.window();
                if (win.length > 0) {
                    final int at = snap.offset();
                    final int first = Math.min(win.length, ring.capacity() - at);
                    cpu.writeMemory(OcBoardLayout.MSG_QUEUE_DATA_BASE + at,
                            java.util.Arrays.copyOfRange(win, 0, first));
                    if (win.length > first) {
                        cpu.writeMemory(OcBoardLayout.MSG_QUEUE_DATA_BASE,
                                java.util.Arrays.copyOfRange(win, first, win.length));
                    }
                }
            }
            // ④ 发布头（A 段：magic + head；B 段：used / capacity / dropped / state / seq）
            cpu.writeMemory(OcBoardLayout.MSG_QUEUE_BASE + HostMessageRing.HOST_A_OFFSET, snap.headerA());
            cpu.writeMemory(OcBoardLayout.MSG_QUEUE_BASE + HostMessageRing.HOST_B_OFFSET, snap.headerB());
            // ⚠ 带上本次发布的 seq：发布期间新追加进来的消息不会因此被"清脏吞掉"（下一 tick 会再发）
            ring.markFlushed(snap.seq());
            msgFlushes++;
            // 按键延迟台账②：待发清空 = 固件已经把这条按键取走了（tail 推到位）。
            // 放在发布之后结算：这一 tick 刚写进 guest RAM，固件最迟下一轮轮询就能看到。
            if (keyWaiting && ring.pendingBytes() == 0) {
                final long ms = (System.nanoTime() - keyPostNanos) / 1_000_000L;
                keyWaiting = false;
                keyLatencyLastMs = ms;
                if (ms > keyLatencyWorstMs) {
                    keyLatencyWorstMs = ms;
                }
                LOG.info("[OpenComputers] 按键延迟：入队 → 固件取走 {} ms（历史最差 {} ms；"
                                + "宿主侧发布 ≤1 tick ≈ 50 ms，其余是固件的轮询间隔）", ms, keyLatencyWorstMs);
            }
        } catch (Throwable t) {
            // 与显存窗口同一条纪律：这是**输入通路**，出问题要让调用方看见（前 3 次打栈），
            // 但不能把整台机器弄停机 —— 机器还在跑，固件只是收不到新输入。
            msgPublishErrors++;
            if (msgPublishErrors <= 3) {
                LOG.warn("[OpenComputers] 消息缓存区发布失败（第 {} 次；host 侧镜像仍在，之后不再刷日志）",
                        msgPublishErrors, t);
            }
        }
    }

    /**
     * 每 tick 与 UART **硬件**整块同步（2026-09-27 定案：**任何设备访问都不许停 CPU**）。
     *
     * <h3>它取代了什么</h3>
     * <p>旧路径是 16550 的 **MMIO 寄存器组**（{@code 0x1000_2000}）：固件每输出一个字符要读一次
     * LSR、写一次 THR —— native 内核下每字符 1~2 次 {@code MSG_MMIO} 跨线程事务，<b>每次事务都把
     * CPU 停下来等宿主兑现</b>（这就是用户说的"强制暂停 rv 内核等 mimo"）。现在 UART 是虚拟机建立的
     * 一块硬件：芯片只读写映射进 guest RAM 的**寄存器窗口**（普通 {@code lw/sw}），硬件每 tick
     * 与窗口整块同步一次 —— UART 链上一次设备事务都不会再产生。</p>
     *
     * <h3>单写者纪律</h3>
     * <p>guest 那段内存**只有本方法写**（开机那次 {@link #publishUartWindow} 是同一线程、
     * 芯片放行之前的一次性初始化）。世界侧来的字节只能经 {@link #postSerial} →
     * {@code UartHardware.offerRx} 交给硬件（这是"宿主只从外部世界那一侧供料"的唯一入口），
     * 由本方法在 tick 边界整块同步进窗口。固件独占 {@code cr1 / tx_push / rx_pop / ovr_ack}
     * 四个字段（见 {@link com.hdf.cryptand.soc.board.UartRegs}）。</p>
     *
     * @param cycles 沙箱已执行周期数（设备时间唯一来源；与 {@code OcSandboxBridge} 同一口径）
     */
    private void pumpUart(long cycles) {
        final CpuCore cpu = cpuCore;
        final com.hdf.cryptand.soc.peripheral.UartHardware hw = uart;
        if (cpu == null || hw == null) {
            return;
        }
        try {
            final com.hdf.cryptand.soc.peripheral.UartHardware.SyncResult r =
                    hw.sync(com.hdf.cryptand.soc.board.UartRegs.of(cpu), cycles);
            if (r.anomaly() && hw.anomalies() != uartReportedAnomalies) {
                uartReportedAnomalies = hw.anomalies();
                LOG.warn("[OpenComputers] UART 硬件：固件写回的计数器不自洽（累计 {} 次）——"
                                + "通常是固件/织物布局版本不一致（布局常量必须同一份）", hw.anomalies());
            }
            if (hw.missingWindow() != 0 && hw.missingWindow() != uartReportedMissing) {
                uartReportedMissing = hw.missingWindow();
                LOG.warn("[OpenComputers] UART 硬件：读不回 guest 0x{} 起那 16 字节寄存器（累计 {} 次）"
                                + "—— 检查 RAM 装配是否把 UART_CACHE_BYTES 算进去了",
                        Long.toHexString(OcBoardLayout.UART_CACHE_BASE), hw.missingWindow());
            }
        } catch (Throwable t) {
            // 与显存窗口/消息缓存区同一条纪律：这是日志出口，出问题要让调用方看见（前 3 次打栈），
            // 但不能把整台机器弄停机 —— 机器还在跑，只是日志这一路暂时不通。
            uartSyncErrors++;
            if (uartSyncErrors <= 3) {
                LOG.warn("[OpenComputers] UART 硬件同步失败（第 {} 次；之后不再刷日志）", uartSyncErrors, t);
            }
        }
    }

    /**
     * 把 UART 硬件的**寄存器窗口初始镜像**写进 guest RAM（开机一次）。
     *
     * <p>必须在芯片放行之前：固件一起来就读魔数（{@code hal_uart_hw_init}）。读到 0 它会
     * 明确报"窗口没就绪"（在调试口打一笔 0xEE 并丢弃输出）而不是把日志静默丢掉。</p>
     */
    private void publishUartWindow(CpuCore cpu) {
        try {
            cpu.writeMemory(OcBoardLayout.UART_CACHE_BASE, uart.initialImage());
            LOG.info("[OpenComputers] UART 硬件已建立：guest 0x{} 起 {} 字节（寄存器区 {} + 发送窗口 {} + "
                            + "接收窗口 {}）；本芯片装载 {}，占 {} 组件槽位 —— 芯片只写 DR / 读 DR / 轮询状态位，"
                            + "UART 链上零 MMIO 事务",
                    Long.toHexString(OcBoardLayout.UART_CACHE_BASE),
                    com.hdf.cryptand.soc.board.UartRegs.TOTAL_BYTES,
                    com.hdf.cryptand.soc.board.UartRegs.HEADER_BYTES,
                    com.hdf.cryptand.soc.board.UartRegs.TX_BYTES,
                    com.hdf.cryptand.soc.board.UartRegs.RX_BYTES,
                    uart.moduleLoaded() ? ("通用 FIFO 模块（深度 " + uart.moduleDepth() + "）") : "1 字节 DR",
                    uart.moduleSlots());
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] UART 硬件窗口初始发布失败（固件会报窗口没就绪并在调试口打 0xEE：明确报错）", t);
        }
    }

    /**
     * **世界侧 → 器件** 的供料入口（宿主唯一能碰 UART 的地方）。
     *
     * <p>用户 2026-09-27 定案："虚拟机就是类似 FPGA 的硬件层面的接口，虚拟机只对芯片建立模拟组件…
     * 供芯片使用"。所以宿主**不碰芯片的寄存器**，只把外部世界（真实串口桥 / 无人化工具 / 控制台）
     * 的字节交给这块硬件；硬件按自己的波特率与缓冲把它变成状态位（RXNE/OVR）给芯片看。</p>
     *
     * <p>投递是"每 tick 整块"的形态：本方法只入织物侧队列（纯 Java 状态，任何线程可调），
     * 由 {@link #pumpUart} 在 tick 边界搬进硬件缓存。队列满则计数丢弃（{@code uartQueueOverflow()}），
     * 绝不静默。</p>
     *
     * @return 实际接受的字节数
     */
    public int postSerial(byte[] data) {
        if (uart == null || data == null || data.length == 0) {
            return 0;
        }
        return uart.offerRx(data, 0, data.length);
    }

    /** UART 硬件诊断（心跳日志用） */
    private String uartStats() {
        final com.hdf.cryptand.soc.peripheral.UartHardware hw = uart;
        if (hw == null) {
            return "未装配";
        }
        return (hw.moduleLoaded() ? ("FIFO" + hw.moduleDepth()) : "1B DR")
                + (hw.fifoEnabled() ? " 使能" : " 旁路") + "，生效容量 " + hw.capacity()
                + "，收窗占用 " + hw.rxWindowLevel()
                + "，世界侧待投 " + hw.inboxPending()
                + "，已发 " + hw.txTotal() + "，已收 " + hw.rxTotal()
                + "，溢出 " + hw.ovrTotal() + "（发 " + hw.ovrTx() + "/收 " + hw.ovrRx() + "）"
                + "，同步 " + hw.syncs() + " 次，异常 " + hw.anomalies();
    }

    /**
     * 把消息缓存区的**初始镜像**（0 值数据区 + 头）写进 guest RAM（开机一次）。
     *
     * <p>必须发生在沙箱放行之前：固件一起来就会读魔数（{@code hal_msg_ready}），
     * 读到 0 会明确报"窗口没就绪"，而不是把"没有输入"当成"没有窗口"。</p>
     */
    private void publishMessageWindow(CpuCore cpu) {
        try {
            cpu.writeMemory(OcBoardLayout.MSG_QUEUE_BASE, guestMessages.initialImage());
            // 开机这一次发布覆盖到"当前入队计数"（此刻还没追加任何消息；紧随其后的开机通知会再标脏）
            guestMessages.markFlushed(guestMessages.appended());
            msgDataSeq = guestMessages.appended();
            msgFlushes = 0L;
            LOG.info("[OpenComputers] 消息缓存区已接上：guest 0x{} 起 {} 字节（头 {} + 环形数据区 {}）——"
                            + "键盘/串口/宿主通知统一从这里进虚拟机（固件 hal_msg_take 按 tail 消费）",
                    Long.toHexString(OcBoardLayout.MSG_QUEUE_BASE), OcBoardLayout.MSG_QUEUE_BYTES,
                    HostMessageRing.HEADER_BYTES, HostMessageRing.MESSAGE_BYTES);
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] 消息缓存区初始发布失败（固件会报窗口没就绪：明确报错，不是静默）", t);
        }
    }

    /**
     * 往消息缓存区投递一条消息（**主线程 → 虚拟机 的唯一入口**）。
     *
     * <p>键盘、串口字节、宿主通知、外部事件都走它 —— 同一件事只有这一条路，
     * 不存在"某类消息改走别处"的第二套实现。</p>
     *
     * <p><b>线程</b>：任何线程都可以调（服务端 tick / OC 组件回调 / MCP 工具 / 渲染输入报文），
     * 内部对队列加锁；本方法<b>只追加进队列，绝不写 guest RAM</b>（那是 {@link #pumpMessageQueue()} 的活）。</p>
     *
     * @param kind    {@link HostMessageRing#KIND_KEY} / {@code KIND_SERIAL} / {@code KIND_EVENT} / {@code KIND_NOTICE}
     * @param payload 消息负载（&gt;255 字节会被截到 255，与固件侧帧格式一致）
     * @return 是否入队；false = 队列满被丢弃（{@link #msgDropped()} 会 +1，绝不静默丢）
     */
    public boolean postToGuest(int kind, byte[] payload) {
        final boolean ok = guestMessages.append(kind, payload);
        if (ok && kind == HostMessageRing.KIND_KEY) {
            // 按键延迟台账（2026-09-28）：入队 → 固件把 tail 推过去 = "这次按键花了多久"。
            // 只对 KIND_KEY 记，人类打字频率下日志不会刷屏；固件的取走时刻由 pumpMessageQueue
            // 每 tick 读回的 tail 判定（≤1 tick 的量化误差，量级 50 ms，够分辨"几十毫秒"和"几秒"）。
            keyPostNanos = System.nanoTime();
            keyWaiting = true;
        }
        if (!ok) {
            final long dropped = guestMessages.dropped();
            // 塞满时可能一连丢几千条：只在头几条与每 256 条打一行，避免刷爆日志
            if (dropped <= 3 || dropped % 256 == 0) {
                LOG.warn("[OpenComputers] 消息缓存区已满（上限 {} 字节，待发 {}）：本条 kind={} 被丢弃，"
                                + "累计丢弃 {} 条 —— 固件没来得及消费；丢弃计数也写在消息头里（固件 msg 命令可读）",
                        guestMessages.capacity(), guestMessages.pendingBytes(), kind, dropped);
            }
        }
        return ok;
    }

    /** 按键延迟台账：最近一次按键的入队时刻（纳秒）与"还在等固件取走"标志（只碰纯 Java 状态） */
    private volatile long keyPostNanos;
    private volatile boolean keyWaiting;
    /** 历史上最差的一次按键延迟（毫秒）—— 面板/无人化读它，比"看日志"稳 */
    private long keyLatencyWorstMs;

    /** 最近一次按键从入队到被固件取走的毫秒数（-1 = 还没有过一次完整样本） */
    public long keyLatencyLastMs() {
        return keyLatencyLastMs;
    }

    /** 历史最差按键延迟（毫秒） */
    public long keyLatencyWorstMs() {
        return keyLatencyWorstMs;
    }

    private long keyLatencyLastMs = -1;

    private String msgStats() {
        final HostMessageRing r = guestMessages;
        return ("待发 " + r.pendingBytes() + "/" + r.capacity() + " 字节（" + r.pendingMessages() + " 条）"
                + "，已消费 " + r.consumedBytes() + " 字节"
                + "，丢弃 " + r.dropped() + " 条"
                + "，seq=" + r.appended() + "，发布 " + msgFlushes + " 次");
    }

    /** 待发消息字节数（无人化/面板诊断） */
    public int msgPendingBytes() {
        return guestMessages.pendingBytes();
    }

    /** 消息缓存区容量（字节） */
    public int msgCapacity() {
        return guestMessages.capacity();
    }

    /** 因满被丢弃的消息条数（0 = 从未丢过；非 0 必须让调用方看到） */
    public long msgDropped() {
        return guestMessages.dropped();
    }

    /** 累计入队消息条数（单调；固件侧对应头里的 seq 字段） */
    public long msgSeq() {
        return guestMessages.appended();
    }

    /** 固件已消费的字节数（宿主侧口径：固件写回的 tail 累计推进量） */
    public long msgConsumedBytes() {
        return guestMessages.consumedBytes();
    }

    /** 已发布进 guest RAM 的次数（诊断：0 = 一次都没发过，说明沙箱没起来或地址错了） */
    public long msgFlushes() {
        return msgFlushes;
    }

    /** 停机/故障日志（跑飞了要能一眼看出原因，别静默；native 与 Java 内核统一走 CpuCore 契约） */
    private void reportFaultIfAny() {
        final CpuCore cpu = cpuCore;
        if (cpu == null || !cpu.isFaulted()) {
            return;
        }
        final var f = cpu.getFault();
        final String fault = (f == null || f == com.hdf.cryptand.soc.api.SocFault.NONE)
                ? "faulted (unknown cause)"
                : f.toString();
        if (!fault.equals(lastFault)) {
            lastFault = fault;
            LOG.warn("[OpenComputers] ⚠ Cryptand OS 停下来了：{}（已执行 {} 指令，PC=0x{}，内核={}）",
                    fault, cpu.getInstructionsRetired(), Long.toHexString(cpu.getProgramCounter()),
                    nativeKernel ? "C++ native" : "Java");
        }
    }

    /** 主线程里兑现"需要碰世界"的部分。 */
    @Override
    public void runSynchronized() {
        // ★ 组件调用在这里兑现 —— 这是 OC 指定的**主线程**上下文（见 runThreaded 里 SynchronizedCall
        //   那段说明）。本方法只在 runThreaded 返回 SynchronizedCall 时被 OC 调用
        //   （Machine.scala:618-650：switchTo(Running) → inSynchronizedCall = true → runSynchronized()），
        //   因此既线程安全，又绕过 direct 调用预算。
        //   drainCalls 会**连续**兑现（固件是串行的：一次调用 → 轮询 STATUS → 下一次；同一次
        //   主线程回调里它往往已经发起了下一个），并带 8ms 上限 —— 固件高频调用也不会把主线程占满。
        //   这就是"拿不到就跳过、绝不卡主线程"的落实：宁可让固件这次看到 ERROR 并重试，
        //   也绝不在任何一方阻塞等待。
        final OcArchitectureCore c = core;
        if (c != null) {
            // ⚠ 不再用时间上限（原 drainCalls(8) = 最多 8ms）：单个 gpu 渲染就可能超过它，

            //   结果是每 tick 只消化一两个、队列长期排不空 ⇒ 屏幕滞后（用户 2026-09-25 定案：
            //   "一次性消化完成"）。
            final int done = c.drainCalls(0);
            if (done > 0 && (threadTicks <= 3 || threadTicks % 200 == 0)) {
                LOG.info("[OpenComputers] 组件调用（主线程批量消化）：本次 {} 个（tick {}，仍有积压={}，累计丢弃 {}）",
                        done, threadTicks, c.hasPendingCalls(), c.droppedCalls());
            }
        }
    }

    // ==================== 信号与连接 ====================

    /**
     * OC 队列里来了新信号 ⇒ **把键盘按键喂给固件**。
     *
     * <p>为什么是这里：OC 的键盘按键不是"某个方法调用"，而是**信号** ——
     * {@code Keyboard.scala:73-76} 发 {@code "key_down"} / {@code "text_input"}，
     * 经 {@code computer.checked_signal} 由 {@code Machine.scala:670-672} 转成
     * {@code Machine.signal(name, 组件地址, ...)}，最后回调本方法。而 Cryptand OS 的
     * shell 的输入是 {@code console.c} 的 {@code shell_feed} 一个字符一个字符吃的。</p>
     *
     * <p>接法（2026-09-27 起）：抽干信号队列（一次 {@code onSignal} 可能积压多个按键），
     * 把键盘类的信号翻译成字节投进**统一消息缓存区**（{@link HostMessageRing#KIND_KEY}，
     * 由 {@link #pumpMessageQueue()} 每 tick 发布进 guest RAM）—— <b>不新增第二套输入通道</b>：
     * 宿主的入口只有 {@link #postToGuest}，固件的入口只有"读那段内存"。</p>
     *
     * <p>⚠ 以前这里是把字节塞进 UART RX。弃用原因（真机实测）：UART 的硬件 FIFO 只有 16 字节，
     * 快速输入/粘贴/自动化注入必然丢字节（一次注入 "help"+CR 就把回车挤掉）。
     * 消息缓存区是 4096 字节的 guest RAM，灌满前不会丢，丢了也有 {@code dropped} 计数。</p>
     *
     * <p>⚠ 线程：本方法在"发信号者线程"上被调用（玩家按键 → 组件 → 主线程），
     * 而 {@link HostMessageRing} 的 append 只碰纯 Java 状态（不碰世界、不碰 UART），
     * 真正的写内存发生在服务端 tick 的 {@link #pumpMessageQueue()} 里 —— 那条界线就是
     * "组件回调里只碰纯数据"的纪律。</p>
     */
    @Override
    public void onSignal() {
        final Machine m = machine;
        if (m == null) {
            return;
        }
        // 上限保护：正常一次也就一两个按键；给个上限避免异常情况下把主线程占住
        for (int guard = 0; guard < 512; guard++) {
            final li.cil.oc.api.machine.Signal sig;
            try {
                sig = m.popSignal();
            } catch (Throwable t) {
                return;                     // 信号队列异常不该拖垮机器
            }
            if (sig == null) {
                return;
            }
            // ⚠ 形态判断**只此一处**（{@link OcKeyboardInput#encodeSignal}）：key_down 的
            //   args 是 (char, code)、text_input 的 args[1] 才是 String。老代码在这里只认
            //   String ⇒ 真人按回车/退格（Character）全被丢掉，症状是"能打字、控制键无效"。
            final byte[] bytes = OcKeyboardInput.encodeSignal(sig.name(), sig.args());
            if (bytes == null) {
                continue;                   // 不是键盘输入信号（component_added / key_up 之类）：消费掉即可
            }
            // 诊断（默认完全静默；-Dcryptand.debug.calls=1 时逐次打印，不做采样）：
            // 真机上"某个键没反应"唯一能定性的事实就是 OC 到底送了什么形态、我们注入了什么。
            if (OcKeyboardInput.debugEnabled()) {
                LOG.info("[OpenComputers] 键盘信号 {}：args=[{}] → 注入 {} 字节",
                        sig.name(), OcKeyboardInput.describeArgs(sig.args()), bytes.length);
            }
            // 按键 → **消息缓存区**（KIND_KEY），一次按键 = 一条消息。
            // ⚠ 不再逐字节塞 UART RX：那是 16 字节的硬件 FIFO，"一次注入一段输入"必丢字节
            //   （2026-09-25 实测：oc_key 注入 5 字节就把回车挤掉）。UART 仍然保留（TX 是日志出口），
            //   只是不再是主线程→虚拟机的输入通道。
            postToGuest(HostMessageRing.KIND_KEY, bytes);
        }
    }

    /**
     * 注入一次键盘输入（供 AI 工具 / E2E 自动化用）。
     *
     * <p>走的是**与真人按键完全相同的后续路径**（消息缓存区 → 固件 shell），
     * 唯一差别是绕过了"键盘组件 → 信号"那一跳 —— 那一跳只有真人按键才会走，
     * 本方法存在的意义就是让"输入之后的一切"可被无人值守地验证。</p>
     *
     * @param key  单个键名（{@code a} / {@code enter} / {@code space} / {@code backspace} …）
     * @param text 整段文本（先注入 text，再注入 key）
     * @return 实际**入队**的字节数（&lt; 请求量 = 队列满，差额进 {@link #msgDropped()}）
     */
    public int injectInput(String key, String text) {
        int n = 0;
        if (text != null && !text.isEmpty()) {
            n += postKeyBytes(OcKeyboardInput.encodeText(text));
        }
        if (key != null && !key.isEmpty()) {
            n += postKeyBytes(OcKeyboardInput.encodeKey(key));
        }
        return n;
    }

    /**
     * 逐字节投递键盘消息（一段文本 = 每字节一条消息）。
     *
     * <p>为什么按字节而不是"一整段一条"：固件那边是按字节喂行编辑的，
     * 而"塞满丢弃"要能精确到字符才可观测（一条 6000 字节的消息要么全进要么全丢，
     * 固件看到的是一个截断的巨帧）。逐字节 = 谁被丢了、丢了几个字符一目了然。</p>
     *
     * @return 真正入队的字节数（&lt; 入参 说明队列满了，被丢的那些进 dropped 计数）
     */
    private int postKeyBytes(byte[] bytes) {
        int ok = 0;
        for (final byte b : bytes) {
            // ⚠ 满了**不 break**：后面每一个进不去的字节都要进 dropped 计数。
            //   break 会让"一次灌 6000 字节、只进去 1365"报成"丢弃 1 条"，
            //   调用方据此以为只丢了一个字符 —— 那就是静默丢（纪律不允许）。
            if (postToGuest(HostMessageRing.KIND_KEY, new byte[]{b})) {
                ok++;
            }
        }
        return ok;
    }

    /** 接入网络（P2 让核心感知组件可用了） */
    @Override
    public void onConnect() {
        // TODO(P2)
    }

    // ==================== 持久化 ====================

    @Override
    public void loadData(CompoundTag nbt) {
        // P1 无持久状态（固件落盘复用 Cryptand 的 chip/<uuid>.bin 机制，P2 接）
    }

    @Override
    public void saveData(CompoundTag nbt) {
        // 同上
    }

    // ==================== 诊断访问器 ====================

    /** 最近一帧屏幕文本（无人化断言；未接组件总线时为 null） */
    public String screenText() {
        final com.hdf.cryptand.neoforge.opencomputers.OcComponentBus b = componentBus;
        return b == null ? null : b.lastFrameText();
    }

    /** 诊断头：架构身份 + 总线的帧计数（"读不到屏幕"时用它区分原因） */
    public String screenDebug() {
        final com.hdf.cryptand.neoforge.opencomputers.OcComponentBus b = componentBus;
        return "arch=#" + Integer.toHexString(System.identityHashCode(this))
                + " uart=#" + (uart == null ? "null" : Integer.toHexString(System.identityHashCode(uart)))
                + " " + (b == null ? "bus=null" : b.statsText());
    }

    /**
     * UART 接收窗口里还有多少字节没被芯片读走（-1 = 无 UART）—— 判断"投递有没有到芯片"。
     *
     * <p>⚠ 2026-09-27：这里以前读的是 16550 的硬件 RX FIFO 占用，那条 MMIO 路径已整条删除；
     * 现在读的是**虚拟机那块 UART 硬件**的接收窗口占用（同一个问题的正确答案）。</p>
     */
    public int uartAvailable() {
        return uart == null ? -1 : uart.rxWindowLevel();
    }

    /** UART 世界侧累计送进器件的字节数（-1 = 无 UART） */
    public long uartRxBytes() {
        return uart == null ? -1 : uart.rxTotal();
    }

    /** UART 寄存器窗口基址（工具/面板显示用；0 = 未装配） */
    public long uartCacheBase() {
        return uart == null ? 0L : OcBoardLayout.UART_CACHE_BASE;
    }

    /** UART 寄存器窗口总字节数（0 = 未装配） */
    public int uartCacheBytes() {
        return uart == null ? 0 : com.hdf.cryptand.soc.board.UartRegs.TOTAL_BYTES;
    }

    /** UART 硬件：固件写回的计数器不自洽次数（正常恒为 0） */
    public long uartCacheAnomalies() {
        return uart == null ? -1 : uart.anomalies();
    }

    /** **UART 溢出（丢掉）的字节数** —— 0 = 从未丢过；非 0 必须让调用方看到（OVR 语义） */
    public long uartOvr() {
        return uart == null ? -1 : uart.ovrTotal();
    }

    /** UART 发送侧溢出（软件没看 TXE 就连写 DR，被覆盖掉的字节数） */
    public long uartOvrTx() {
        return uart == null ? -1 : uart.ovrTx();
    }

    /** UART 接收侧溢出（新字节到达而缓冲没腾空，丢掉的字节数） */
    public long uartOvrRx() {
        return uart == null ? -1 : uart.ovrRx();
    }

    /** UART 生效的硬件缓冲容量（1 = 经典 1 字节 DR；装载 FIFO 模块且软件使能 = 模块深度） */
    public int uartSlots() {
        return uart == null ? -1 : uart.capacity();
    }

    /** 本芯片装载的 FIFO 模块深度（0 = 未装载，就是经典 1 字节 DR） */
    public int uartFifoDepth() {
        return uart == null ? -1 : uart.moduleDepth();
    }

    /** FIFO 模块是否被软件使能（CR1.FIFOEN；不使能 ⇒ 生效容量退回 1 字节） */
    public boolean uartFifoEnabled() {
        return uart != null && uart.fifoEnabled();
    }

    /** 装载 FIFO 模块占用的组件槽位（计价口径：PeripheralFifo.slotsFor） */
    public int uartFifoSlots() {
        return uart == null ? -1 : uart.moduleSlots();
    }

    /** UART 最近一次发布的状态位（诊断：看 TXE/TC/RXNE/OVR 的现场） */
    public int uartStatusBits() {
        return uart == null ? -1 : uart.lastSr();
    }

    public Machine machine() {
        return machine;
    }

    /**
     * 本机处理器的 ISA / 位宽（未插处理器 / 未开机时为 null）。
     *
     * <p>诊断必须问这里：位宽由 <b>CPU 部件</b> 决定，而"这台机器到底按几位在跑"
     * 是排查 64 位芯片异常时第一个要确认的事实（{@code oc_machine_state} 会打出来）。</p>
     */
    public SocIsa isa() {
        return cpuIsa;
    }

    public long memoryBytes() {
        return memoryBytes;
    }

    public int componentCount() {
        return components.size();
    }

    /**
     * 当前跑的是不是 C++ native 内核。
     *
     * <p>⚠ 诊断必须问这里，别去 {@link #toString()} 里找 "native" 字样
     * —— 那段文本里从来没有这个词，靠它判断会把 native 内核一律报成 Java
     * （{@code oc_machine_state} 就这么误报过）。</p>
     */
    public boolean nativeKernel() {
        return nativeKernel;
    }

    @Override
    public String toString() {
        return "CryptandOcArchitecture[c-rv32, isa=" + (cpuIsa == null ? "none" : cpuIsa.id())
                + ", components=" + components.size()
                + ", memory=" + memoryBytes + "B, " + (running ? "running]" : "stopped]");
    }

    // ==================== 高级分析器（面板 / soc_inspect）的只读诊断访问器（2026-09-27 追加） ====================
    //
    // 全部是**纯 getter**：只把本类已有字段读出来，不改执行路径、不改日志、不改装配顺序
    // （面板与 MCP 工具必须看到同一份数据 —— 各自反射或各算一套，迟早出现"面板 45%、工具 44%"）。
    // 为什么必须放在本类：这些数据的持有者只有它（sandbox / threadTicks / guestMessages /
    // componentBus / uart），外部拿不到。

    /** 沙箱是否在跑（{@link SandboxVm#isRunning()}）；未开机 = false */
    public boolean vmRunning() {
        return sandbox != null && sandbox.isRunning();
    }

    /** 沙箱是否停在"等宿主兑现设备访问"（{@link SandboxVm#isWaitingMmio()}） */
    public boolean vmWaitingMmio() {
        return sandbox != null && sandbox.isWaitingMmio();
    }

    /** 沙箱是否已故障停机（{@link SandboxVm#isFaulted()}） */
    public boolean vmFaulted() {
        return sandbox != null && sandbox.isFaulted();
    }

    /** 固件是否自己停机了（ECALL / PARK，{@link SandboxVm#isHalted()}） */
    public boolean vmHalted() {
        return sandbox != null && sandbox.isHalted();
    }

    /** 沙箱 PC（{@link SandboxVm#pc()}）；未开机 = -1 */
    public int vmPc() {
        return sandbox == null ? -1 : sandbox.pc();
    }

    /** 沙箱累计退休指令数（{@link SandboxVm#instructionsRetired()}）；未开机 = -1 */
    public long vmInstructions() {
        return sandbox == null ? -1 : sandbox.instructionsRetired();
    }

    /** 沙箱自报的累计周期（字段 {@code sandboxCycles}：心跳里按 instret 增量累加，含等设备访问的停顿） */
    public long sandboxCycles() {
        return sandboxCycles;
    }

    /** 故障原因码（{@link SandboxVm#faultCause()}，0 = 无故障）；未开机 = -1 */
    public int vmFaultCause() {
        return sandbox == null ? -1 : sandbox.faultCause();
    }

    /** OC 工作线程驱动本架构的次数（字段 {@code threadTicks}；自驱动模型下就是**心跳次数**） */
    public long heartbeats() {
        return threadTicks;
    }

    /** "实测 MHz"的累计窗口毫秒数（字段 {@code measuredNanos} 的只读视图） */
    public long measuredWindowMillis() {
        return measuredNanos / 1_000_000L;
    }

    /** guest RAM 实际映射字节数（{@code initialize()} 装配时算出的 {@code ramBytes}）；未开机 = 0 */
    public int mappedRamBytes() {
        return mappedRamBytes;
    }

    /**
     * 邮箱里**未消化**的调用数（未开机 = -1）。
     *
     * <h3>口径（2026-09-27 修正）</h3>
     * <p>取自核心的**配对账本**里的**积压**（{@link OcArchitectureCore#mailboxBacklog()} = 在途**且**
     * 超过 2 个 tick 还没写回的通道数），<b>不再</b>数 guest 内存里 {@code MB_STATE == BUSY} 的通道，
     * 也不把"正常的在途事务"算进来（宿主每 tick 消化一次 ⇒ 一条正常调用在途 ≤1 个 tick）。
     * 为什么必须改：
     * 宿主的 BUSY/DONE 写回是**排队**进 native 内核命令队列、由沙箱线程在指令边界才落地的
     * （{@code SandboxVm.writeMemory} = {@code CMD_WMEM}），所以 guest 看到的 STATE
     * <b>永远滞后</b>于宿主自己的动作 —— 采样它必然把"宿主已经兑现完、DONE 还在路上"的那一瞬
     * 算成"未消化 1 条"。真机现象正是"机器完全空闲也恒为 1"（3 次采样一致），
     * 因为它采的是**在途事务**，不是"没消化完的积压"。</p>
     *
     * <h3>guest 的 STATE 仍然读，但只作对账证据</h3>
     * <p>两条证据必须一致才说明"空闲"：账本说没有在途调用、而 guest 还停在 BUSY ⇒ 那是
     * <b>真有调用没写回</b>（写侧的认领/写回没配对），必须查，绝不能显示成 1 就算了。
     * 不一致时打一行诊断（通道号/组件/方法/认领与写回次数/队列积压），节流到 2 秒一行。</p>
     */
    public int mailboxPendingCalls() {
        final CpuCore cpu = cpuCore;
        final OcArchitectureCore c = core;
        if (cpu == null || c == null) {
            return -1;
        }
        final int inFlight = c.mailboxInFlight();
        final int backlog = c.mailboxBacklog();
        final int guestBusy = guestMailboxBusyChannels(cpu);
        if (guestBusy < 0) {
            return -1;                      // 读不到 guest 内存 ⇒ 如实返回占位，不编造 0
        }
        logMailboxLedgerOnce(c, inFlight, backlog, guestBusy);      // 每次开机一行：口径与配平证据
        if (guestBusy != inFlight) {
            logMailboxAccount(c, cpu, inFlight, backlog, guestBusy);
        }
        return backlog;
    }

    /**
     * **每次开机一行**邮箱账本小结（只在第一次读该指标时打）。
     *
     * <p>为什么要这一行：显示出来的只有一个数，而"这个数为什么是 0（或为什么不是 0）"需要两件事
     * 同时在场 —— ①口径（积压 vs 在途）②认领/写回是否**配平**（配平 = 写侧没有漏配对）。
     * 一次开机一行，正常情况零噪声，排障时一眼定案。</p>
     */
    private void logMailboxLedgerOnce(OcArchitectureCore core, int inFlight, int backlog, int guestBusy) {
        if (mailboxLedgerLogged) {
            return;
        }
        mailboxLedgerLogged = true;
        LOG.info("[OpenComputers] 邮箱账本：显示口径=**积压**（在途超过 {} ms 才算）；当前 在途={} 积压={}，"
                        + "认领/写回配平={}，guest 侧 STATE=BUSY 采样={}，宿主队列积压={}，累计丢弃={}"
                        + " —— guest 的 BUSY 会滞后于宿主队列（写入走 CMD_WMEM），只作对账",
                OcArchitectureCore.MAILBOX_DIGEST_GRACE_NANOS / 1_000_000L,
                inFlight, backlog, core.mailboxAllPaired() ? "全部配平" : "有不配平通道",
                guestBusy, core.hasPendingCalls(), core.droppedCalls());
        if (backlog > 0) {
            LOG.warn("[OpenComputers] ⚠ 邮箱有 {} 条**积压**未消化（在途超过 {} ms）—— 不是正常在途事务，"
                            + "查平台侧 runSynchronized 是否还在消化、以及认领/写回是否配平",
                    backlog, OcArchitectureCore.MAILBOX_DIGEST_GRACE_NANOS / 1_000_000L);
        }
    }

    /**
     * guest 邮箱 64 通道里 {@code MB_STATE == BUSY} 的通道数（**采样口径，只用于对账**）。
     *
     * @return 读不到 guest 内存时返回 -1（与 {@link #mailboxPendingCalls()} 的占位口径一致）
     */
    private int guestMailboxBusyChannels(CpuCore cpu) {
        int busy = 0;
        for (int i = 0; i < OcAbi.MAILBOX_CHANNELS; i++) {
            final int state;
            try {
                state = readGuestInt(cpu, OcAbi.mailboxChannel(i) + OcAbi.MB_STATE);
            } catch (Throwable t) {
                return -1;
            }
            if (state < 0) {
                return -1;
            }
            if (state == OcAbi.MB_STATE_BUSY) {
                busy++;
            }
        }
        return busy;
    }

    /**
     * 邮箱对账不一致的诊断（**只在真的不一致时输出**，正常零日志；2 秒一行，避免刷屏）。
     *
     * <p>它要能一次回答"到底该修哪边"：</p>
     * <ul>
     *   <li>{@code 宿主在途=0} 而 {@code guest BUSY=1} 且该通道 {@code 认领==写回} ⇒ 写回**发出去了**
     *       但 guest 没看到终态（写侧落地问题），账本是配平的；</li>
     *   <li>某通道 {@code 认领 > 写回} ⇒ 真有调用认领了没写回（写侧漏配对，是必须修的 bug）。</li>
     * </ul>
     */
    private void logMailboxAccount(OcArchitectureCore core, CpuCore cpu, int inFlight, int backlog,
                                  int guestBusy) {
        final long now = System.nanoTime();
        if (now - lastMailboxAccountNanos < 2_000_000_000L) {
            return;
        }
        lastMailboxAccountNanos = now;
        final StringBuilder detail = new StringBuilder();
        for (int i = 0; i < OcAbi.MAILBOX_CHANNELS; i++) {
            final int state = readGuestInt(cpu, OcAbi.mailboxChannel(i) + OcAbi.MB_STATE);
            if (state != OcAbi.MB_STATE_BUSY) {
                continue;
            }
            detail.append(" ch").append(i)
                    .append("[STATE=").append(state)
                    .append(" comp=").append(readGuestInt(cpu, OcAbi.mailboxChannel(i) + OcAbi.MB_COMPONENT))
                    .append(" method=").append(readGuestInt(cpu, OcAbi.mailboxChannel(i) + OcAbi.MB_METHOD))
                    .append(" argc=").append(readGuestInt(cpu, OcAbi.mailboxChannel(i) + OcAbi.MB_ARGC))
                    .append(" seq=").append(readGuestInt(cpu, OcAbi.mailboxChannel(i) + OcAbi.MB_SEQ))
                    .append(" 认领=").append(core.mailboxClaims(i))
                    .append(" 写回=").append(core.mailboxFinishes(i)).append("]");
        }
        for (int i = 0; i < OcAbi.MAILBOX_CHANNELS; i++) {
            if (!core.mailboxPaired(i)) {
                detail.append(" ch").append(i).append("[未配对 认领=").append(core.mailboxClaims(i))
                        .append(" 写回=").append(core.mailboxFinishes(i)).append("]");
            }
        }
        LOG.warn("[OpenComputers] 邮箱对账不一致：宿主在途={} 积压={}（账本 认领/写回 未配对={}，队列积压={}，"
                        + "累计丢弃={}）而 guest BUSY={} ——{}（对账用；显示的未消化数以账本为准）",
                inFlight, backlog, core.mailboxAllPaired() ? "全部配平" : "有不配平通道",
                core.hasPendingCalls(), core.droppedCalls(), guestBusy,
                detail.length() == 0 ? " BUSY 通道=无" : detail);
    }

    /** 读 guest 内存里一个小端 32 位整数（读不到 = -1） */
    private int readGuestInt(CpuCore cpu, long address) {
        final byte[] raw = cpu.readMemory(address, 4);
        if (raw == null || raw.length < 4) {
            return -1;
        }
        return (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8) | ((raw[2] & 0xFF) << 16) | ((raw[3] & 0xFF) << 24);
    }

    /** 消息缓存区**待发条数**（{@link HostMessageRing#pendingMessages()}，与 msgPendingBytes() 同一份来源） */
    public int msgPendingMessages() {
        return guestMessages.pendingMessages();
    }

    // ---- GPU / 组件总线（OcComponentBus 的只读视图；未装配 = -1 / 空串）----

    /** 已绑定的屏幕地址（空串 = 还没 bind 过） */
    public String gpuBoundScreen() {
        return componentBus == null ? "" : componentBus.boundScreenAddress();
    }

    /** gpu_blit 上屏帧数 */
    public long gpuBlitFrames() {
        return componentBus == null ? -1 : componentBus.blitFrames();
    }

    /** 上屏的 set 行数合计（显存页路径 + 逐行兜底路径） */
    public long gpuBlitRows() {
        return componentBus == null ? -1 : componentBus.blitRows();
    }

    /** 走"显存页 + 一次 bitblt"上屏的帧数 */
    public long gpuPageBlits() {
        return componentBus == null ? -1 : componentBus.pageBlits();
    }

    /** 退回逐行 set 的帧数（> 0 说明页路径不可用，屏幕照旧有字） */
    public long gpuRowFallbacks() {
        return componentBus == null ? -1 : componentBus.rowFallbacks();
    }

    /** 组件调用失败次数 */
    public long gpuFailedCalls() {
        return componentBus == null ? -1 : componentBus.failedCalls();
    }

    /**
     * 组件表的**有序**只读视图（下标 = 固件的组件句柄，与 {@link OcComponentBus#components()} 同一份）。
     *
     * <p>为什么不直接读 {@code Machine.components()}：那张表是 Scala 的 {@code mutable.Map}，
     * 迭代顺序不稳定、还含机箱自身 —— 句柄顺序的唯一来源是组件总线（它才做了剔除与排序）。</p>
     */
    public List<OcComponentBus.Entry> componentTable() {
        return componentBus == null ? List.of() : componentBus.components();
    }

    // ---- UART（2026-09-27 起 = 虚拟机建立的一块硬件；未装配 = -1）----

    /** UART 接收窗口里还没被芯片读走的字节数（世界侧投递到没到芯片，看它） */
    public int uartQueued() {
        return uart == null ? -1 : uart.rxWindowLevel();
    }

    /** UART 接收窗口容量（字节；= 芯片设计值） */
    public int uartQueueMax() {
        return uart == null ? -1 : com.hdf.cryptand.soc.board.UartRegs.RX_BYTES;
    }

    /** UART 溢出（丢掉）的字节数（> 0 = 曾经丢过，绝不静默） */
    public long uartQueueOverflow() {
        return uart == null ? -1 : uart.ovrTotal();
    }

    /** UART 累计发送（TX）字节数（硬件按波特率交给世界侧出口的计数） */
    public long uartTxBytes() {
        return uart == null ? -1 : uart.txTotal();
    }

    /** 世界侧队列因满丢弃的字节数（投递上限到了，绝不静默） */
    public long uartInboxDropped() {
        return uart == null ? -1 : uart.inboxDropped();
    }
}
