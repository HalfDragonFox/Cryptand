/*
 * ============================================================================
 * PE 客户端库（pe_client.h, 2026-09-18）
 *
 * 与宿主 PE 服务（特殊句柄 OC_HANDLE_PE）对话的那层协议，从 cryptand-pe/main.c 抽出来 ——
 * 于是任何系统（OS / UI OS / mini OS / 将来的救援盘）都能"链上这个库就有装机能力"，
 * 而不必各自复制一份组包/打印逻辑（复制出来的第二份正是内测期要消掉的东西）。
 *
 * 协议（与宿主 PeDispatcher 同一张表）：
 *   入方向：参数用 NUL 连成一串放进 pe_client_buffer()，把**缓冲区容量**当第 6 个参数传过去；
 *   出方向：宿主把结果文本写回同一块缓冲区（覆盖入参 —— 宿主在调用前已拷走入参）。
 * ============================================================================
 */
#ifndef CRYPTAND_PE_CLIENT_H
#define CRYPTAND_PE_CLIENT_H

#include <stdint.h>

/* 参数/结果共用的缓冲区（1024 字节；入参 NUL 串，结果文本） */
char *pe_client_buffer(void);

/* 缓冲区容量（宿主写回结果的上限；传 0 会让屏幕上永远只有 "(no output)"） */
uint32_t pe_client_capacity(void);

/*
 * 调一次宿主 PE 服务，并把结果打成屏幕行 + 一行 UART 日志。
 *
 * @param method OC_PE_* 方法号
 * @param argc   argv 个数
 * @param argv   参数数组
 * @param first  从 argv 的第几项开始进缓冲区（0 = 本次不带缓冲区参数）
 */
void pe_client_run(uint32_t method, int argc, char **argv, int first);

#endif /* CRYPTAND_PE_CLIENT_H */
