/*
 * ============================================================================
 * Cryptand OS · HAL 实现（hal.c，2026-09-17）
 *
 * 两个系统共用：UART 调试输出、OC 组件调用（MMIO ABI）、FreeRTOS 钩子与 tick。
 * LVGL 相关部分用 CRYPTAND_WITH_LVGL 条件编译（系统 2 编译时定义）。
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"
#include "hal.h"

#ifdef CRYPTAND_WITH_LVGL
#include "lvgl.h"
#endif

/* ---------- 设备寄存器 ---------- */
/* ⚠ 这里**没有 16550 的 MMIO 寄存器**了：UART 按 2026-09-27 定案改成 guest RAM 里的外设缓存
 *   （见下方"串口"一节）。旧的 UART_THR/UART_LSR/... 与 HAL_UART_BASE 已整条删除（逐字节跨线程事务没了）。 */
#define OC_ABI(i)       (*(volatile uint32_t *)(HAL_REG_BASE + (uint32_t)(i)))

/* ==================== 系统配置（RAM 顶端保留块） ==================== */

/* 链接脚本提供：配置块起始（= 栈顶，栈向下生长 ⇒ 不冲突） */
extern const char _config_block[];

/** 前向声明（实现见下方 OC 组件调用一节） */
static uint32_t str_len(const char *s);

static int configOk;

void hal_config_init(void)
{
    configOk = 0;
    if (_config_block[0] == 'C' && _config_block[1] == 'F' &&
        _config_block[2] == 'G' && _config_block[3] == '1') {
        configOk = 1;
    }
    hal_uart_puts("[cfg] config block @ ");
    hal_uart_putu((uint32_t)(uintptr_t)_config_block);
    hal_uart_puts(configOk ? "  (valid)\r\n" : "  (absent, using defaults)\r\n");
}

int hal_config_present(void)
{
    return configOk;
}

/** 在配置块里查找 key（返回 value 起始指针；未找到返回 NULL） */
static const char *configFind(const char *key)
{
    if (!configOk) {
        return 0;
    }
    const int keyLen = (int)str_len(key);
    const char *p = _config_block + 4;                 /* 跳过 "CFG1" */
    uint32_t scanned = 0;
    while (*p != '\0' && scanned < 4090u) {
        /* 行首 */
        const char *line = p;
        if (*line == '#') {                            /* 注释：跳到行尾 */
            while (*p != '\0' && *p != '\n') { p++; scanned++; }
            if (*p == '\n') { p++; scanned++; }
            continue;
        }
        /* 匹配 key= */
        int i = 0;
        while (i < keyLen && line[i] == key[i]) { i++; }
        if (i == keyLen && line[i] == '=') {
            return line + keyLen + 1;
        }
        while (*p != '\0' && *p != '\n') { p++; scanned++; }
        if (*p == '\n') { p++; scanned++; }
    }
    return 0;
}

/** 把配置块里的值拷进静态缓冲（去行尾空白、补 '\0'） */
static const char *configValue(const char *key, const char *def)
{
    static char buf[96];
    const char *v = configFind(key);
    if (v == 0) {
        return def;
    }
    int n = 0;
    while (*v != '\0' && *v != '\n' && *v != '\r' && n < (int)sizeof(buf) - 1) {
        buf[n++] = *v++;
    }
    while (n > 0 && (buf[n - 1] == ' ' || buf[n - 1] == '\t')) {
        n--;
    }
    buf[n] = '\0';
    return buf;
}

const char *hal_config_get(const char *key, const char *def)
{
    const char *v = configValue(key, 0);
    return v == 0 ? def : v;
}

int hal_config_int(const char *key, int def)
{
    const char *v = configValue(key, 0);
    if (v == 0) {
        return def;
    }
    int sign = 1;
    int out = 0;
    if (*v == '-') {
        sign = -1;
        v++;
    }
    if (*v < '0' || *v > '9') {
        return def;
    }
    while (*v >= '0' && *v <= '9') {
        out = out * 10 + (*v - '0');
        v++;
    }
    return out * sign;
}

int hal_config_bool(const char *key, int def)
{
    const char *v = configValue(key, 0);
    if (v == 0) {
        return def;
    }
    if (v[0] == '1' || v[0] == 't' || v[0] == 'T' || v[0] == 'y' || v[0] == 'Y' || v[0] == 'o' || v[0] == 'O') {
        return 1;
    }
    if (v[0] == '0' || v[0] == 'f' || v[0] == 'F' || v[0] == 'n' || v[0] == 'N') {
        return 0;
    }
    return def;
}

/* ==================== 串口：读写"虚拟机建立的 UART 硬件"的寄存器窗口（2026-09-27 定案）========
 *
 * 层级：虚拟机 = 硬件层（这块 UART 硬件由它持有并实现）；芯片只执行指令 + 碰这些寄存器。
 * 布局的唯一来源是 common 的 UartRegs（hal.h 的 HAL_UART_* 是它的 C 侧镜像，闸门逐项对拍）。
 * 固件**只写** cr1 / tx_push / rx_pop / ovr_ack 四个字段，其余只读 ⇒ 两侧各写各的字段，没有锁。 */

