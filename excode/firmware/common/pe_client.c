/*
 * PE 客户端库实现（pe_client.c）—— 协议说明见 pe_client.h。
 *
 * 这段代码原本长在 cryptand-pe/main.c 里；抽出来是为了"库化 OS"（用户 2026-09-18 定案）：
 * 系统只负责**列模块**，能力（库）由 common 提供。
 *
 * 行为与原来**逐字一致**（含 UART 日志格式）—— UART 行是无人化测试的断言依据：
 *   [PE] method=<n> channel=<ch> result="<换行换成 |>"
 */
#include "pe_client.h"
#include "hal.h"
#include "console.h"

/* 参数/结果缓冲区：入方向是 NUL 分隔串，出方向宿主把结果文本写回这里（同一份 guest 内存）。
 * 1024 字节够装机命令用（地址 + 程序 id + 若干选项），且不占太多 RAM。 */
static char peBuf[1024];

char *pe_client_buffer(void)
{
    return peBuf;
}

uint32_t pe_client_capacity(void)
{
    return (uint32_t)sizeof(peBuf);
}

/* 把 argv[first..argc-1] 用 NUL 连成一串（与宿主侧的 split 约定一致） */
static uint32_t join_nul(char *dst, uint32_t cap, int argc, char **argv, int first)
{
    uint32_t n = 0;
    int i;
    for (i = first; i < argc; i++) {
        const char *p = argv[i];
        while (*p) {
            if (n + 2u >= cap) {
                dst[n] = 0;
                return n;
            }
            dst[n++] = *p++;
        }
        dst[n++] = 0;
    }
    if (n < cap) {
        dst[n] = 0;
    }
    return n;
}

/* 结果文本可能有换行：按 '\n' 拆成多行逐行打印（就地拆，不额外分配） */
static void print_result(char *text)
{
    char *p = text;
    if (*p == 0) {
        con_line("(no output)");
        return;
    }
    while (*p) {
        char *nl = p;
        while (*nl && *nl != '\n' && *nl != '\r') {
            nl++;
        }
        if (*nl) {
            *nl = 0;
            con_line(p);
            p = nl + 1;
        } else {
            con_line(p);
            return;
        }
    }
}

void pe_client_run(uint32_t method, int argc, char **argv, int first)
{
    uint32_t len = 0;

    peBuf[0] = 0;
    if (first > 0 && argc > first) {
        len = join_nul(peBuf, sizeof(peBuf), argc, argv, first);
    }
    (void)len;

    /* ⚠ 第 6 个参数是**缓冲区容量**，不是"参数长度"：宿主写回结果时按它做上限，
     *   传 0 就等于告诉宿主"没地方放结果" ⇒ 屏幕上永远是 "(no output)"（真机踩过）。
     *   参数与结果共用 peBuf 是安全的：宿主在调用前就把入参数拷走了，结果覆盖它无害。 */
    const int ch = component_invoke(OC_HANDLE_PE, method, 0u, 0, peBuf,
                                    (uint32_t)sizeof(peBuf));

    /* 结果同时进 UART：屏幕给玩家看，UART 给日志/无人化测试断言（纯 ASCII） */
    hal_uart_puts("[PE] method=");
    hal_uart_putu(method);
    hal_uart_puts(" channel=");
    hal_uart_putu((uint32_t)ch);
    hal_uart_puts(" result=\"");

    if (ch < 0) {
        hal_uart_puts("\"\r\n");
        con_line("PE service unavailable (no host 'pe' component)");
        return;
    }

    print_result(peBuf);

    /* UART 里那一段：把文本里的换行换成 ' | '，保证一行日志一条结果 */
    {
        const char *p = peBuf;
        while (*p) {
            hal_uart_putc(*p == '\n' ? '|' : *p);
            p++;
        }
    }
    hal_uart_puts("\"\r\n");
}
