/*
 * ============================================================================
 * Cryptand OS · HAL 公共接口（hal.h，2026-09-17）
 *
 * 两个系统（仅 FreeRTOS / FreeRTOS+LVGL）共用这一层：
 *   · 早期调试串口（UART 16550，内核起来前也能用）
 *   · OC 组件调用（component_invoke，走 RegBank 的 MMIO ABI 寄存器组）
 *   · FreeRTOS 钩子（断言 / 堆失败 / 栈溢出 / tick）
 *
 * ⚠ 这里只有"我们的代码"；FreeRTOS 与 LVGL 的源码一个字都不改（用户要求）。
 * ============================================================================
 */
#ifndef CRYPTAND_HAL_H
#define CRYPTAND_HAL_H

#include <stdint.h>

/* ---------- 设备地址（与 SocBoard 装配 + cryptand_os.ld 一致）---------- */
#define HAL_REG_BASE    0x10000000u   /* RegBank：OC 组件桥 ABI（固件只允许通过 hal.c 的 OC_ABI 访问）*/
/* 心跳 / 运行时状态区（2026-09-24 定论）：**绝不能**再占用 HAL_REG_BASE 的偏移 0/4。
 * 事故：系统的心跳任务写 REG(0)=g_heartbeat（= OC_REG_CALL）、REG(1)=tick（= OC_REG_STATUS），
 * 于是每一 tick 的心跳都被组件桥当成一次新的组件调用请求，而 COMPONENT/METHOD 是 Boot 的残留值
 * ⇒ 每次都命中引导服务 ⇒ 引导读盘服务被反复调用 300 次（真机 259 次；当时那个方法叫 loadProgram，
 *    现已是 BOOT_METHOD_READ_FILE，见下面的引导服务段）。
 * 教训：业务状态与 ABI 区必须物理隔离，靠"约定不越界"是守不住的。*/
#define HAL_HEARTBEAT_BASE  0x10004000u
#define HAL_TIMER_BASE  0x10001000u   /* CLINT 风格定时器（FreeRTOS tick）*/

/* ==================== 串口：**虚拟机建立的一块 UART 硬件**（2026-09-27 定案）====================
 *
 * 层级（用户定案）：**虚拟机 = 硬件层（FPGA 式 fabric），只对芯片建立模拟外设组件；芯片只执行 + 碰寄存器**。
 *   · 这块 UART 硬件由虚拟机侧持有：寄存器文件 + 硬件缓存（默认 1 字节 DR；装载通用 FIFO 模块才 >1 字节）
 *     + 收发装配（波特率节流 / 移位寄存器）+ 状态位（TXE TC RXNE OVR）；
 *   · 宿主只从**外部世界**那一侧供料（世界侧字节交给器件、器件发出的字节送到控制台/日志），
 *     芯片侧**看不到宿主**，也没有任何"叫宿主 pump 一下"的入口；
 *   · 固件只做三件事：**写 DR / 读 DR / 轮询状态位**。
 *
 * ⇒ 旧的 16550 **MMIO 寄存器组**（HAL_UART_BASE = 0x1000_2000，每字符读 LSR + 写 THR
 *   = native 内核下每字符 1~2 次跨线程事务、每次都停 CPU 等宿主）**已整条删除**。
 *   现在芯片读写的是映射进 guest RAM 的**寄存器窗口**（普通内存，零 MMIO 事务）。
 *
 * 布局的**唯一来源**是 common 的 com.hdf.cryptand.soc.board.UartRegs；
 * 这里只是 C 侧镜像（数值由离线闸门 :common:runUartCacheTest 逐项对拍，改那边就要改这里）：
 *
 *   +0x00 magic      u32  'CUAR'（织物写；读到它才知道窗口已就绪）
 *   +0x04 sr         u32  状态位 HAL_UART_SR_*（织物写）
 *   +0x08 tx_taken   u32  单调：织物已从窗口消费的发送字节数
 *   +0x0C rx_given   u32  单调：织物已投进窗口的接收字节数
 *   +0x10 ovr        u32  单调：溢出（丢掉的）字节数 —— **绝不静默**
 *   +0x14 tx_slots   u32  当前生效的发送缓冲字节数（1 = 经典 DR；FIFO 使能 = 模块深度）
 *   +0x18 rx_level   u32  当前接收占用
 *   +0x1C tx_total   u32  单调：已交给线路（世界侧出口）的字节数
 *   +0x20 rx_total   u32  单调：世界侧已送进器件的字节数
 *   +0x24 reserved   u32
 *   +0x28 cr1        u32  控制位 HAL_UART_CR1_*（**固件独占**：FIFO 使能 / 阈值 / 清缓冲）
 *   +0x2C tx_push    u32  单调：固件写 DR 的次数（**固件独占**；写事件靠它发布）
 *   +0x30 rx_pop     u32  单调：固件读 DR 的次数（**固件独占**）
 *   +0x34 ovr_ack    u32  单调：软件已确认的溢出次数（**固件独占**；等价"读 SR+DR 清 ORE"）
 *   +0x38 reserved   u32（织物）
 *   +0x3C reserved   u32（织物）
 *   +0x40 tx_buf[HAL_UART_TX_BYTES]  发送数据窗口（固件写、织物读）
 *   +0x40+RX rx_buf[HAL_UART_RX_BYTES] 接收数据窗口（织物写、固件读）
 *
 * ⚠ 数据窗口是**硬件缓存的映射视图**（芯片只能碰普通内存，所以 DR/FIFO 数据口必须映射成内存槽位）；
 *   容量仍是芯片设计值 = 装载的 FIFO 模块深度，**软件改不了**（只通过 CR1.FIFOEN 决定使能/旁路）。
 * ⚠ 它必须落在 guest RAM 里（宿主装配 RAM 时已含这一段）——落在映射区外的话，每次 lw/sw
 *   又会变回一次跨线程 MMIO 事务，"不停 CPU"就没兑现。
 * ⚠ 固件**只写** cr1 / tx_push / rx_pop / ovr_ack 四个字段，其余只读 ⇒ 两侧各写各的，不需要锁。 */