#define UART_MAGIC     (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_MAGIC))
#define UART_SR        (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_SR))
#define UART_TX_TAKEN  (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_TX_TAKEN))
#define UART_RX_GIVEN  (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_RX_GIVEN))
#define UART_OVR_W     (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_OVR))
#define UART_TX_SLOTS  (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_TX_SLOTS))
#define UART_CR1_W     (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_CR1))
#define UART_TX_PUSH_W (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_TX_PUSH))
#define UART_RX_POP_W  (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_RX_POP))
#define UART_OVR_ACK_W (*(volatile uint32_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_OVR_ACK))
#define UART_TX_BUF    ((volatile uint8_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_TX_BUF))
#define UART_RX_BUF    ((volatile uint8_t *)(HAL_UART_CACHE_BASE + HAL_UART_OFF_RX_BUF))

/** 校验状态：0 = 还没校验；1 = 就绪；< 0 = 校验过没通过（-1 魔数不对） */
static int uartState;
/** 本机单调写计数（**唯一写者**：固件侧只有 hal_uart_putc 动它；它同时是"写 DR 事件"的发布） */
static uint32_t uartTxPush;
/** 本机单调读计数（**唯一写者**：固件侧只有 hal_uart_getc 动它） */
static uint32_t uartRxPop;
/** 窗口没就绪期间被丢弃的字节数（打一笔调试口 + 记在本地；不静默） */
static uint32_t uartLocalDropped;

int hal_uart_hw_init(void)
{
    if (UART_MAGIC != HAL_UART_MAGIC) {
        uartState = -1;                     /* 织物还没发布窗口：明确报错，不猜 */
        return -1;
    }
    /* 照真实芯片：FIFO 要**软件使能**（并设接收阈值）。没装载模块的芯片这里读到的 FIFOMOD=0，
     * 于是 CR1 写 0 —— 生效容量就是经典 1 字节 DR。 */
    uint32_t cr1 = 0u;
    if ((UART_SR & HAL_UART_SR_FIFOMOD) != 0u) {
        cr1 = HAL_UART_CR1_FIFOEN | HAL_UART_CR1_RXFTH_1_2;
    }
    UART_CR1_W = cr1;
    uartTxPush = UART_TX_PUSH_W;            /* 本机上次的写水位（热复位不丢） */
    uartRxPop = UART_RX_POP_W;
    uartState = 1;
    return 0;
}

int hal_uart_ready(void)
{
    if (uartState == 0) {
        hal_uart_hw_init();
    }
    return uartState > 0 ? 1 : 0;
}

int hal_uart_getc(void)
{
    if (uartState == 0 && hal_uart_hw_init() != 0) {
        return -1;
    }
    if (uartState < 0) {
        return -1;
    }
    /* 判"有没有字节"用**两个计数**（器件已投进窗口的字节数 − 本机读走的字节数）：
     * ⚠ 不能只看 SR.RXNE：器件每 tick 才刷新一次状态位，而芯片在两次刷新之间可能已经把窗口里的
     *   字节读完了 —— 那时 RXNE 还停留在上一轮的值，照着它读就会把已经读过的槽位再读一遍
     *   （实测：编辑命令行时整行被旧字节冲乱）。两个计数都在同一段寄存器里、芯片自己算得准。 */
    if (UART_RX_GIVEN == uartRxPop) {
        return -1;                          /* 没有新字节（绝不阻塞） */
    }
    const uint8_t c = UART_RX_BUF[uartRxPop & (HAL_UART_RX_BYTES - 1u)];
    uartRxPop++;
    UART_RX_POP_W = uartRxPop;              /* 发布"读了一次 DR"：织物据此腾出窗口位置 */
    return (int)c;
}

int hal_uart_rx_ready(void)
{
    if (uartState == 0) {
        hal_uart_hw_init();
    }
    /* 与 hal_uart_getc 同一个判据（两个计数之差）：SR.RXNE 是器件侧的同一信息，
     * 但每 tick 才刷新一次，拿它当"还有没有"的循环条件会多读一遍已读过的槽位。 */
    return (uartState > 0 && UART_RX_GIVEN != uartRxPop) ? 1 : 0;
}

void hal_uart_ack_ovr(void)
{
    UART_OVR_ACK_W = UART_OVR_W;            /* 软件确认：SR.OVR（粘滞位）随之清掉 */
}

uint32_t hal_uart_ovr(void)
{
    return UART_OVR_W;
}

/* ==================== 陷阱向量（FreeRTOS 要求应用自己装） ==================== */

/* portASM.S 导出的陷阱总入口（见 portASM.S: .global freertos_risc_v_trap_handler） */
extern void freertos_risc_v_trap_handler(void);

void hal_install_trap_vector(void)
{
    __asm volatile ("csrw mtvec, %0" :: "r"(freertos_risc_v_trap_handler));
}

#include <stdarg.h>

