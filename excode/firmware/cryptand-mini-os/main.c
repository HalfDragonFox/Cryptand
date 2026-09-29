/*
 * ===== Cryptand mini OS（2026-09-24）=====
 *
 * 用户定案："mini OS（低配 MCU 档）" —— 目标是让**最低配的盘**也有系统可跑。
 *
 * 为什么需要它：主系统 cryptand-os 是 15KB 左右，而 hdd1 只有 8KB —— 装不进去。
 * 本镜像不链 LVGL、不起 shell、不用调度器，只做"被 BIOS 从盘里引导起来并输出"这一件事，
 * 体积目标 < 8KB。
 *
 * 它同时是一个很好的**固件最小样例**：想看"一个 Cryptand 系统最少需要什么"时看这个文件。
 */
#include "hal.h"

int main(void)
{
    /* 串口 = 虚拟机建立的一块 UART 硬件（2026-09-27）：校验寄存器窗口并使能 FIFO */
    hal_uart_hw_init();

    /* ⚠ 暂用 hal_uart_puts（2026-09-24）：本镜像是第一个真正走 LOG 宏（hal_uart_printf）的镜像，
     *   而它在装置里跑进 main 之后就 fault 了。先用已经验证过的 puts 分辨"是那段新 printf 的问题，
     *   还是别的地方" —— 定位完再把 LOG 换回来。 */
    hal_uart_puts("\r\n========================================\r\n");
    hal_uart_puts(" Cryptand mini OS 0.1  (minimal bring-up)\r\n");
    hal_uart_puts("========================================\r\n");
    hal_uart_puts("[mini OS] booted from disk, no scheduler / no shell\r\n");

    /* 探一下组件桥在不在线；没有总线也能跑完（这正是"最小系统"的意思） */
    const int ch = component_invoke(OC_HANDLE_GPU, OC_GPU_GET_RES, 0u, 0, 0, 0u);
    hal_uart_puts(ch >= 0 ? "[mini OS] gpu bridge: ONLINE\r\n" : "[mini OS] gpu bridge: OFFLINE\r\n");

    hal_uart_puts("[mini OS] console ready.\r\n");

    /* 裸机语义：停机（系统起来了，但没有可做的事 —— 真实最小系统的样子） */
    for (;;) {
    }
}
