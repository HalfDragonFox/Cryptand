/*
 * Cryptand OS 的模块清单（modules.c）—— 属于"系统描述"，不是通用库。
 *
 * 库化 OS（用户 2026-09-18 定案）：系统 = 内核（main.c）+ 库（common）+ 要装的模块（下面列）。
 * 想给 OS 加功能，就在这里加一行、并在 build.ps1 的 Sources 里链上对应文件。
 */
#include "module.h"

extern const cryptand_module SYSINFO_COMMAND_MODULE;   /* common/cmd_sysinfo.c */
extern const cryptand_module GPU_COMMAND_MODULE;       /* common/cmd_gpu.c */

const cryptand_module *const OS_MODULES[] = {
    &SYSINFO_COMMAND_MODULE,
    &GPU_COMMAND_MODULE,
};

const int OS_MODULES_COUNT = (int)(sizeof(OS_MODULES) / sizeof(OS_MODULES[0]));