/* ===== 调试口（2026-09-24；2026-09-27 缩小用途）=====
 * 当年是为了"把 uart0 输出镜像到屏幕"（那时 UART 是 MMIO、会被系统重配、FIFO 还会丢字节）。
 * UART 改成 guest RAM 缓存之后这些理由都不成立了，**逐字节镜像已删除**；现在它只剩两处用途：
 *   ① 引导服务调用标记 0x01（oc_boot_invoke，离线闸门数它确认"一次请求只兑一次"）；
 *   ② UART 缓存窗口没就绪时打一笔 0xEE（"日志没了"必须能看出是窗口没接上，不是固件没输出）。
 * ⚠ 它本身仍是一处 MMIO 停摆点（写它 ⇒ native 内核停下等宿主），归第二阶段清单。
 * 关掉（0）时这两处都不进固件。 */
#ifndef CRYPTAND_DEBUG_CONSOLE
#  define CRYPTAND_DEBUG_CONSOLE 1
#endif
#define CRYPTAND_DEBUG_PORT (*(volatile uint8_t *)0x10003000u)

/**
 * 最简 printf（不依赖 newlib）：支持 %s %d %u %x %c %%。
 * LOG 宏（hal.h，LVGL 风格）在 CRYPTAND_LOG_LEVEL 足够高时展开成对它的调用。
 */
int hal_uart_printf(const char *fmt, ...)
{
    va_list ap;
    int n = 0;
    va_start(ap, fmt);
    for (const char *p = fmt; p != 0 && *p != 0; p++) {
        if (*p != '%') { hal_uart_putc(*p); n++; continue; }
        p++;
        switch (*p) {
            case 's': {
                const char *s = va_arg(ap, const char *);
                while (s != 0 && *s != 0) { hal_uart_putc(*s++); n++; }
                break;
            }
            case 'd': {
                int v = va_arg(ap, int);
                if (v < 0) { hal_uart_putc('-'); n++; v = -v; }
                hal_uart_putu((uint32_t)v);
                break;
            }
            case 'u': hal_uart_putu(va_arg(ap, uint32_t)); break;
            case 'x': hal_uart_puthex(va_arg(ap, uint32_t)); break;
            case 'c': hal_uart_putc((char)va_arg(ap, int)); n++; break;
            case '%': hal_uart_putc('%'); n++; break;
            case 0: goto done;
            default: hal_uart_putc('%'); hal_uart_putc(*p); n += 2; break;
        }
    }
done:
    va_end(ap);
    return n;
}
void hal_uart_putc(char c)
{
    /* ⚠ 这里**不再有**"每个字符写一次调试口镜像"（那是旧 MMIO UART 链的最后一处逐字节 MMIO：
     *   固件每输出一个字符就写 0x1000_3000 ⇒ native 内核停一次 CPU 等宿主兑现）。
     *   2026-09-27 定案后 UART 是虚拟机建立的一块硬件，芯片只写它的 DR —— 调试口只剩
     *   引导服务调用标记那一处用途（见 oc_boot_invoke）。 */
    if (uartState == 0 && hal_uart_hw_init() != 0) {
        /* 窗口没就绪：**绝不写到野地址**，但也绝不静默 —— 打一笔调试口（0xEE 不会被当成日志字符）。 */
        if (uartLocalDropped == 0u) {
#if CRYPTAND_DEBUG_CONSOLE
            CRYPTAND_DEBUG_PORT = 0xEEu;
#endif
        }
        uartLocalDropped++;
        return;
    }
    if (uartState < 0) {
        uartLocalDropped++;
        return;
    }
    /* 写 DR 之前轮询就绪位（照真实芯片）：
     *   ① 状态位 TXE —— 硬件缓存（DR / FIFO）还有位置就为 1；器件每 tick 刷新一次；
     *   ② 硬不变式 —— 本机已写但还没被器件搬走的字节数 < 数据窗口容量。
     *   两个条件都要看：器件每 tick 才刷新状态位，而一个 tick 内可能写很多字节；
     *   ② 保证**绝不绕窗口覆盖**尚未被搬走的字节。取两者更严的一个。
     * ⚠ 这是**程序自己的死等**：指令照常在这条循环里执行，虚拟机没有停下等任何东西；
     *   器件每 tick 搬走一批，下一轮循环就拿到空位（256 字节的硬件 FIFO ⇒ 一整屏日志不必等）。 */
    while ((UART_SR & HAL_UART_SR_TXE) == 0u
           || (uint32_t)(uartTxPush - UART_TX_TAKEN) >= HAL_UART_TX_BYTES) {
    }
    UART_TX_BUF[uartTxPush & (HAL_UART_TX_BYTES - 1u)] = (uint8_t)c;
    uartTxPush++;
    UART_TX_PUSH_W = uartTxPush;            /* 发布"写了一次 DR"（本机是唯一写者） */
}

void hal_uart_puts(const char *s)
{
    while (*s != '\0') {
        hal_uart_putc(*s++);
    }
}

void hal_uart_puthex(uint32_t v)
{
    static const char hex[] = "0123456789ABCDEF";
    hal_uart_puts("0x");
    for (int shift = 28; shift >= 0; shift -= 4) {
        hal_uart_putc(hex[(v >> shift) & 0xFu]);
    }
}

