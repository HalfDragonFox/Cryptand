/*
 * ============================================================================
 * Cryptand firmware module framework (module.h, 2026-09-18)
 *
 * 用户定案："库化 OS，即 OS 本身没有内容，像 linux 一样通过不同 lib 和模块提供
 * 各种命令和功能"。
 *
 * 三层分工：
 *   kernel  main.c   —— 初始化 + 起任务 + modules_init()。**内核里没有一条命令**。
 *   lib     *.c      —— 通用实现（hal / console / pe_client / disk ...），任何系统可链。
 *   module  cmd_*.c  —— 功能模块：**导出**一个 cryptand_module（命令表 + 可选 init）。
 *
 * ★ 命令表为什么由框架**合并**后再注册：
 *   console.c 的扩展命令表只有**一张**（shell_set_commands 存的是单个指针）。模块化之前
 *   只有一个 PE 注册，所以这个限制没暴露；一旦 sysinfo / gpu / lvgl 各注册一张，
 *   后注册的会**覆盖**前一个（表现成"某条命令神秘消失"）。合并放在框架里做，
 *   console.c 保持原样（少改一处就少一处回归）。
 * ============================================================================
 */
#ifndef CRYPTAND_MODULE_H
#define CRYPTAND_MODULE_H

#include <stdint.h>

#include "console.h"     /* shell_cmd —— 模块提供的命令表就是 shell_cmd 数组 */

typedef struct {
    const char *name;            /* 模块名（启动日志 [mod] <name>，无人化测试据此断言） */
    const shell_cmd *commands;   /* 本模块的命令表（可为 0：纯后台模块） */
    int commandCount;            /* 命令条数 */
    void (*init)(void);          /* 额外初始化（挂任务/装驱动），可为 0 */
} cryptand_module;

/*
 * 按清单初始化：先合并所有模块的命令表并一次性注册，再依次调用各模块的 init()。
 * 之后把模块名写进 UART。
 */
void modules_init(const cryptand_module *const *mods, int count);

#endif /* CRYPTAND_MODULE_H */
