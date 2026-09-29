/*
 * 系统信息命令模块（cmd_sysinfo.c）—— version / mem / uptime / sysinfo / tasks。
 *
 * 从 common/console.c 搬出来（库化 OS，用户 2026-09-18 定案）：console 只留 shell 自身的
 * 三条命令（help / clear / echo），"系统有什么功能"由模块提供 —— 于是不需要这些信息的
 * 系统可以选择不链本模块（镜像更小、依赖更干净）。
 */
#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"
#include "hal.h"
#include "console.h"
#include "module.h"

#ifdef CRYPTAND_WITH_LVGL
#include "lvgl.h"       /* version 要报 LVGL 版本（只有 UI OS 定义该宏） */
#endif

static void cmd_version(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_line("Cryptand OS 0.1");
    con_puts("  FreeRTOS   : " tskKERNEL_VERSION_NUMBER "\n");
#ifdef CRYPTAND_WITH_LVGL
    con_puts("  LVGL       : ");
    con_putu((uint32_t)LVGL_VERSION_MAJOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_MINOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_PATCH);
    con_putc('\n');
#else
    con_puts("  LVGL       : (not compiled in)\n");
#endif
    con_puts("  shell      : 0.1\n");
}

static void cmd_mem(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_puts("REG[0] (heartbeat) = ");
    con_putu(*(volatile uint32_t *)(HAL_HEARTBEAT_BASE + 0x00u));   /* 心跳区（原来误读组件桥偏移 0 = OC_REG_CALL）*/
    con_puts("\n");
    con_puts("FreeRTOS heap free = ");
    con_putu((uint32_t)xPortGetFreeHeapSize());
    con_puts(" bytes\n");
}

static void cmd_uptime(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_puts("ticks = ");
    con_putu((uint32_t)xTaskGetTickCount());
    con_puts("  (");
    con_putu((uint32_t)(xTaskGetTickCount() / (TickType_t)configTICK_RATE_HZ));
    con_line(" s)");
}

static void cmd_sysinfo(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_line("--- system info ---");
    con_puts("kernel   : Cryptand OS 0.1 / FreeRTOS\n");
    /* ⚠ 真实标称频率与 ISA 由**宿主按插着的处理器规格**注入（配置块 cpu.mhz / cpu.isa）——
     *   这里绝不能用编译期常量 configCPU_CLOCK_HZ：它写死 20 MHz，与芯片实际规格无关。 */
    {
        const int mhz = hal_config_int("cpu.mhz", 0);
        const char *isa = hal_config_get("cpu.isa", "");
        con_puts("cpu      : ");
        con_line((isa != 0 && isa[0] != 0) ? isa : "unknown-isa");
        con_puts("           @ ");
        if (mhz > 0) {
            con_putu((uint32_t)mhz);
            con_line(" MHz (nominal, from host)");
        } else {
            con_line("unknown MHz (host did not provide cpu.mhz)");
        }
    }
    con_puts("tick     : ");
    con_putu((uint32_t)configTICK_RATE_HZ);
    con_line(" Hz");
    con_puts("heap     : ");
    con_putu((uint32_t)configTOTAL_HEAP_SIZE);
    con_line(" bytes (FreeRTOS)");
    con_puts("gpu      : ");
    con_line(gpu_set(1u, 1u, "") >= 0 ? "component bridge ONLINE" : "component bridge OFFLINE (host not wired yet)");
}

static void cmd_tasks(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_puts("FreeRTOS priorities  = ");
    con_putu((uint32_t)configMAX_PRIORITIES);
    con_puts("\n");
    con_line("(full task list needs configUSE_TRACE_FACILITY; off to save RAM)");
}

static const shell_cmd SYSINFO_COMMANDS[] = {
    { "version", "system & kernel version", cmd_version },
    { "mem",     "heap & heartbeat",        cmd_mem },
    { "uptime",  "uptime (ticks)",          cmd_uptime },
    { "sysinfo", "system info (CPU / MHz)", cmd_sysinfo },
    { "tasks",   "FreeRTOS task limits",    cmd_tasks },
};

const cryptand_module SYSINFO_COMMAND_MODULE = {
    "sysinfo-commands",
    SYSINFO_COMMANDS,
    (int)(sizeof(SYSINFO_COMMANDS) / sizeof(SYSINFO_COMMANDS[0])),
    0,
};