void hal_uart_putu(uint32_t v){
    char buf[12];
    int i = 0;
    if (v == 0) {
        hal_uart_putc('0');
        return;
    }
    while (v > 0 && i < 11) {
        buf[i++] = (char)('0' + (v % 10u));
        v /= 10u;
    }
    while (i > 0) {
        hal_uart_putc(buf[--i]);
    }
}

/* ==================== OC 组件调用 ==================== */

#define OC_BOOT_POLL_LIMIT 2000000u  /* 引导专用：宿主未接桥时不至于死等（只给 HANDLE_BOOT 用） */

static int      oc_last_results[8];
static uint32_t oc_last_result_count;

/* ==================== 外部设备调用：内存邮箱协议（2026-09-17 定案）====================
 *
 * 用户定案：「寄存器仅限自己的内容，外部设备全部靠类似 linux 或者映射为一段内存操作，
 * 然后写入后可以由程序决定是否等待，或者通过状态机在执行其他任务时看一眼，
 * 所有外设当作耗时操作」。
 *
 * 旧协议（自旋把机器拖死）：填寄存器 → 写 CALL=1 → 自旋 lw STATUS 200 万次。
 *   每次 sw/lw 都是一次完整 MMIO 往返（≈0.44ms），固件每 5 个周期读一次 STATUS，
 *   于是 100 MHz 的机器实测只有 0.01 MHz。
 * 新协议：写**内存邮箱**（普通 sw）+ 轮询 **RAM 里的 STATE**（普通 lw，零往返）。
 *   邮箱区在 0x20020000（主 RAM 128K 之后），是内核自持 RAM 的一部分 ⇒ 不产生 MMIO 事务。
 *
 * 引导（OC_HANDLE_BOOT）仍走老的寄存器同步路径：它跑在调度器启动之前，没有任务可阻塞，
 * 而且只在启动时调用几次，不值得为它引入异步。
 */

/* 找一个空闲通道；全忙返回 -1 */
static int oc_mb_alloc(void)
{
    uint32_t i;
    for (i = 0; i < OC_MB_CHANNELS; i++) {
        if (OC_MB(OC_MB_CH(i), OC_MB_STATE) == OC_MB_IDLE) {
            return (int)i;
        }
    }
    return -1;
}

/* 把请求写进指定通道（**不等待**） */
static void oc_mb_post(int ch, uint32_t handle, uint32_t method, uint32_t argc,
                       const uint32_t *args, const void *buf, uint32_t bufLen)
{
    const uintptr_t b = OC_MB_CH(ch);
    uint32_t i;

    OC_MB(b, OC_MB_COMPONENT) = handle;
    OC_MB(b, OC_MB_METHOD)    = method;
    OC_MB(b, OC_MB_ARGC)      = argc;
    for (i = 0; i < argc && i < 8u; i++) {
        OC_MB(b, OC_MB_ARG0 + i * 4u) = args[i];
    }
    OC_MB(b, OC_MB_BUF_ADDR) = (uint32_t)(uintptr_t)buf;
    OC_MB(b, OC_MB_BUF_LEN)  = bufLen;
    /* ★ 状态**最后**写：宿主看到 REQUEST 时其余字段一定已经就位 */
    OC_MB(b, OC_MB_STATE)    = OC_MB_REQUEST;
}

/**
 * 非阻塞投递：立即返回通道号（>=0），失败返回 -1。
 *
 * <p>给"状态机风格的调用方"用：投出去，回去干别的，之后再 {@link #component_poll} 看一眼。
 * 「所有外设当作耗时操作」的落点就在这个 API 上。</p>
 */
int component_invoke_async(uint32_t handle, uint32_t method, uint32_t argc, const uint32_t *args,
                           const void *buf, uint32_t bufLen)
{
    const int ch = oc_mb_alloc();
    if (ch < 0) {
        return -1;
    }
    oc_mb_post(ch, handle, method, argc, args, buf, bufLen);
    return ch;
}

/** 查一次通道状态（普通 lw，零往返）：1=完成，0=还在跑，-1=出错或通道非法 */
int component_poll(int channel)
{
    if (channel < 0 || (uint32_t)channel >= OC_MB_CHANNELS) {
        return -1;
    }
    switch (OC_MB(OC_MB_CH(channel), OC_MB_STATE)) {
    case OC_MB_DONE:
        return 1;
    case OC_MB_ERROR:
        return -1;
    default:
        return 0;
    }
}

/** 取某个通道的第 index 个返回值 */
int component_read_result(int channel, uint32_t index)
{
    if (channel < 0 || (uint32_t)channel >= OC_MB_CHANNELS || index >= 8u) {
        return 0;
    }
    return (int)OC_MB(OC_MB_CH(channel), OC_MB_RESULT0 + index * 4u);
}

/* ==================== 盘：OC filesystem 组件的 C 封装 ====================
 *
 * 盘的权威语义 = OC 的 filesystem 组件（用户定案「硬盘软盘等走 OC」），所以固件这边
 * 只是"把 ABI 的 FS_* 调用发出去、把结果拿回来"：没有第二套文件语义、没有块设备、
 * 也没有 FatFS —— 文件系统在宿主那侧（盘 = 宿主文件夹）。
 */

