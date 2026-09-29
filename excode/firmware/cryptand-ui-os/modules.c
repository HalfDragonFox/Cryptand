/*
 * Cryptand UI OS 的模块清单（modules.c）—— 见 cryptand-os/modules.c 的说明。
 *
 * ⚠ 只有本系统链 lvgl 模块（common/cmd_lvgl.c）——这正是库化的收益：
 *   不需要 LVGL 的系统一块 LVGL 代码都不带。
 */
#include "module.h"

extern const cryptand_module SYSINFO_COMMAND_MODULE;   /* common/cmd_sysinfo.c */
extern const cryptand_module GPU_COMMAND_MODULE;       /* common/cmd_gpu.c */
extern const cryptand_module LVGL_COMMAND_MODULE;      /* common/cmd_lvgl.c */

const cryptand_module *const UI_MODULES[] = {
    &SYSINFO_COMMAND_MODULE,
    &GPU_COMMAND_MODULE,
    &LVGL_COMMAND_MODULE,
};

const int UI_MODULES_COUNT = (int)(sizeof(UI_MODULES) / sizeof(UI_MODULES[0]));
