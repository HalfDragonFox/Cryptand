/*
 * PE 命令模块（cmd_pe.c）—— 装机环境对外的全部命令。
 *
 * 从 cryptand-pe/main.c 搬出来（库化 OS，用户 2026-09-18 定案）：命令是**模块**，
 * 内核不认识任何一条。别的系统要这些命令，就在自己的 modules.c 里加一行、
 * 并在 build.ps1 的 Sources 里链上本文件即可 —— 不需要复制代码。
 *
 * 命令实现本身保持**极薄**：真正干活的是宿主（文件系统在宿主侧），
 * 这里只负责组包 → 调 pe_client_run → 打印结果。
 */
#include "console.h"
#include "module.h"
#include "pe_client.h"
#include "hal.h"

static void cmd_disks(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    pe_client_run(OC_PE_TARGETS, 0, 0, 0);
}

static void cmd_info(int argc, char **argv)
{
    if (argc < 2) {
        con_line("usage: info <disk-address>");
        return;
    }
    pe_client_run(OC_PE_INFO, argc, argv, 1);
}

static void cmd_format(int argc, char **argv)
{
    if (argc < 2) {
        con_line("usage: format <disk-address> [fs] [label]");
        return;
    }
    pe_client_run(OC_PE_FORMAT, argc, argv, 1);
}

static void cmd_install(int argc, char **argv)
{
    if (argc < 3) {
        con_line("usage: install <disk-address> <program-id>");
        return;
    }
    pe_client_run(OC_PE_INSTALL, argc, argv, 1);
}

static void cmd_install_all(int argc, char **argv)
{
    if (argc < 3) {
        con_line("usage: install-all <disk-address> <program-id>");
        return;
    }
    pe_client_run(OC_PE_INSTALL_ALL, argc, argv, 1);
}

static const shell_cmd PE_COMMANDS[] = {
    { "disks",       "list target disks (address / size / has system)", cmd_disks },
    { "info",        "show one disk: info <address>",                   cmd_info },
    { "format",      "partition+format: format <address> [fs] [label]", cmd_format },
    { "install",     "install a program: install <address> <program>",  cmd_install },
    { "install-all", "one-shot: format + install + verify",             cmd_install_all },
};

/* 模块描述：命令表由框架统一注册（见 module.h），所以 init 为 0 */
const cryptand_module PE_COMMAND_MODULE = {
    "pe-commands",
    PE_COMMANDS,
    (int)(sizeof(PE_COMMANDS) / sizeof(PE_COMMANDS[0])),
    0,
};