/** 等结果的轮询上限：与引导期同一量级（宿主每 tick 兑现一次，50ms 粒度） */
#define FS_POLL_LIMIT       2000000u

/** -2 = 还没读过配置；-1 = 没盘 */
static int fs_handle_cache = -2;

int fs_disk_handle(void)
{
    if (fs_handle_cache == -2) {
        fs_handle_cache = hal_config_int("disk.fs_handle", -1);
    }
    return fs_handle_cache;
}

/**
 * 投递一次 FS_* 调用并等结果。
 *
 * @return 成功返回**通道号**（调用方读 result 后必须 component_release）；
 *         失败返回**负的 ABI 错误码**（状态机语义见 hal.h 的邮箱说明）。
 */
static int fs_call(uint32_t method, const uint32_t *args, uint32_t argc,
                   const void *buf, uint32_t bufLen)
{
    const int handle = fs_disk_handle();
    if (handle < 0) {
        return -OC_ERR_UNKNOWN_COMPONENT;      /* 没插盘：让上层看到明确的错误，而不是静默 0 */
    }
    const int ch = component_invoke_async((uint32_t)handle, method, argc, args, buf, bufLen);
    if (ch < 0) {
        return -OC_ERR_TIMEOUT;                /* 通道分配失败 */
    }
    for (uint32_t spin = 0; spin < FS_POLL_LIMIT; spin++) {
        const int st = component_poll(ch);
        if (st == 1) {
            return ch;                         /* DONE：result 由调用方取 */
        }
        if (st == -1) {
            const int err = component_read_result(ch, 0);   /* ERROR：result0 = ABI 错误码 */
            component_release(ch);
            return err > 0 ? -err : -OC_ERR_COMPONENT_FAILED;
        }
    }
    component_release(ch);
    return -OC_ERR_TIMEOUT;                    /* 宿主一直没兑现 */
}

/** 路径类调用的公共尾巴：投递 path（入方向）→ 取 result0 → 释放 */
static int fs_path_call(uint32_t method, const char *path)
{
    const int ch = fs_call(method, 0, 0u, path, str_len(path));
    if (ch < 0) {
        return ch;
    }
    const int r = component_read_result(ch, 0);
    component_release(ch);
    return r;
}

int fs_open(const char *path, unsigned mode)
{
    const uint32_t args[1] = { (uint32_t)mode };
    const int ch = fs_call(OC_FS_OPEN, args, 1u, path, str_len(path));
    if (ch < 0) {
        return ch;
    }
    const int handle = component_read_result(ch, 0);
    component_release(ch);
    return handle > 0 ? handle : -OC_ERR_BAD_HANDLE;
}

int fs_read(int handle, void *buf, unsigned len)
{
    const uint32_t args[2] = { (uint32_t)handle, (uint32_t)len };
    /* buf 是固件自带的缓冲区（guest 物理地址）：宿主把数据写进来（出方向） */
    const int ch = fs_call(OC_FS_READ, args, 2u, buf, (uint32_t)len);
    if (ch < 0) {
        return ch;
    }
    const int got = component_read_result(ch, 0);   /* 实读字节数；-1 = EOF */
    component_release(ch);
    return got;
}

int fs_write(int handle, const void *data, unsigned len)
{
    const uint32_t args[1] = { (uint32_t)handle };
    const int ch = fs_call(OC_FS_WRITE, args, 1u, data, (uint32_t)len);
    if (ch < 0) {
        return ch;
    }
    const int ok = component_read_result(ch, 0);
    component_release(ch);
    return ok;
}

int fs_seek(int handle, unsigned whence, int offset)
{
    const uint32_t args[3] = { (uint32_t)handle, (uint32_t)whence, (uint32_t)offset };
    const int ch = fs_call(OC_FS_SEEK, args, 3u, 0, 0u);
    if (ch < 0) {
        return ch;
    }
    const int pos = component_read_result(ch, 0);
    component_release(ch);
    return pos;
}

int fs_close(int handle)
{
    const uint32_t args[1] = { (uint32_t)handle };
    const int ch = fs_call(OC_FS_CLOSE, args, 1u, 0, 0u);
    if (ch < 0) {
        return ch;
    }
    component_release(ch);
    return 0;
}

int fs_list(const char *path, char *buf, unsigned cap)
{
    const unsigned plen = (unsigned)str_len(path) + 1u;    /* 含结尾 NUL */
    unsigned i;
    if (buf == 0 || cap <= plen) {
        return -OC_ERR_BUF_TOO_SMALL;
    }
    /* 约定：缓冲区起始放 "path"，BUF_LEN = **容量**（结果会原地覆盖路径） */
    for (i = 0; i < plen; i++) {
        buf[i] = path[i];
    }
    const int ch = fs_call(OC_FS_LIST, 0, 0u, buf, cap);
    if (ch < 0) {
        return ch;
    }
    const int n = component_read_result(ch, 0);     /* 写入字节数；-1 = 不是目录/不存在 */
    component_release(ch);
    return n;
}