#define HAL_UART_CACHE_BASE    0x20024820u  /* = 消息缓存区(0x20023800) + 4128，紧跟其后 */
#define HAL_UART_CACHE_BYTES   576u         /* 64 寄存器区 + 256 发送窗口 + 256 接收窗口 */
#define HAL_UART_MAGIC         0x52415543u  /* 'CUAR'（小端写出去就是 C U A R） */
#define HAL_UART_TX_BYTES      256u         /* 本芯片装载的通用 FIFO 模块深度（2 的幂） */
#define HAL_UART_RX_BYTES      256u

#define HAL_UART_OFF_MAGIC      0x00u
#define HAL_UART_OFF_SR         0x04u
#define HAL_UART_OFF_TX_TAKEN   0x08u
#define HAL_UART_OFF_RX_GIVEN   0x0Cu
#define HAL_UART_OFF_OVR        0x10u
#define HAL_UART_OFF_TX_SLOTS   0x14u
#define HAL_UART_OFF_RX_LEVEL   0x18u
#define HAL_UART_OFF_TX_TOTAL   0x1Cu
#define HAL_UART_OFF_RX_TOTAL   0x20u
#define HAL_UART_OFF_CR1        0x28u
#define HAL_UART_OFF_TX_PUSH    0x2Cu
#define HAL_UART_OFF_RX_POP     0x30u
#define HAL_UART_OFF_OVR_ACK    0x34u
#define HAL_UART_OFF_TX_BUF     0x40u
#define HAL_UART_OFF_RX_BUF     0x140u  /* = 0x40 + TX_BYTES(256) */

/* 状态位（照真实芯片的命名与语义） */
#define HAL_UART_SR_TXE        0x0001u  /* 发送数据寄存器空（有空位可写；写 DR 之前轮询它） */
#define HAL_UART_SR_TC         0x0002u  /* 发送完成（硬件发送管线已空） */
#define HAL_UART_SR_RXNE       0x0004u  /* 接收数据可读 */
#define HAL_UART_SR_OVR        0x0008u  /* **溢出**：软件确认之前一直为 1（不静默丢） */
#define HAL_UART_SR_TXFE       0x0010u  /* 发送缓冲空 */
#define HAL_UART_SR_RXFF       0x0020u  /* 接收缓冲满 */
#define HAL_UART_SR_RXFT       0x0040u  /* 接收占用达到阈值 */
#define HAL_UART_SR_FIFOEN     0x0080u  /* 当前生效的 FIFO 使能 */
#define HAL_UART_SR_FIFOMOD    0x0100u  /* 芯片**装载了** FIFO 模块（设计值） */

/* 控制位（软件写；照真实芯片：FIFO 要软件使能 + 阈值位） */
#define HAL_UART_CR1_FIFOEN    0x0001u
#define HAL_UART_CR1_RXFTH_1_2 0x0002u  /* 接收阈值 = 1/2（两位一组的第二个编码） */
#define HAL_UART_CR1_RXFLUSH   0x0020u
#define HAL_UART_CR1_TXFLUSH   0x0040u

/* ---------- 串口 ---------- */
/* ===== 日志（2026-09-24，参考 LVGL 的 LV_LOG_* 风格）=====
 *
 * 用户定案："#define 开启 debug 时 LOG 使用 printf 生效，关闭 debug 时 #define LOG 后面为空"。
 *   CRYPTAND_LOG_LEVEL: 0=OFF 1=ERROR 2=WARN 3=INFO 4=DEBUG
 * 关闭（0）时 LOG 展开为空 —— 与 LVGL 的 LV_LOG_USER 一致：零开销、零引用、不进固件体积。
 * 输出走 hal_uart_printf（自带最简格式化，不依赖 newlib 的 printf）。
 */
