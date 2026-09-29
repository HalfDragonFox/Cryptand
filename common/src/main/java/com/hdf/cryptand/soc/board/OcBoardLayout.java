package com.hdf.cryptand.soc.board;

/**
 * ===== OC 板级布局常量（common，纯 Java 零 MC）=====
 *
 * <p>这些地址原本硬编码在 neoforge 侧的 {@code CryptandOcArchitecture} 里。按项目的铁律
 * （"非 MC 强相关内容一律优先放 common"）它们必须在这一份：因为**离线验证装置**要装配一块
 * 与真机<b>同构</b>的板子来跑固件，而"同构"的前提就是两边读的是同一组地址 ——
 * 各写一份迟早会出现"沙盒里跑得好、真机上设备影子都找不到"。</p>
 *
 * <p>⚠ 数值必须与固件链接脚本一致（{@code excode/firmware/common/cryptand_os.ld} 与
 * {@code cryptand_boot.ld}）：改这里就得同步改链接脚本。</p>
 */
public final class OcBoardLayout {

    /** ROM 起始（Boot 从这里执行） */
    public static final long ROM_BASE = 0x0000_0000L;

    /** 系统区载入地址：Boot 把系统镜像刷到这里并跳转（= 链接脚本的 ROM 起点） */
    public static final long SYS_LOAD_BASE = 0x0001_0000L;

    /** ROM 总容量（Boot 64KB + 系统区 448KB） */
    public static final int ROM_BYTES = 512 * 1024;

    /** 寄存器桥（组件调用 / 引导服务 / 配置块） */
    public static final long REG_BASE = 0x1000_0000L;

    /** 定时器（FreeRTOS 的 tick） */
    public static final long TIMER_BASE = 0x1000_1000L;

    /** 调试控制台（MMIO 单字节口，2026-09-24）。
     *
     * <p>用户："可以通过定义 #define + LOG 的形式打印输出，把 uart0 输出转接到屏幕上"。
     * 它当年存在的理由是"UART 会被系统重新配置、输出会消失"——**那条理由随 2026-09-27 的
     * UART 缓存化作废**（UART 现在是 guest RAM 里的缓存，没有寄存器可被重配置）。
     * 现在它只剩一处用途：引导程序发的"引导服务调用标记"（{@code 0x01}，见 hal.c 的
     * {@code oc_boot_invoke}），离线闸门靠数这个字节数确认"一次请求只兑一次"。</p>
     *
     * <p>⚠ 它仍然是**一处跨线程 MMIO 停摆点**（固件写它 ⇒ native 内核停下等宿主兑现），
     * 只是每次开机只发生一次、且不在 UART 链上 —— 归入第二阶段"其余设备停摆点"清单。</p>
     */
    public static final long DEBUG_BASE = 0x1000_3000L;

    /**
     * 固件的**心跳 / 运行时状态区**（{@code hal.h} 的 {@code HAL_HEARTBEAT_BASE}，2026-09-24 定论）。
     *
     * <p>为什么不放在 {@code REG_BASE} 的偏移 0/4：那两个偏移是组件桥的 {@code OC_REG_CALL} /
     * {@code OC_REG_STATUS}，心跳任务每 tick 写一次就会被桥当成"新的组件调用请求"
     * （真机上表现为引导服务被反复调用 259 次）。业务状态与 ABI 区必须**物理隔离**。</p>
     *
     * <p>⚠ 装配纪律：这是固件用的第 5 个 MMIO 区，**每个装配点都必须声明**（真机 + 每个离线装置）
     * —— 只声明 4 个的话，心跳任务第一次写就落到未映射地址：Java 内核直接 store access fault
     * 并停在 FreeRTOS 的默认异常处理器里（症状是"固件对输入再无反应"，2026-09-25 定位）。</p>
     */
    public static final long HEARTBEAT_BASE = 0x1000_4000L;

    /** 心跳区大小（4KB：够放 g_heartbeat / tick / 诊断计数等 10 个字并留余量） */
    public static final int HEARTBEAT_BYTES = 4 * 1024;