int fs_size(const char *path)
{
    return fs_path_call(OC_FS_SIZE, path);
}

int fs_exists(const char *path)
{
    return fs_path_call(OC_FS_EXISTS, path);
}

int fs_is_dir(const char *path)
{
    return fs_path_call(OC_FS_IS_DIR, path);
}

int fs_mkdir(const char *path)
{
    return fs_path_call(OC_FS_MKDIR, path);
}

int fs_delete(const char *path)
{
    return fs_path_call(OC_FS_DELETE, path);
}

int fs_space_total(void)
{
    const int ch = fs_call(OC_FS_SPACE, 0, 0u, 0, 0u);
    if (ch < 0) {
        return ch;
    }
    const int total = component_read_result(ch, 0);
    component_release(ch);
    return total;
}

int fs_space_used(void)
{
    const int ch = fs_call(OC_FS_SPACE, 0, 0u, 0, 0u);
    if (ch < 0) {
        return ch;
    }
    const int used = component_read_result(ch, 1);
    component_release(ch);
    return used;
}

/** 释放通道（置回 IDLE，宿主可以复用它） */
void component_release(int channel)
{
    if (channel >= 0 && (uint32_t)channel < OC_MB_CHANNELS) {
        OC_MB(OC_MB_CH(channel), OC_MB_STATE) = OC_MB_IDLE;
    }
}

/* 引导专用：老的寄存器同步路径（行为与改动前完全一致） */
static int oc_boot_invoke(uint32_t handle, uint32_t method, uint32_t argc, const uint32_t *args,
                          const void *buf, uint32_t bufLen)
{
    uint32_t i, spins;

    /* 引导服务调用计数（宿主侧用调试口的 writeCount 数这个标记字节来读它）：
     * 0x01 不会被 %c 之外的任何日志路径产生，且 sink 会把它挡在可读日志之外。 */
    CRYPTAND_DEBUG_PORT = 0x01u;

    OC_ABI(OC_REG_COMPONENT) = handle;
    OC_ABI(OC_REG_METHOD)    = method;
    OC_ABI(OC_REG_ARG_COUNT) = argc;
    for (i = 0; i < argc && i < 8u; i++) {
        OC_ABI(OC_REG_ARG0 + i * 4u) = args[i];
    }
    OC_ABI(OC_REG_BUF_ADDR) = (uint32_t)(uintptr_t)buf;
    OC_ABI(OC_REG_BUF_LEN)  = bufLen;
    OC_ABI(OC_REG_ERROR)    = 0u;
    OC_ABI(OC_REG_CALL)     = 1u;

    for (spins = 0; spins < OC_BOOT_POLL_LIMIT; spins++) {
        const uint32_t status = OC_ABI(OC_REG_STATUS);
        if (status == OC_STATUS_DONE || status == OC_STATUS_ERROR) {
            if (status == OC_STATUS_ERROR) {
                OC_ABI(OC_REG_STATUS) = OC_STATUS_IDLE;
                return -1;
            }
            oc_last_result_count = OC_ABI(OC_REG_RESULT_COUNT);
            if (oc_last_result_count > 8u) {
                oc_last_result_count = 8u;
            }
            for (i = 0; i < oc_last_result_count; i++) {
                oc_last_results[i] = (int)OC_ABI(OC_REG_RESULT0 + i * 4u);
            }
            OC_ABI(OC_REG_STATUS) = OC_STATUS_IDLE;
            return (int)oc_last_result_count;
        }
    }
    return -1;
}

/**
 * 外部设备调用的同步形态：投递 + 轮询 RAM。
 *
 * <p>轮询的是**内存**（零 MMIO 往返），所以即使忙等也只烧自己的 CPU 周期，不跨宿主。
 * 结果与旧的 {@code component_result()} 兼容 —— 调用方代码不必改。</p>
 *
 * <p>想不等待就用 {@link #component_invoke_async} + {@link #component_poll}。</p>
 */
int component_invoke(uint32_t handle, uint32_t method, uint32_t argc, const uint32_t *args,
                     const void *buf, uint32_t bufLen)
{
    int ch;
    uint32_t i, n;

    /* 引导服务仍走寄存器同步路径（调度器未起，无法阻塞） */
    if (handle == OC_HANDLE_BOOT) {
        return oc_boot_invoke(handle, method, argc, args, buf, bufLen);
    }

    ch = component_invoke_async(handle, method, argc, args, buf, bufLen);
    if (ch < 0) {
        return -1;   /* 通道全忙 */
    }
    for (;;) {
        const int r = component_poll(ch);
        if (r > 0) {
            n = OC_MB(OC_MB_CH(ch), OC_MB_RESULT_COUNT);
            if (n > 8u) {
                n = 8u;
            }
            oc_last_result_count = n;
            for (i = 0; i < n; i++) {
                oc_last_results[i] = component_read_result(ch, i);
            }
            component_release(ch);
            return (int)n;
        }
        if (r < 0) {
            component_release(ch);
            return -1;
        }
    }
}