#ifndef CRYPTAND_LOG_LEVEL
#  define CRYPTAND_LOG_LEVEL 3
#endif

#if CRYPTAND_LOG_LEVEL >= 4
#  define LOG_DEBUG(...) hal_uart_printf(__VA_ARGS__)
#else
#  define LOG_DEBUG(...) ((void)0)
#endif

#if CRYPTAND_LOG_LEVEL >= 3
#  define LOG(...) hal_uart_printf(__VA_ARGS__)
#else
#  define LOG(...) ((void)0)
#endif

#if CRYPTAND_LOG_LEVEL >= 1
#  define LOG_ERROR(...) hal_uart_printf(__VA_ARGS__)
#else
#  define LOG_ERROR(...) ((void)0)
#endif

int  hal_uart_printf(const char *fmt, ...);

/**
 * UART 硬件初始化：校验寄存器窗口的魔数，把本机自己的两个计数（tx_push / rx_pop）从窗口里读出来，
 * 并在芯片装载了通用 FIFO 模块时**照真实芯片的做法软件使能它**（CR1.FIFOEN）+ 设接收阈值。
 *
 * @return 0 = 就绪；-1 = 魔数不对（织物还没发布窗口）
 *
 * ⚠ 波特率**不由固件写寄存器**：它是板级时基的单一来源（宿主侧 {@code OcBoardLayout.UART_BAUD}），
 *   器件按它节流。固件这里没有分频寄存器可配 —— 这正是"硬件缓存取代 MMIO 寄存器组"的直接结果。
 * ⚠ 没调用它也安全：{@code hal_uart_putc} 会惰性做同一次校验（三个系统里只有 Boot/mini OS
 *   显式调用），但显式调用能把结果打进日志。
 */
int  hal_uart_hw_init(void);

/** 器件是否就绪（惰性校验过一次的结果） */
int  hal_uart_ready(void);

/** 取一个收到的字节（读 DR）；-1 = 此刻没有（**绝不阻塞**：等不等是调用方的事） */
int  hal_uart_getc(void);

/** 有字节可读（= 状态位 HAL_UART_SR_RXNE） */
int  hal_uart_rx_ready(void);

/** 软件确认溢出（等价"读 SR + 读 DR 清 ORE"）：确认后 SR.OVR 才归零 */
void hal_uart_ack_ovr(void);

/** 器件累计溢出（丢掉）的字节数 —— 0 = 从未丢过；非 0 必须让调用方看到 */
uint32_t hal_uart_ovr(void);

void hal_uart_putc(char c);
void hal_uart_puts(const char *s);
void hal_uart_putu(uint32_t v);
void hal_uart_puthex(uint32_t v);

/**
 * 启动调度器**之前**必调：把 FreeRTOS 的陷阱向量装进 {@code mtvec}。
 *
 * <p>⚠ 必须由应用做的步骤：FreeRTOS 202411 的 RISC-V port 只导出
 * {@code freertos_risc_v_trap_handler} **符号**，自己**不写 mtvec**（官方 demo 由 BSP 安装）。
 * 忘了它会怎样：第一次 {@code portYIELD()}（ecall）或第一个定时器中断就因"没有 handler"停机
 * —— 实测跑完 80 万条指令后死在 {@code ecall from M}。</p>
 */
void hal_install_trap_vector(void);

/* ==================== 系统配置（上电从配置文件读，改文件 + 重启机器即生效） ====================
 *
 * 机制：宿主（Java 侧）在上电前把 config/cryptand/cryptand-os.cfg 的内容写进
 * **guest RAM 顶端保留的 4KB 配置块**（链接脚本 _config_block，栈顶已让出这一段）。
 * 固件在这里按 key=value 直接读内存 ⇒ 不进设备、不进 ROM、零 MMIO 成本。
 *
 * 格式（宿主机写文件，玩家改的就是它）：
 *
 *     CFG1                      ← 魔数（块首，缺了就视为"无配置"）
 *     # 注释行以 # 开头
 *     shell.prompt=Cryptand>
 *     lvgl.refr_ms=50
 *     heartbeat.ms=100
 */

/** 配置块魔数（宿主写入时放在块首） */
#define HAL_CONFIG_MAGIC "CFG1"

/** 初始化配置访问（校验魔数；无配置时所有键走默认值） */
void hal_config_init(void);

/** 是否读到了有效配置块 */
int hal_config_present(void);

/** 取字符串值（找不到返回 def，绝不返回 NULL） */
const char *hal_config_get(const char *key, const char *def);

/** 取整数值（找不到/非法返回 def） */
int hal_config_int(const char *key, int def);

/** 取布尔（1/true/on/yes 为真；0/false/off/no 为假） */
int hal_config_bool(const char *key, int def);