    /**
     * 显存窗口（VRAM）基址：**紧跟邮箱区之后**，而它本身**就是 guest RAM 的一段**。
     *
     * <p>这正对应用户定案「VRAM 与内存同占用」：固件写这里 = 写内存（零往返、零 MMIO）；
     * 宿主用 {@code CpuCore.readMemory} 取出来扫描上屏 —— **不需要额外的 MMIO 设备**。</p>
     *
     * <p>= {@code OcAbi.MAILBOX_BASE + OcAbi.MAILBOX_SPAN}（0x2002_0000 + 6144 = 0x2002_1800）。</p>
     */
    public static final long VRAM_BASE = 0x2002_1800L;

    /** 显存窗口预留大小（80×25 字符三平面 = 32 + 6000 = 6032 B；留 8KB 给多屏/真彩） */
    public static final int VRAM_BYTES = 8 * 1024;

    /**
     * **消息缓存区**（主线程 → 虚拟机）在 guest RAM 里的位置（用户 2026-09-26 定案）。
     *
     * <p>布局 = {@link HostMessageRing#HEADER_BYTES} 的头（状态 / 头尾指针 / 容量 / 丢弃计数）
     * + {@link HostMessageRing#MESSAGE_BYTES} 的环形数据区。主线程只管往这段内存里灌，
     * 固件只认"读这段内存" —— 与邮箱区（虚拟机 → 主线程）方向相反、职责对称，
     * 两者都在 guest RAM 里，零 MMIO 往返。</p>
     *
     * <p>= {@code VRAM_BASE + VRAM_BYTES}（紧跟显存窗口之后）。⚠ 它同样是 guest RAM 的一段
     * ⇒ 装配 RAM 时必须把它算进去（见 {@code CryptandOcArchitecture} 的 {@code ramBytes}），
     * 否则宿主写消息就落到未映射地址（native 内核直接 store access fault）。</p>
     */
    public static final long MSG_QUEUE_BASE = VRAM_BASE + VRAM_BYTES;

    /** 消息缓存区总大小（头 + 环形数据区），布局的唯一来源是 {@link HostMessageRing} */
    public static final int MSG_QUEUE_BYTES = HostMessageRing.HEADER_BYTES + HostMessageRing.MESSAGE_BYTES;

    /**
     * **UART 外设缓存**在 guest RAM 里的位置（用户 2026-09-27 定案：任何设备访问都不许停 CPU）。
     *
     * <p>这里放的是**虚拟机为芯片建立的一块 UART 硬件**的寄存器窗口（寄存器区 + DR/FIFO 数据窗口），
     * 但**它在 guest RAM 里、不是 MMIO 窗口**：芯片的每一次读写都是普通内存访问（native 内核零事务），
     * 器件每 tick 整块同步一次（见 {@link UartRegs} 与 {@code UartHardware}）。
     * 旧口径的 {@code UART_BASE = 0x1000_2000}（16550 寄存器组 MMIO）已按定案**整条删除** ——
     * 不留第二套逐字节 MMIO 路径。</p>
     *
     * <p>= {@code MSG_QUEUE_BASE + MSG_QUEUE_BYTES}（紧跟消息缓存区之后）。⚠ 它同样是 guest RAM
     * 的一段 ⇒ 装配 RAM 时必须把它算进去（见 {@link #GUEST_WINDOWS_BYTES}）。</p>
     */
    public static final long UART_CACHE_BASE = MSG_QUEUE_BASE + MSG_QUEUE_BYTES;

    /** UART 寄存器窗口总大小（寄存器区 + 发送窗口 + 接收窗口），单一来源是 {@link UartRegs} */
    public static final int UART_CACHE_BYTES = UartRegs.TOTAL_BYTES;

