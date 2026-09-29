/*
 * 模块框架实现（module.c）—— 设计说明见 module.h。
 *
 * 只做三件事：合并命令表、一次性注册、按清单跑 init。刻意**不做**依赖解析/懒加载：
 * 固件是一次性静态链接的镜像，清单顺序就是初始化顺序。
 */
#include "module.h"
#include "hal.h"

/* 合并后的命令表上限：当前最多十几个命令，留足余量（每个 shell_cmd 12 字节 ⇒ 768B RAM） */
#define MODULE_CMD_MAX 64

static shell_cmd merged[MODULE_CMD_MAX];
static int mergedCount;

void modules_init(const cryptand_module *const *mods, int count)
{
    int i;
    int j;

    mergedCount = 0;
    for (i = 0; i < count; i++) {
        const cryptand_module *m = mods[i];
        if (m == 0) {
            continue;
        }
        for (j = 0; j < m->commandCount; j++) {
            if (mergedCount < MODULE_CMD_MAX) {
                merged[mergedCount++] = m->commands[j];
            } else {
                /* 表满必须可观测：静默丢命令会表现成"某条命令不存在"，极难查 */
                hal_uart_puts("[mod] command table full, dropped module: ");
                hal_uart_puts(m->name != 0 ? m->name : "?");
                hal_uart_puts("\r\n");
                break;
            }
        }
    }

    /* ★ 一次性注册：console 的扩展表是单张，多模块各自注册会互相覆盖（见 module.h 说明） */
    if (mergedCount > 0) {
        shell_set_commands(merged, mergedCount);
    }

    for (i = 0; i < count; i++) {
        const cryptand_module *m = mods[i];
        if (m == 0) {
            continue;
        }
        if (m->init != 0) {
            m->init();
        }
        hal_uart_puts("[mod] ");
        hal_uart_puts(m->name != 0 ? m->name : "?");
        hal_uart_puts("\r\n");
    }
}