/* ---------- OC 组件桥（OcAbi 的 C 侧）---------- */
#define OC_REG_CALL         0x00u
#define OC_REG_STATUS       0x04u
#define OC_REG_COMPONENT    0x08u
#define OC_REG_METHOD       0x0Cu
#define OC_REG_ARG_COUNT    0x10u
#define OC_REG_ARG0         0x20u
#define OC_REG_RESULT_COUNT 0x60u
#define OC_REG_RESULT0      0x70u
#define OC_REG_ERROR        0xB0u
#define OC_REG_LIST_INDEX   0xB4u
#define OC_REG_LIST_NAME    0xC0u
#define OC_REG_LIST_COUNT   0xC4u
#define OC_REG_BUF_ADDR     0xC8u
#define OC_REG_BUF_LEN      0xCCu
/* 宿主写：本次 I/O **实际**用掉的字节数（0 = 本次没用缓冲区）。
 * ⚠ 只服务**寄存器通道**（引导服务那套）。走邮箱通道的文件操作不用它 ——
 *   邮箱的 MB_RESULT0 已经在写回路径上（FS_READ: 实读字节数 / EOF=-1；FS_LIST: 写入字节数）。 */
#define OC_REG_BUF_USED     0xD0u

#define OC_STATUS_IDLE      0u
#define OC_STATUS_BUSY      1u
#define OC_STATUS_DONE      2u
#define OC_STATUS_ERROR     3u

/* 错误码（与 OcAbi.ERR_* 一一对应；STATUS=ERROR 时读 OC_REG_ERROR / 邮箱的 ERROR 槽）
 * ⚠ 7..14 是文件系统段：语义照 OC 原版（抛异常 vs 返回 false/nil 必须分开，否则固件
 *   分不清"该重试"还是"该报错"，见 .ai_cache/cryptand-fs-design.md §3.3.3）。 */
#define OC_ERR_NONE             0u
#define OC_ERR_NO_BUS           1u
#define OC_ERR_UNKNOWN_COMPONENT 2u
#define OC_ERR_UNKNOWN_METHOD   3u
#define OC_ERR_BAD_ARGS         4u
#define OC_ERR_COMPONENT_FAILED 5u
#define OC_ERR_TIMEOUT          6u
#define OC_ERR_NOT_FOUND        7u   /* 不存在 / 模式不允许 / 目标是目录 */
#define OC_ERR_BAD_HANDLE       8u   /* 句柄无效或已关闭 */
#define OC_ERR_NO_SPACE         9u   /* 配额用尽 */
#define OC_ERR_READ_ONLY       10u   /* 只读盘 */
#define OC_ERR_INVALID_PATH    11u   /* 路径非法 / 越界 */
#define OC_ERR_TOO_MANY_HANDLES 12u  /* 句柄超限 */
#define OC_ERR_BAD_MODE        13u   /* 模式/参数非法（含 seek 负数） */
#define OC_ERR_BUF_TOO_SMALL   14u   /* 仅 FS_LIST：缓冲区装不下，result0 = 所需字节数 */

/* 组件句柄（宿主按组件表下标预注册） */
/* ⚠ 引导服务的特殊句柄：**不是组件表下标**，核心在泵里优先识别它（与 OcAbi.HANDLE_BOOT 同值）。
 *   原来只在 cryptand-boot/main.c 里定义，hal.c 也要用它分流（引导走同步寄存器路径），故提到这里。 */
#define OC_HANDLE_BOOT      0x0Fu
/* ===== 引导服务的方法号：BIOS 的**读盘服务**（= 现实 INT 13h）=====
 * 2026-09-27 定案（用户："EEPROM 属于外部 BIOS 部分"、"BIOS 类似现实做引导，负责从盘中读取文件
 * 加载到虚拟机内运行"）：BIOS 提供读盘服务，引导程序**请求**它把引导盘上的某个文件读进内存。
 *
 *   参数：arg0 = 载入地址（guest 物理地址，= INT 13h 的 ES:BX）
 *         缓冲区 = NUL 结尾的**路径**（要哪个文件；本固件传 "/boot/system.bin"）
 *   返回：result0 = 实读字节数（0 = 盘上没有这个文件）
 *          result1 = 实际载入地址
 *
 * ⚠ 这里**没有"哪块盘"参数**：读的就是 BIOS 当前引导的那块盘（INT 19h 已把 DL 设好）。
 * ⚠ 它**不是**"宿主替我把系统取来"—— 旧的 loadSystem（无参、路径写死在宿主）已删除，
 *   因为"取哪个文件"是引导程序的事，不是 BIOS 的事。 */
#define BOOT_METHOD_READ_FILE       0u

/* ⚠ PE 服务（Cryptand OS PE 的装机能力，2026-09-26）：与引导服务同样是**特殊句柄** ——
 *   它不是 OC 机箱里插的组件，而是宿主提供的"分区 / 格式化 / 安装"能力。
 *   核心在句柄映射里把它翻成组件名 "pe"（见 OcArchitectureCore.addressOf/componentOf）。 */
#define OC_HANDLE_PE        0x10u

