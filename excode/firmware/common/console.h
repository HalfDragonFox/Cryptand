/*
 * ============================================================================
 * Cryptand OS · 字符控制台与 shell（console.h，2026-09-17）
 *
 * 为什么要有"字符层"：OC 的屏幕是**字符设备**（GPU 组件按字符格显示），
 * LVGL 渲染出来的是像素。两者分工：
 *   · 文字（终端 / 标题 / 菜单）→ **字符层**（本文件，gpu_blit 一次上屏）
 *   · 图形（窗口边框 / 进度条 / 图标 / 动画）→ LVGL 渲染后量化成字符
 * 所以两个系统都是"字符层 + LVGL 图形层"分区共存。
 *
 * 系统(2)：左 40 列字符终端 + 右 40 列 LVGL 图形面板
 * 系统(3)：LVGL 全屏桌面 + 字符层叠加的标题栏/任务栏/终端内容
 * ============================================================================
 */
#ifndef CRYPTAND_CONSOLE_H
#define CRYPTAND_CONSOLE_H

#include <stdint.h>

/* 终端尺寸（字符格）。
 *
 * ⚠ 2026-09-25：这两个值不再代表"屏幕多大"，而是**缓冲上限**；有效尺寸由 con_init() 向
 *   gpu 组件查询（gpu.getResolution）后写入 conCols/conRows。
 *   起因（用户实测）：固件原来写死 40×25，而 OC 屏幕是 50×16 ⇒ 第 17..25 行画到屏幕外，
 *   现象就是"屏幕满了不滚动、旧行也不被顶掉"。
 *   系统(2)只用左半屏，系统(3)用一个窗口的区域 —— 可在构建时用
 *   -DCON_COLS=36 -DCON_ROWS=13 收紧上限。 */
#ifndef CON_COLS
#define CON_COLS 80
#endif
#ifndef CON_ROWS
#define CON_ROWS 32
#endif

/** 缓冲上限（数组按它开；运行时有效尺寸 ≤ 它） */
#define CON_COLS_MAX CON_COLS
#define CON_ROWS_MAX CON_ROWS
/** 查询失败时的保守默认（OC 终端常见尺寸） */
#define CON_DEF_COLS 40
#define CON_DEF_ROWS 25

/** 运行时有效尺寸（≤ 上面的上限）：con_init() 查询屏幕后写入 */
int con_cols(void);
int con_rows(void);

/* ---------- 字符终端 ---------- */
void con_init(int originCol, int originRow);
void con_clear(void);
void con_putc(char c);
void con_puts(const char *s);
void con_putu(uint32_t v);
void con_puthex(uint32_t v);
/** 把整块字符缓冲刷到屏幕（一次 gpu_blit；没有组件桥时静默失败） */
void con_flush(void);
/** 光标处打印一行（自动换行/滚动；**只标脏不立即刷屏**，见 con_tick） */
void con_line(const char *s);
/** 合并刷新节流间隔（毫秒）：系统心跳任务按它调 con_tick() —— 同时也是键盘回显延迟上界 */
#define CON_FLUSH_INTERVAL_MS 20
/**
 * 定期刷新（系统心跳任务调用）：把 con_line/con_putc 标脏的内容**批量**刷到屏幕。
 *
 * <p>⚠ 系统必须在心跳任务里调用它，否则屏幕永远不更新（con_line 已经不自己刷了）。</p>
 */
void con_tick(void);

/* ---------- shell ---------- */
typedef void (*shell_fn)(int argc, char **argv);

/* 一条 shell 命令（名字 / 帮助 / 处理函数）。
 * ⚠ 从 console.h 导出，是为了让**系统程序**（如 Cryptand OS PE）注册自己的命令表 ——
 *   否则 PE 的分区/安装命令只能塞进 console.c 的内置表，那会让每个系统都看见它们。 */
typedef struct {
    const char *name;
    const char *help;
    shell_fn    fn;
} shell_cmd;

/* 注册**扩展命令表**（可选；系统在 shell_init 之前调用）。
 * 查找顺序：先内置、后扩展（扩展表不得覆盖内置命令名：同名以内置为准）。 */
void shell_set_commands(const shell_cmd *cmds, int count);

void shell_init(void);
/** 喂入一个输入字符（UART 或 OC 键盘组件都走这里） */
void shell_feed(char c);
/** 从串口 RX 拉取可用字符（宿主注入口；无数据则立即返回） */
void shell_poll_uart(void);
/** 执行一行（供内置脚本/应用调用，不经过行编辑） */
void shell_exec_line(const char *line);
/** 打印帮助 */
void shell_help(void);

#endif /* CRYPTAND_CONSOLE_H */