    /**
     * **RAM 尾部的宿主窗口总字节数**（邮箱 + 显存 + 消息缓存区 + UART 缓存）。
     *
     * <p>这是"装配 guest RAM 时要多加多少"的唯一来源：平台层算
     * {@code ramBytes = max(MIN_RAM_BYTES, 内存条字节数) + GUEST_WINDOWS_BYTES}，
     * 离线闸门用同一个常量断言"每个窗口都落在映射区里"。
     * ⚠ 少算任何一个 ⇒ 宿主写那个窗口就落到未映射地址（native 内核直接 store access fault）。</p>
     */
    public static final int GUEST_WINDOWS_BYTES = com.hdf.cryptand.soc.oc.OcAbi.MAILBOX_SPAN
            + VRAM_BYTES + MSG_QUEUE_BYTES + UART_CACHE_BYTES;

    /**
     * 消息缓存区**环形数据区**基址（= {@link #MSG_QUEUE_BASE} + 头部 32 字节）。
     *
     * <p>宿主发布待发窗口时按它写：从 {@code MSG_QUEUE_DATA_BASE + tail % capacity} 起写，
     * 写满环尾后回卷到 {@code MSG_QUEUE_DATA_BASE}（与 {@link HostMessageRing#window()} 的字节顺序一致）。
     * 固件侧对应 {@code HAL_MSG_BASE + 32}（hal.h 只声明基址与偏移，不再另算一个基数）。</p>
     */
    public static final long MSG_QUEUE_DATA_BASE = MSG_QUEUE_BASE + HostMessageRing.HEADER_BYTES;

    /**
     * 系统配置块：宿主上电前把 {@code config/cryptand/cryptand-os.cfg} 的内容写进 RAM 顶端 4KB，
     * 固件用 {@code hal_config_get/int} 按 {@code key=value} 直接读内存（不进设备、零 MMIO 成本）。
     *
     * <p>= 链接脚本的 {@code _config_block = ORIGIN(RAM) + LENGTH(RAM) - 4K}
     * （{@code cryptand_os.ld} 的 RAM 是 128KB ⇒ 0x2000_0000 + 128K − 4K）。
     * <b>改链接脚本的 RAM 尺寸就要同步改这里</b>（两边必须是同一个算式的结果）。</p>
     */
    public static final long CONFIG_BLOCK = 0x2001_F000L;

    /** 主 RAM 起始 */
    public static final long RAM_BASE = 0x2000_0000L;

    /** 定时器中断号：FreeRTOS 的 tick 走这里（RISC-V 的 MTIP） */
    public static final int IRQ_MTIP = 7;

    /**
     * **板级基准时钟**（Hz）：UART 波特率分频、定时器换算这类"板级外设"的时基。
     *
     * <p>⚠ 它<b>不是</b> CPU 档位的标称频率 —— 那是 {@link SocCpuTiers} 的事
     * （每档一个 MHz，来源唯一）。两者以前都叫"CPU_HZ"，改名的原因就是：
     * 同一个名字被当成"处理器频率"引用，于是"SOC 档显示 100 MHz、固件显示 20 MHz、
     * 芯片描述写 20 MHz"这种三方打架看起来像是同一个问题（2026-09-26 实测踩过）。
     * 现在分工明确：**处理器频率 = SocCpuTiers；板级时基 = 这里**。</p>
     */
    public static final long BOARD_CLOCK_HZ = 100_000_000L;

    /**
     * UART 波特率（bps）。
     *
     * <p>用户 2026-09-24："输出按照波特率，输出可以是 921600"。器件（虚拟机侧的
     * {@code UartHardware}）按它节流"往线路上吐字节" —— 收发装配是硬件的事，芯片侧只轮询状态位。
     * 921600 是嵌入式常见的高速率档，也是调试串口的实用上限。</p>
     */
    public static final int UART_BAUD = 921600;

    /** UART 每字节周期 = 板级基准时钟 / 波特率（100MHz / 921600 ≈ 108） */
    public static final int UART_CYCLES_PER_BYTE = (int) (BOARD_CLOCK_HZ / UART_BAUD);

    private OcBoardLayout() {
    }
}