/* PE 方法号（与 OcAbi.PE_METHOD_* 一一对应）
 * 缓冲区方向：入方向一律 NUL 分隔串（address\0fsName\0label / address\0programId）；
 *             出方向把**人类可读的结果文本**写回固件给的缓冲区（与 fs 读同一条出方向通路）。 */
#define OC_PE_TARGETS       0u   /* 列出这台机器上的目标盘 */
#define OC_PE_FORMAT        1u   /* args[0]=分区 KB（0=整盘）；buf=address\0fsName\0label */
#define OC_PE_INSTALL       2u   /* buf=address\0programId */
#define OC_PE_INSTALL_ALL   3u   /* 一键：格式化 + 安装 + 回读校验 */
#define OC_PE_INFO          4u   /* buf=address */

/* 句柄空间（与 OcAbi 一致，用户 2026-09-18 定案）：
 *   0..N-1  OC 组件表下标 —— **盘（filesystem）与 GPU/屏幕/键盘一样在这里**（"硬盘软盘走 OC"）
 *   0x0F    引导服务（特殊句柄）
 *   0x10    PE 服务（特殊句柄，装机环境用）
 * 芯片内部模块（TIMER/UART/GPIO/PWM/ADC）不经过句柄：固件直接读写板级 MMIO 寄存器（自管理） */

#define OC_HANDLE_GPU       0u
#define OC_HANDLE_SCREEN    1u
#define OC_HANDLE_KEYBOARD  2u

/* GPU 方法 id（宿主的方法表必须与此一致） */
#define OC_GPU_SET          0u
#define OC_GPU_FILL         1u
#define OC_GPU_GET_RES      2u
#define OC_GPU_SET_FG       3u
#define OC_GPU_SET_BG       4u
#define OC_GPU_BLIT         5u   /* 整块字符：args=(x,y,w,h) + 缓冲区 w*h 字节；一次调用画一整屏 */

/* ==================== 文件系统方法 id（16..31，2026-09-18）====================
 * 与 OcAbi.FS_* 一一对应；语义照 OC 的 li.cil.oc.api.fs.FileSystem（见
 * .ai_cache/cryptand-fs-design.md §3.3）。6..15 保留给 GPU 扩展 / screen / keyboard。
 *
 * 缓冲区方向（由方法 id 决定，两侧共用这张表）：
 *   入（固件→宿主，宿主读 guest 内存）：OPEN / WRITE / DELETE / RENAME / MKDIR /
 *        EXISTS / SIZE / IS_DIR / LAST_MODIFIED / STAT
 *   出（宿主→guest，宿主写 guest 内存）：READ / LIST
 *        "实际写了多少"用 result0 表达：READ → 实读字节数（-1 = EOF）；LIST → 写入字节数（-1 = 不是目录）
 *   ⚠ 缓冲区一律**由固件自带**（OC_MB_BUF_ADDR = 地址、OC_MB_BUF_LEN = 容量）：
 *     固件没有 malloc 大块的能力，ABI 就不能设计成"宿主返回一个新数组"。 */
#define OC_FS_OPEN          16u
#define OC_FS_READ          17u
#define OC_FS_WRITE         18u
#define OC_FS_SEEK          19u
#define OC_FS_CLOSE         20u
#define OC_FS_LIST          21u
#define OC_FS_DELETE        22u
#define OC_FS_RENAME        23u
#define OC_FS_MKDIR         24u
#define OC_FS_EXISTS        25u
#define OC_FS_SIZE          26u
#define OC_FS_IS_DIR        27u
#define OC_FS_LAST_MODIFIED 28u
#define OC_FS_SPACE         29u
#define OC_FS_IS_READONLY   30u
#define OC_FS_STAT          31u   /* 非 OC 原生：一次拿全 exists/isDir/size/mtime/readOnly */

/* FS_OPEN 的模式（对应 OC 的 r/w/a/r+/w+/a+） */
#define OC_FS_MODE_R        0u
#define OC_FS_MODE_W        1u
#define OC_FS_MODE_A        2u
#define OC_FS_MODE_RPLUS    3u
#define OC_FS_MODE_WPLUS    4u
#define OC_FS_MODE_APLUS    5u

/* FS_SEEK 的 whence */
#define OC_FS_SEEK_SET      0u
#define OC_FS_SEEK_CUR      1u
#define OC_FS_SEEK_END      2u

/* ==================== 盘（OC filesystem 组件；用户定案：盘走 OC）====================
 *
 * 盘是 OC **组件表**里的一项，下标运行时才知道 —— 宿主在开机时把它写进启动参数块
 * （与 cryptand-os.cfg 同一个块），固件用 hal_config_int("disk.fs_handle", -1) 读一次即可，
 * 于是固件**不需要"枚举组件"的能力**。
 *
 * 返回值约定：>= 0 为正常结果（含义见各函数）；< 0 为 **ABI 错误码的负值**
 * （如 -OC_ERR_NOT_FOUND、-OC_ERR_NO_SPACE），与 C 的 errno 风格一致。
 *
 * ⚠ fs_read 的 buf 是**固件自带的缓冲区**（guest 物理地址）：数据由宿主写进来。
 * ⚠ fs_list 的 buf 起始要放 "path\0"，cap 是**容量**（结果原地覆盖路径）——
 *   这是 ABI 里唯一"入方向与出方向共用同一块缓冲区"的方法。 */