int component_result(uint32_t index)
{
    return (index < oc_last_result_count) ? oc_last_results[index] : 0;
}

static uint32_t str_len(const char *s)
{
    uint32_t n = 0;
    while (s[n] != '\0') {
        n++;
    }
    return n;
}

uint32_t gpu_get_resolution(void)
{
    if (component_invoke(OC_HANDLE_GPU, OC_GPU_GET_RES, 0u, 0, 0, 0u) < 0) {
        return 0u;
    }
    const uint32_t w = *(volatile uint32_t *)(HAL_REG_BASE + OC_REG_RESULT0);
    const uint32_t h = *(volatile uint32_t *)(HAL_REG_BASE + OC_REG_RESULT0 + 4u);
    if (w == 0u || h == 0u) {
        return 0u;
    }
    return (w << 16) | (h & 0xFFFFu);
}

int gpu_set(uint32_t x, uint32_t y, const char *text)
{
    const uint32_t args[2] = { x, y };
    return component_invoke(OC_HANDLE_GPU, OC_GPU_SET, 2u, args, text, str_len(text));
}

int gpu_fill(uint32_t x, uint32_t y, uint32_t w, uint32_t h, char ch)
{
    const uint32_t args[4] = { x, y, w, h };
    return component_invoke(OC_HANDLE_GPU, OC_GPU_FILL, 4u, args, &ch, 1u);
}

int gpu_blit(uint32_t x, uint32_t y, uint32_t w, uint32_t h, const uint8_t *cells)
{
    const uint32_t args[4] = { x, y, w, h };
    return component_invoke(OC_HANDLE_GPU, OC_GPU_BLIT, 4u, args, cells, w * h);
}

/* ==================== 消息缓存区（主线程 → 虚拟机）====================
 *
 * 布局与语义见 hal.h 那一段；这里只说实现上的三条约定：
 *   ① 只写 tail（其余头字段归宿主）⇒ 两边各写各的字段，不需要锁；
 *   ② 先读 head/tail 算 used，再整帧地读 —— 帧跨环尾靠 msg_read 的两段拷贝处理；
 *   ③ 任何不自洽（used > capacity、帧长超出已用字节）都**返回错误**，绝不猜、不静默丢。 */
static uint32_t msgTaken;

static inline uint32_t msg_rd32(uint32_t off)
{
    return *(volatile uint32_t *)(HAL_MSG_BASE + off);
}

static inline void msg_wr32(uint32_t off, uint32_t value)
{
    *(volatile uint32_t *)(HAL_MSG_BASE + off) = value;
}

static inline uint8_t msg_rd8(uint32_t off)
{
    return *(volatile uint8_t *)(HAL_MSG_BASE + off);
}

int hal_msg_ready(void)
{
    return msg_rd32(HAL_MSG_OFF_MAGIC) == HAL_MSG_MAGIC ? 1 : 0;
}

uint32_t hal_msg_capacity(void)
{
    if (!hal_msg_ready()) {
        return 0u;
    }
    const uint32_t c = msg_rd32(HAL_MSG_OFF_CAPACITY);
    /* 容量由宿主写，但不能全信：离谱的值会让下标算飞。上限用编译期常量夹住（明确拒绝，不静默取默认） */
    return (c >= 2u && c <= HAL_MSG_CAPACITY_MAX) ? c : 0u;
}

uint32_t hal_msg_head(void)   { return hal_msg_ready() ? msg_rd32(HAL_MSG_OFF_HEAD) : 0u; }
uint32_t hal_msg_tail(void)   { return hal_msg_ready() ? msg_rd32(HAL_MSG_OFF_TAIL) : 0u; }
uint32_t hal_msg_used(void)   { return hal_msg_ready() ? msg_rd32(HAL_MSG_OFF_USED) : 0u; }
uint32_t hal_msg_dropped(void){ return hal_msg_ready() ? msg_rd32(HAL_MSG_OFF_DROPPED) : 0u; }
uint32_t hal_msg_seq(void)    { return hal_msg_ready() ? msg_rd32(HAL_MSG_OFF_SEQ) : 0u; }
uint32_t hal_msg_state(void)  { return hal_msg_ready() ? msg_rd32(HAL_MSG_OFF_STATE) : 0u; }
uint32_t hal_msg_taken(void)  { return msgTaken; }

/** 从（单调计数）off 起连续拷 n 字节出环形数据区；跨环尾时分成两段（不做逐字节取模） */
static void msg_read(uint32_t off, void *dst, uint32_t n)
{
    const uint32_t cap = hal_msg_capacity();
    if (cap == 0u || n == 0u || dst == 0) {
        return;
    }
    uint8_t *out = (uint8_t *)dst;
    const uint32_t at = off % cap;
    uint32_t first = cap - at;
    if (first > n) {
        first = n;
    }
    for (uint32_t i = 0; i < first; i++) {
        out[i] = msg_rd8(HAL_MSG_DATA_OFF + at + i);
    }
    for (uint32_t i = first; i < n; i++) {
        out[i] = msg_rd8(HAL_MSG_DATA_OFF + (i - first));
    }
}

