/*
 * ============================================================================
 * Cryptand Boot · 盘上的 **bootloader**（main.c，2026-09-25 收口 / 2026-09-27 定案）
 *
 * 引导分层（用户 2026-09-24 定案；2026-09-27："EEPROM 属于外部 BIOS 部分，然后虚拟机内为真实芯片模拟"、
 * "BIOS 类似现实做引导，负责从盘中读取文件加载到虚拟机内运行"）：
 *
 *   Cryptand BIOS（宿主 Java = EEPROM／主板 BIOS flash + 上电取指那一段）
 *        枚举机箱里的盘 → 按 boot order 选**第一有效文件** → 把该盘的 /boot/loader.bin
 *        （就是本固件的字节）写进 guest ROM 引导区 0x0 → **启动虚拟机**
 *        → 把「引导盘是哪一块」交给程序（= INT 19h 传 DL）
 *   ↓
 *   本固件（bootloader，跑在虚拟机里那颗芯片上）
 *        经 **BIOS 读盘服务**（= 现实 INT 13h）请 BIOS 把引导盘上的系统文件读进 guest 内存
 *        → 跳过去执行（裸机语义：交出去就不回来了）
 *
 * ⚠ 本固件**不参与选盘**：盘是 BIOS 选好的，我们就是被它从那块盘载入执行的
 *   （读盘服务里没有"哪块盘"参数 —— INT 13h 读的就是 BIOS 当前引导的那块盘）。
 *   但"读哪个文件、装到哪个地址"**是我们说了算**，这正是 INT 13h 的形状：
 *   BIOS 只提供"把盘上的字节读进内存"这一项硬件能力，取哪个文件是引导程序的事。
 *
 * ⚠ 所以 ABI 里只有一个方法 BOOT_METHOD_READ_FILE(path 走缓冲区, arg0 = 载入地址)：
 *   旧的 DISK_COUNT + LOAD(index)（bootloader 自己枚举盘）与 loadSystem（无参、路径写死在宿主，
 *   "回来向宿主要系统本体"）都已在 2026-09-25 / 09-27 收口时删除 —— 它们把引导程序该做的决定
 *   塞给了宿主。
 *
 * 为什么读盘这件事仍由宿主兑现：OC 的硬盘不是块设备，而是 filesystem **组件**（open/read/seek），
 * 读文件必须走组件调用；芯片侧没有这条总线 —— 与真实机器上"INT 13h 由 BIOS 提供"完全同形。
 *
 * 本固件自身极小（不链 LVGL、不起 shell），因为它同时是 mini OS 那种低配档位的引导层。
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"
#include "hal.h"

/* 引导服务的 ABI 常量在 hal.h（C 侧 ABI 镜像，check-abi-sync.mjs 只认它）——
   本文件不再重复定义，免得两侧各写一份还要靠人工对齐。 */

/* 系统镜像要装的地址 = guest ROM 系统区 0x1_0000（cryptand_os.ld 的链接地址，
   与宿主 OcBoardLayout.SYS_LOAD_BASE / BootPlan.SYSTEM_BASE 同值）。
   ⚠ 这是**引导协议的契约**，不是随手填的数：内核装在哪个地址属于引导协议的一部分
   （对照 Linux 的 LOAD_PHYSICAL_ADDR），所以由引导程序告诉 BIOS（= INT 13h 的 ES:BX），
   而不是让 BIOS 替它决定。 */
#define BOOT_SYSTEM_ADDR 0x00010000u

/* 要哪个文件同样由我们说了算：BIOS 不认识"系统"这个概念，它只会按路径读盘
   （NUL 结尾，经 REG_BUF_ADDR/REG_BUF_LEN 缓冲区传给宿主）。 */
static const char boot_system_path[] = "/boot/system.bin";


static void print_u(uint32_t v)
{
    hal_uart_putu(v);
}

static void boot_panic(const char *reason)
{
    hal_uart_puts("\r\n[Cryptand Boot] *** ");
    hal_uart_puts(reason);
    hal_uart_puts(" ***\r\n");
    hal_uart_puts("[Cryptand Boot] halted (power cycle to retry)\r\n");
    for (;;) {
        /* 停机：真实 BIOS 的 "no bootable device" 也是这么停的 */
    }
}

int main(void)
{
    /* 串口 = 虚拟机建立的一块 UART 硬件（2026-09-27）：校验寄存器窗口并按真实芯片做法使能 FIFO */
    hal_uart_hw_init();

    hal_uart_puts("\r\n");
    hal_uart_puts("========================================\r\n");
    hal_uart_puts(" Cryptand Boot 0.2  (bootloader)\r\n");
    hal_uart_puts(" loaded by Cryptand BIOS from a drive\r\n");
    hal_uart_puts("========================================\r\n");
    hal_uart_puts("[Cryptand Boot] config: ");
    hal_uart_puts(hal_config_present() ? "loaded" : "defaults");
    hal_uart_puts("\r\n");

    /* 唯一的引导服务调用：请 BIOS 把**引导盘**上的系统文件读进 guest 内存 —— 现实 INT 13h。
       参数全来自我们：要哪个文件（缓冲区里的 NUL 结尾路径）、装到哪个地址（arg0）。
       ⚠ 它不是"让宿主替我把系统取来"：BIOS 只按我们给的路径与地址读字节。 */
    const uint32_t args[1] = { BOOT_SYSTEM_ADDR };
    if (component_invoke(OC_HANDLE_BOOT, BOOT_METHOD_READ_FILE, 1u, args,
                         boot_system_path, (uint32_t)sizeof(boot_system_path)) < 0) {
        boot_panic("BIOS disk service unavailable (host bridge not wired)");
    }
    const uint32_t len = (uint32_t)component_result(0);
    const uint32_t loadAddr = (uint32_t)component_result(1);

    /* 有效判据：BIOS 报告读到了字节（宿主已经把文件写进 ROM 系统区了）。
       ⚠ 这里不做魔数校验：程序从 0x10000 开始执行，第一条必须是指令。 */
    if (len == 0u || loadAddr == 0u || len > (512u * 1024u)) {
        boot_panic("no system file on the boot drive");
    }

    hal_uart_puts("[Cryptand Boot] system ");
    print_u(len);
    hal_uart_puts(" bytes @ ");   // hal_uart_puthex 自带 0x 前缀（以前这里多写了一个 0x ⇒ 打出 0x0x…）
    hal_uart_puthex(loadAddr);
    hal_uart_puts("  from ");
    hal_uart_puts(boot_system_path);
    hal_uart_puts("\r\n");
    hal_uart_puts("[Cryptand Boot] handing over control...\r\n");

    /* 交控制权：裸机语义 —— 交出去不回来（用函数指针调用；系统按我们的 ABI 直接用设备寄存器）。
       跳转地址由 BIOS 回报（= 我们请求的载入地址），入口 = 载入地址（镜像首字节就是 .text.start）。 */
    typedef void (*app_entry_t)(void);
    app_entry_t appEntry = (app_entry_t)(uintptr_t)loadAddr;
    appEntry();

    /* 走到这里说明系统镜像居然返回了 —— 明确报出来，而不是静默重试 */
    boot_panic("system image returned");
    return 0;   /* 不会到这里 */
}
