/*
 * ============================================================================
 * Cryptand OS PE . installation environment (main.c, 2026-09-18 库化)
 *
 * 用户定案（2026-09-18）："库化 OS，即 OS 本身没有内容，像 linux 一样通过不同 lib 和
 * 模块提供各种命令和功能"。
 *
 * 所以本文件现在只剩**内核骨架**：初始化 → 按清单起模块 → 起任务。
 *   · 命令（disks / info / format / install / install-all）→ common/cmd_pe.c
 *   · 与宿主 PE 服务的协议（组包 / 打印）→ common/pe_client.c
 *   · 模块框架 → common/module.c；本系统的清单 → modules.c
 * 任何系统要装机能力，链上同样的 lib+mod 即可，不再复制代码。
 *
 * ASCII-only text on screen (用户 2026-09-25：屏幕字符格是一格一字节，中文必乱)。
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"
#include "hal.h"
#include "console.h"
#include "display.h"
#include "module.h"
#include "pe_client.h"

#define REG(i)  (*(volatile uint32_t *)(HAL_HEARTBEAT_BASE + 4u * (uint32_t)(i)))

static volatile uint32_t g_heartbeat;

/* 模块清单由本系统的 modules.c 提供（见那里的说明） */
extern const cryptand_module *const PE_MODULES[];
extern const int PE_MODULES_COUNT;

/* ---------------- 任务（内核职责：起任务，不实现命令） ---------------- */

static void task_shell(void *arg)
{
    (void)arg;
    for (;;) {
        shell_poll_uart();
        /* 合并刷新（见 cryptand-os 的同名注释）：con_line 只标脏，这里按间隔批量刷。
         * ⚠ 轮询周期 2026-09-28 由 20ms 降到 **2ms**：与 cryptand-os 对齐。
         *   PE 是装机环境、也是真机上跑得最多的那份镜像（系统软盘 /boot/system.bin），
         *   它原来一直是 20ms —— "改了 cryptand-os 却没改 PE"正是上一轮键盘延迟
         *   迟迟不降的原因之一（世界里的机器跑的是 PE）。 */
        con_tick();
        vTaskDelay(pdMS_TO_TICKS(2));
    }
}

static void task_beat(void *arg)
{
    (void)arg;
    for (;;) {
        g_heartbeat++;
        con_tick();                     /* 合并刷新：con_line 只标脏，屏幕在这里批量更新 */
        REG(0) = g_heartbeat;
        REG(1) = (uint32_t)xTaskGetTickCount();
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

/* ---------------- 入口 ---------------- */

int main(void)
{
    hal_uart_puts("\r\n[Cryptand OS PE] booting (installation environment)...\r\n");
    hal_install_trap_vector();          /* 必须在调度器之前：FreeRTOS 的 ecall/中断都靠它 */
    hal_config_init();

    con_init(1, 1);
    con_line(hal_config_get("pe.banner", "Cryptand OS PE 0.1 (installer)"));
    hal_uart_puts("[Cryptand OS PE] banner: ");
    hal_uart_puts(hal_config_get("pe.banner", "Cryptand OS PE 0.1 (installer)"));
    hal_uart_puts("\r\n");

    /* ★ 模块化：内核不认识任何命令，只按清单初始化模块（命令由模块自己注册）。
     *   每个模块名会打到 UART（[mod] <name>），无人化测试据此断言"装了哪些模块"。 */
    modules_init(PE_MODULES, PE_MODULES_COUNT);

    /* 显示窗口：与另两个系统同一条路（display.c 的 disp_auto_init）。PE 也装 gpu/draw 模块，
     * 所以它同样要拿到宿主注入的显存窗口 —— "程序直接画图像"那条路在 PE 里也要能用。
     * 没有窗口不算故障（PE 的主通路是控制台），只如实报一行。 */
    {
        const int dispOk = disp_auto_init();
        hal_uart_puts("[DISP] window init -> ");
        hal_uart_puts(dispOk == 0 ? "OK\r\n" : "NO-WINDOW (host did not provide disp.base)\r\n");
    }

    shell_init();
    hal_uart_puts("[Cryptand OS PE] shell ready (type 'help')\r\n");

    /* 自检：宿主有没有把 PE 服务接上（没有就明确说，不静默） */
    {
        const int ch = component_invoke(OC_HANDLE_PE, OC_PE_TARGETS, 0u, 0, pe_client_buffer(), 0u);
        hal_uart_puts("[PE] service: ");
        hal_uart_puts(ch >= 0 ? "ONLINE" : "OFFLINE");
        hal_uart_puts("\r\n");
    }

    if (xTaskCreate(task_shell, "shell", 512, NULL, 2, NULL) != pdPASS) {
        hal_uart_puts("[FATAL] cannot create shell task\r\n");
        return 1;
    }
    if (xTaskCreate(task_beat, "beat", 256, NULL, 1, NULL) != pdPASS) {
        hal_uart_puts("[FATAL] cannot create beat task\r\n");
        return 1;
    }

    vTaskStartScheduler();

    for (;;) {
        /* 调度器不应该返回到这里 */
    }
}