/** 本次开机的盘句柄（OC 组件表下标）；-1 = 没插盘 */
int  fs_disk_handle(void);

int  fs_open(const char *path, unsigned mode);       /* → 文件句柄(>0)；<0 错误码 */
int  fs_read(int handle, void *buf, unsigned len);   /* → 实读字节数；-1 = EOF；<0 错误码 */
int  fs_write(int handle, const void *data, unsigned len);  /* → 1/0；<0 错误码 */
int  fs_seek(int handle, unsigned whence, int offset);      /* → 新位置；<0 错误码 */
int  fs_close(int handle);                           /* → 0；<0 错误码 */
int  fs_list(const char *path, char *buf, unsigned cap);    /* → 写入字节数；-1 = 非目录 */
int  fs_size(const char *path);                      /* → 字节数；<0 错误码 */
int  fs_exists(const char *path);                    /* → 1/0；<0 错误码 */
int  fs_is_dir(const char *path);                    /* → 1/0；<0 错误码 */
int  fs_mkdir(const char *path);                     /* → 1/0（递归建父目录）；<0 错误码 */
int  fs_delete(const char *path);                    /* → 1/0；<0 错误码 */
int  fs_space_total(void);                           /* → 总配额字节；<0 错误码 */
int  fs_space_used(void);                            /* → 已用字节；<0 错误码 */

/* ==================== 外部设备邮箱区（2026-09-17 定案）====================
 *
 * 用户定案：「寄存器仅限自己的内容，外部设备全部靠类似 linux 或者映射为一段内存操作，
 * 然后写入后可以由程序决定是否等待，或者通过状态机在执行其他任务时看一眼，
 * 所有外设当作耗时操作」。
 *
 * 所以上面那些 OC_REG_* 寄存器**只留给内部外设与引导服务**；一切 OC 组件
 * （gpu/screen/filesystem/…）改走这块**内存邮箱** —— 它的地址落在主 RAM 之后
 * （0x20020000 = 0x20000000 + 128K），宿主侧按 OcAbi.MAILBOX_SPAN 多分配了这段。
 *
 * ⚠ 为什么必须落在 RAM 里：native 内核**自持 RAM**，只有它范围内的地址才是普通内存访问；
 *   范围外一律变 MMIO 事务（一次完整往返 ≈0.44ms）。旧协议自旋读 STATUS 200 万次把
 *   100MHz 拖成 0.01MHz，就是这么来的。现在轮询的是 RAM ⇒ 只烧自己的 CPU 周期。
 */
#define OC_MAILBOX_BASE     0x20020000u
#define OC_MB_CHANNELS      64u
/* 通道跨度：**96（0x60）**，不是 64。本通道字段排到 OC_MB_BUF_LEN(0x5C)+4 = 0x60；
 * 若按 64 排，通道 i 的 BUF_ADDR/LEN 会落在通道 i+1 的 ARG1/ARG2 上（单通道串行时看不出，
 * 多通道并发就互相踩）。与 OcAbi.MAILBOX_STRIDE 必须同值。 */
#define OC_MB_STRIDE        96u

/* 通道内字段偏移（与 OcAbi.MB_* 一一对应） */
#define OC_MB_STATE         0x00u
#define OC_MB_SEQ           0x04u
#define OC_MB_COMPONENT     0x08u
#define OC_MB_METHOD        0x0Cu
#define OC_MB_ARGC          0x10u
#define OC_MB_ARG0          0x14u   /* 8 个标量参数：0x14..0x30 */
#define OC_MB_RESULT_COUNT  0x34u
#define OC_MB_RESULT0       0x38u   /* 8 个返回值：0x38..0x54 */
#define OC_MB_BUF_ADDR      0x58u
#define OC_MB_BUF_LEN       0x5Cu

/* 通道状态机：固件写 REQUEST，宿主写 BUSY/DONE/ERROR */
#define OC_MB_IDLE          0u
#define OC_MB_REQUEST       1u
#define OC_MB_BUSY          2u
#define OC_MB_DONE          3u
#define OC_MB_ERROR         4u

#define OC_MB(base, off)    (*(volatile uint32_t *)((uintptr_t)(base) + (off)))
#define OC_MB_CH(idx)       ((uintptr_t)OC_MAILBOX_BASE + (uintptr_t)(idx) * OC_MB_STRIDE)

