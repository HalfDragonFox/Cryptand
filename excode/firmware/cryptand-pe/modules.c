/*
 * PE 的模块清单（modules.c）—— 属于"系统描述"，不是通用库。
 *
 * 库化 OS（用户 2026-09-18 定案）之后，一个系统就是这样描述自己的：
 *   内核（main.c）+ 依赖的库（common 里的库文件）+ 要装的模块（在下面列）。
 * 用**指针数组**而不是结构体数组：模块描述定义在各自的 .c 里，
 * 而 C 的静态初始化器不接受"另一个 const 变量的值"（只有地址是常量表达式）。
 */
#include "module.h"

extern const cryptand_module PE_COMMAND_MODULE;    /* common/cmd_pe.c */
extern const cryptand_module SYSINFO_COMMAND_MODULE; /* common/cmd_sysinfo.c */
extern const cryptand_module GPU_COMMAND_MODULE;     /* common/cmd_gpu.c: gpu / draw（图形帧生产者）*/

const cryptand_module *const PE_MODULES[] = {
    &PE_COMMAND_MODULE,
    &SYSINFO_COMMAND_MODULE,
    &GPU_COMMAND_MODULE,
};

const int PE_MODULES_COUNT = (int)(sizeof(PE_MODULES) / sizeof(PE_MODULES[0]));