int hal_msg_take(uint32_t *kind, void *buf, uint32_t cap)
{
    if (!hal_msg_ready()) {
        return -1;
    }
    const uint32_t ring = hal_msg_capacity();
    if (ring == 0u) {
        return -1;
    }
    const uint32_t head = msg_rd32(HAL_MSG_OFF_HEAD);
    const uint32_t tail = msg_rd32(HAL_MSG_OFF_TAIL);
    const uint32_t used = head - tail;          /* 无符号差值 = 待发字节数 */
    if (used == 0u) {
        return 0;                               /* 空 */
    }
    if (used > ring) {
        return -1;                              /* 头部不自洽：不猜（宁可不取，也不读错数据） */
    }
    uint8_t hdr[2];
    msg_read(tail, hdr, 2u);
    const uint32_t len = (uint32_t)hdr[1];
    if (len + 2u > used) {
        return -1;                              /* 帧长超出已用字节：同上 */
    }
    if (kind != 0) {
        *kind = (uint32_t)hdr[0];
    }
    if (len > cap) {
        /* 装不下：整条消费掉并明确报 -2（半读会让下一条帧的边界全错） */
        msg_wr32(HAL_MSG_OFF_TAIL, tail + len + 2u);
        msgTaken++;
        return -2;
    }
    if (len > 0u && buf != 0) {
        msg_read(tail + 2u, buf, len);
    }
    msg_wr32(HAL_MSG_OFF_TAIL, tail + len + 2u);
    msgTaken++;
    return (int)len;
}

uint32_t hal_msg_take_all(void *buf, uint32_t cap)
{
    if (!hal_msg_ready() || buf == 0) {
        return 0u;
    }
    const uint32_t ring = hal_msg_capacity();
    if (ring == 0u) {
        return 0u;
    }
    const uint32_t head = msg_rd32(HAL_MSG_OFF_HEAD);
    uint32_t tail = msg_rd32(HAL_MSG_OFF_TAIL);
    if ((head - tail) == 0u || (head - tail) > ring) {
        return 0u;
    }
    uint8_t *out = (uint8_t *)buf;
    uint32_t at = 0u;
    /* 只整帧地拷 ⇒ tail 永远落在帧边界上（下一次 hal_msg_take 才能正确解析） */
    while ((head - tail) > 0u) {
        uint8_t hdr[2];
        msg_read(tail, hdr, 2u);
        const uint32_t len = (uint32_t)hdr[1];
        if (len + 2u > (head - tail)) {
            break;                              /* 头部被写坏：停手（不静默） */
        }
        if (at + len + 2u > cap) {
            break;                              /* 缓冲区装不下：剩下的留在队列里 */
        }
        msg_read(tail, out + at, len + 2u);
        at += len + 2u;
        tail += len + 2u;
        msgTaken++;
    }
    msg_wr32(HAL_MSG_OFF_TAIL, tail);
    return at;
}

/* ==================== FreeRTOS 钩子 ==================== */

void vAssertCalled(const char *pcFile, unsigned long ulLine)
{
    hal_uart_puts("\r\n[ASSERT] ");
    hal_uart_puts(pcFile);
    hal_uart_putc(':');
    hal_uart_putu((uint32_t)ulLine);
    hal_uart_puts("\r\n");
    for (;;) {
        /* 停在这里等世界侧诊断（固件不该带病继续跑） */
    }
}

void vApplicationMallocFailedHook(void)
{
    hal_uart_puts("\r\n[FATAL] heap exhausted\r\n");
    for (;;) {
    }
}

void vApplicationStackOverflowHook(TaskHandle_t xTask, char *pcTaskName)
{
    (void)xTask;
    hal_uart_puts("\r\n[FATAL] stack overflow: ");
    hal_uart_puts(pcTaskName);
    hal_uart_puts("\r\n");
    for (;;) {
    }
}

/**
 * FreeRTOS 每 tick 回调（configUSE_TICK_HOOK = 1）。
 *
 * LVGL 的时基就从这里来（官方推荐做法之一）：把 RTOS tick 换算成毫秒喂给 lv_tick_inc，
 * 这样 LVGL 不需要额外的硬件定时器，也不必再开一个 tick 任务。
 */
void vApplicationTickHook(void)
{
    /* 🔍 诊断（2026-09-18）：真机症状"进系统后打不了字"，根子却是**所有任务从未运行**。
     *   这个钩子运行在 tick 中断里 —— 第一行打印 = "中断已交付到 guest 且 FreeRTOS 的
     *   tick 处理在跑"；没有这一行 = 中断压根没到（查 mstatus.MIE / mie.MTIE / trap）。
     *   只打一次，定位完可以删。 */
    static uint32_t dbgTicks = 0u;
    if (dbgTicks++ == 0u) {
        hal_uart_puts("[DBG] first tick interrupt delivered\r\n");
    }
#ifdef CRYPTAND_WITH_LVGL
    lv_tick_inc((uint32_t)(1000u / (uint32_t)configTICK_RATE_HZ));
#endif
}