/* 外部设备调用的两种形态（见 hal.c）
 *
 * 用户定案（2026-09-17）：「**寄存器仅操作，写入后不回读**，这样的话可以让程序决定是否等待
 * 再读取」「参考现实寄存器操作，**要么写入要么读取**」「并且还有额外的位表示是否写入以及
 * 是否返回等」「uart 也是会有缓存之类的」。
 *
 * 所以对外设的调用被拆成**两条互不相干的操作**（就像真实硬件）：
 *
 *   ① 写：component_post(..., flags) —— 参数 + 命令写下去就返回，**不回读**。
 *      flags 里的位表达意图（要不要结果、是写还是读、……），对应"额外的位表示是否写入以及是否返回"。
 *   ② 读：component_status() / component_result() —— 由**程序自己决定**何时来读：
 *      · 立刻阻塞等待（while (component_status(ch)==0) {}）；
 *      · 或者丢着不管，先去干别的，回头再看一眼（状态机风格）；
 *      · 或者干脆不看（fire-and-forget，只要写入生效就够了）。
 *
 * 这就是"所有外设当作耗时操作"的落点：写下去 = 发出请求，回不回来、什么时候回来看，是程序的事。
 */
#define OC_CMD_FLAG_WRITE   0x0001u  /* 本次是"写设备"（对 UART/磁盘/屏幕等） */
#define OC_CMD_FLAG_READ    0x0002u  /* 本次是"读设备" */
#define OC_CMD_FLAG_WANT_RESULT 0x0004u /* 需要返回结果；不置位 = fire-and-forget，宿主可省掉结果回写 */
#define OC_CMD_FLAG_NO_WAIT 0x0008u  /* 提示宿主：调用方不会等，可以延后处理（可用于批量刷屏） */

/* 状态寄存器里的位（读路径；对应"额外的位表示是否写入以及是否返回"） */
#define OC_ST_BIT_READY     0x0001u  /* 结果已就绪 */
#define OC_ST_BIT_ERROR     0x0002u  /* 出错 */
#define OC_ST_BIT_BUSY      0x0004u  /* 宿主正在处理 */
#define OC_ST_BIT_DROPPED   0x0008u  /* 被丢弃（例如 fire-and-forget 且队列满） */

/* ① 写：投递即返回（不回读）。返回通道号 >=0，失败 -1。
 *    wantResult=0 表示调用方只要写入生效、不打算读结果。 */
int  component_post(uint32_t handle, uint32_t method, uint32_t argc, const uint32_t *args,
                    const void *buf, uint32_t bufLen, uint32_t flags);

/* ② 读：查状态（位图，见 OC_ST_BIT_*）；1=就绪 0=未就绪 -1=错误 */
int  component_status(int channel);
/* ⚠ 不能叫 component_result —— 那个老名字（单参数，读"上一次调用的第 i 个返回值"）
 *   已被既有代码占用（下方 component_result(uint32_t)），重名会 conflicting types。 */
int  component_read_result(int channel, uint32_t index);
void component_release(int channel);

/* 便利封装：投递 + 同步等待（等价于"写完立刻回读"）。
 * ⚠ 用户定案里**不推荐**把它当默认用法 —— 只有确实需要结果时才用。 */
int  component_invoke(uint32_t handle, uint32_t method, uint32_t argc, const uint32_t *args,
                      const void *buf, uint32_t bufLen);

/** 一次组件调用（一对一问答）；返回结果个数，-1 = 失败/超时（宿主未接桥） */
int component_invoke(uint32_t handle, uint32_t method, uint32_t argc, const uint32_t *args,
                     const void *buf, uint32_t bufLen);

/** 取上次调用的第 index 个结果 */
int component_result(uint32_t index);

/** 便捷封装：等价 OC Lua 的 gpu.set / gpu.fill */
/**
 * 查询屏幕分辨率（OC 的 {@code gpu.getResolution}）。
 *
 * @return 成功返回 {@code (宽 << 16) | 高}；没有屏幕 / 桥不在线返回 0
 */
uint32_t gpu_get_resolution(void);

int gpu_set(uint32_t x, uint32_t y, const char *text);
int gpu_fill(uint32_t x, uint32_t y, uint32_t w, uint32_t h, char ch);

/**
 * 整块字符上屏（LVGL 之类图形栈用）：{@code cells} 是 w×h 字节的字符码。
 * 缓冲区在 guest 内存里，宿主读它并兑现成若干 {@code gpu.set} ——**一次 ABI 往返画一整屏**，
 * 否则每格一次往返（2000 格）在 MC tick 预算里根本跑不动。
 */
int gpu_blit(uint32_t x, uint32_t y, uint32_t w, uint32_t h, const uint8_t *cells);

