/*
 * LVGL 自检命令模块（cmd_lvgl.c）—— 从 common/console.c 搬出来（库化 OS，2026-09-18）。
 *
 * ★ 拆出来最实际的好处：**只有 UI OS 链它**。此前它长在 console.c 里，于是
 *   每个带 shell 的系统（OS / PE）都跟着把 LVGL 相关代码链进来，
 *   而它们根本不需要 —— "系统 = 库 + 模块"之后，不需要的功能一块都不带。
 */
#include <stdint.h>

#include "console.h"
#include "module.h"

#ifdef CRYPTAND_WITH_LVGL
#include "lvgl.h"
#endif

static void cmd_lvgl(int argc, char **argv)
{
    (void)argc;
    (void)argv;
#ifdef CRYPTAND_WITH_LVGL
    con_puts("LVGL     : ");
    con_putu((uint32_t)LVGL_VERSION_MAJOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_MINOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_PATCH);
    con_putc('\n');
    con_puts("OS layer : ");
    con_line("LV_OS_FREERTOS (official osal)");
#else
    con_line("LVGL is not compiled into this system (FreeRTOS only)");
#endif
}

static const shell_cmd LVGL_COMMANDS[] = {
    { "lvgl", "LVGL stack self-check", cmd_lvgl },
};

const cryptand_module LVGL_COMMAND_MODULE = {
    "lvgl-commands",
    LVGL_COMMANDS,
    (int)(sizeof(LVGL_COMMANDS) / sizeof(LVGL_COMMANDS[0])),
    0,
};