/* ==================== 消息缓存区（主线程 → 虚拟机，2026-09-27）====================
 *
 * 用户 2026-09-26 定案："虚拟机和主线程定义一个缓存区，用于消息等接收"。
 * 键盘 / 串口 / 宿主通知 / 外部事件全部由宿主写进这段 **guest RAM**，固件只认"读这段内存"：
 * 与显存窗口一样零 MMIO 往返（内存访问，不是设备事务）。
 *
 * 布局的**唯一来源**是 common 的 com.hdf.cryptand.soc.board.HostMessageRing；
 * 这里只是 C 侧镜像（数值由离线闸门 :common:runMsgQueueTest 逐项对拍，改那边就要改这里）：
 *
 *   +0x00 magic    u32  'CMSG'（宿主写；读到它才知道窗口已就绪）
 *   +0x04 head     u32  **单调写计数**（宿主独占；环形下标 = head % capacity）
 *   +0x08 tail     u32  **单调读计数**（**只由固件写**：消费一条就推进 len+2）
 *   +0x0C used     u32  待发字节数 = head - tail
 *   +0x10 capacity u32  环形数据区容量（按它算下标，不硬编码）
 *   +0x14 dropped  u32  因满被丢弃的消息条数（宿主写；"看丢弃"读它）
 *   +0x18 state    u32  状态位 READY / FULL / DROPPED
 *   +0x1C seq      u32  累计入队消息条数（单调；"有没有新消息"看它）
 *   +0x20 起 = capacity 字节环形数据区（帧 = kind 1B + len 1B + 数据 len B）
 *
 * ⚠ head/tail 是**单调计数**而不是环形下标：这样 head == tail 当且仅当队列为空，
 *   "刚好填满"（used == capacity）不会被读成空 —— 若用下标，满与空同为 head == tail，
 *   固件在大塞满时会把满队列当空队列，从此再不消费（宿主也已写不进去）。
 * ⚠ 固件**只写 tail**：其它头字段归宿主，两边各写各的字段 ⇒ 不需要锁。 */
#define HAL_MSG_BASE        0x20023800u   /* = 显存窗口(0x20021800) + 8KB，紧跟其后 */
#define HAL_MSG_BYTES       4128u         /* 32 头 + 4096 环形数据区（= 宿主的 MSG_QUEUE_BYTES） */
#define HAL_MSG_MAGIC       0x47534D43u   /* 'CMSG'（小端写出去就是 C M S G） */
#define HAL_MSG_DATA_OFF    0x20u         /* 环形数据区在窗口内的偏移 */

#define HAL_MSG_OFF_MAGIC     0x00u
#define HAL_MSG_OFF_HEAD      0x04u
#define HAL_MSG_OFF_TAIL      0x08u
#define HAL_MSG_OFF_USED      0x0Cu
#define HAL_MSG_OFF_CAPACITY  0x10u
#define HAL_MSG_OFF_DROPPED   0x14u
#define HAL_MSG_OFF_STATE     0x18u
#define HAL_MSG_OFF_SEQ       0x1Cu
#define HAL_MSG_HEADER_BYTES  0x20u

/* 数据区容量的编译期上限（= 宿主的 MESSAGE_BYTES）；**实际容量以头里的 capacity 为准** */
#define HAL_MSG_CAPACITY_MAX  4096u

#define HAL_MSG_STATE_READY   0x1u
#define HAL_MSG_STATE_FULL    0x2u
#define HAL_MSG_STATE_DROPPED 0x4u

/* 消息种类（与 HostMessageRing.KIND_* 一一对应） */
#define HAL_MSG_KIND_SERIAL   1u
#define HAL_MSG_KIND_KEY      2u
#define HAL_MSG_KIND_EVENT    3u
#define HAL_MSG_KIND_NOTICE   4u

/* ---- 取消息（"取一条 / 取空 / 看容量 / 看丢弃"）----
 * hal_msg_take：取**一条**整帧
 *   > 0  = 取到 len 字节（payload 已拷进 buf；kind 有值时一并回填）
 *   = 0  = 空（没有消息）
 *   = -1 = 窗口没就绪（魔数不对）或头部不自洽 —— **明确报错，不猜**
 *   = -2 = 这条 payload 装不进 buf：**整条已经消费掉**（绝不半读），调用方自己记一笔
 * hal_msg_take_all：把队列里**整帧**地连续拷进 buf（装不下就停，剩下的留在队列），返回拷贝字节数 */
int hal_msg_take(uint32_t *kind, void *buf, uint32_t cap);
uint32_t hal_msg_take_all(void *buf, uint32_t cap);

int      hal_msg_ready(void);       /* 1 = 魔数有效（宿主已发布窗口） */
uint32_t hal_msg_used(void);        /* 待发字节数（0 = 空） */
uint32_t hal_msg_capacity(void);    /* 环形数据区容量（0 = 没就绪） */
uint32_t hal_msg_dropped(void);     /* 因满被丢弃的消息条数（0 = 从未丢过） */
uint32_t hal_msg_head(void);        /* 单调写计数（宿主已入队到哪） */
uint32_t hal_msg_tail(void);        /* 单调读计数（本机已消费到哪） */
uint32_t hal_msg_seq(void);         /* 累计入队消息条数（单调） */
uint32_t hal_msg_state(void);       /* 状态位（HAL_MSG_STATE_*） */
uint32_t hal_msg_taken(void);       /* 本机自开机起取到的消息条数（固件侧计数，诊断用） */

#endif /* CRYPTAND_HAL_H */
