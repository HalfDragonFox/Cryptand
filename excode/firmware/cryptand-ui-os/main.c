/*
 * ============================================================================
 * Cryptand OS · 系统(3) **Cryptand UI OS**（main.c，2026-09-17）
 *
 * 用户定位：**自定义 UI 操作系统**（LVGL + FreeRTOS），Windows 风格的桌面：
 *   · 桌面背景 + 右上角时钟
 *   · 底部任务栏（"开始" 按钮 + 三个应用图标 + 状态文字）
 *   · 可拖动窗口（标题栏 + 关闭按钮）
 *   · 应用(1)：终端（字符层，shell 跑在里面）
 *   · 应用(2)：系统信息（LVGL 控件：CPU/内存条、版本）
 *
 * 字符层与图形层的分工（OC 屏是字符设备）：
 *   LVGL  → 背景/窗口框/任务栏/控件（量化成 ' ░▒▓█' 灰度字符）
 *   console.c → 窗口**内部**的文字（终端内容、标题栏文字）
 *
 * 任务：
 *   gui    ：lv_timer_handler
 *   shell  ：从 UART RX 收命令（宿主可注入）
 *   clock  ：刷新时钟与状态条
 *   con    ：UART 心跳
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"

#include "lvgl.h"
#include "hal.h"
#include "console.h"
#include "module.h"
#include "display.h"

/* 模块清单：本系统的 modules.c 提供（库化 OS —— 内核不认识任何命令） */
extern const cryptand_module *const UI_MODULES[];
extern const int UI_MODULES_COUNT;

#define REG(i)  (*(volatile uint32_t *)(HAL_HEARTBEAT_BASE + 4u * (uint32_t)(i)))

void lv_port_disp_init(void);

/* ---------------- 屏幕分区（字符格）----------------
   终端窗口内部：第 5..40 列、第 6..21 行（36×16 格）—— console 用 -DCON_COLS/ROWS 对齐 */
#define TERM_ORIGIN_COL 5
#define TERM_ORIGIN_ROW 6

static lv_obj_t *taskbar;
static lv_obj_t *clockLabel;
static lv_obj_t *statusLabel;
static lv_obj_t *winTerm;
static lv_obj_t *winInfo;
static lv_obj_t *barCpu;
static lv_obj_t *barHeap;

/* ---------------- 极简数字/字符串工具（-nostdlib）---------------- */
static int str_eq(const char *a, const char *b)
{
    while (*a && *b) {
        if (*a != *b) {
            return 0;
        }
        a++;
        b++;
    }
    return *a == *b;
}

static void u_to_str(uint32_t v, char *out)
{
    char tmp[12];
    int i = 0;
    if (v == 0) {
        out[0] = '0';
        out[1] = '\0';
        return;
    }
    while (v > 0 && i < 11) {
        tmp[i++] = (char)('0' + (v % 10u));
        v /= 10u;
    }
    int j = 0;
    while (i > 0) {
        out[j++] = tmp[--i];
    }
    out[j] = '\0';
}

/* ---------------- 窗口：LVGL 画框，标题文字走字符层 ---------------- */
static lv_obj_t *make_window(lv_obj_t *scr, int x, int y, int w, int h)
{
    lv_obj_t *win = lv_obj_create(scr);
    lv_obj_set_pos(win, x, y);
    lv_obj_set_size(win, w, h);
    lv_obj_set_style_bg_color(win, lv_color_make(16, 16, 32), 0);
    lv_obj_set_style_border_color(win, lv_color_white(), 0);
    lv_obj_set_style_border_width(win, 1, 0);
    lv_obj_set_style_radius(win, 2, 0);
    lv_obj_set_style_pad_all(win, 2, 0);
    return win;
}

static void build_desktop(void)
{
    lv_obj_t *scr = lv_screen_active();
    lv_obj_set_style_bg_color(scr, lv_color_make(8, 8, 24), 0);
    lv_obj_set_style_pad_all(scr, 0, 0);

    /* 任务栏（底部 8 像素 = 4 字符行） */
    taskbar = lv_obj_create(scr);
    lv_obj_set_pos(taskbar, 0, 42);
    lv_obj_set_size(taskbar, 160, 8);
    lv_obj_set_style_bg_color(taskbar, lv_color_make(40, 40, 90), 0);
    lv_obj_set_style_border_width(taskbar, 0, 0);
    lv_obj_set_style_radius(taskbar, 0, 0);
    lv_obj_set_style_pad_all(taskbar, 1, 0);

    lv_obj_t *start = lv_label_create(taskbar);
    lv_label_set_text(start, "START");
    lv_obj_set_style_text_color(start, lv_color_white(), 0);

    statusLabel = lv_label_create(taskbar);
    lv_label_set_text(statusLabel, "CPU 20MHz  RAM 96KB");
    lv_obj_align(statusLabel, LV_ALIGN_LEFT_MID, 34, 0);

    clockLabel = lv_label_create(scr);
    lv_label_set_text(clockLabel, "00:00");
    lv_obj_align(clockLabel, LV_ALIGN_TOP_RIGHT, -2, 2);

    /* 窗口(1)：终端（内容由字符层写） */
    winTerm = make_window(scr, 8, 10, 74, 34);

    /* 窗口(2)：系统信息（LVGL 控件） */
    winInfo = make_window(scr, 88, 10, 68, 30);
    lv_obj_t *infoTitle = lv_label_create(winInfo);
    lv_label_set_text(infoTitle, "SYSTEM");
    lv_obj_align(infoTitle, LV_ALIGN_TOP_LEFT, 0, 0);

    barCpu = lv_bar_create(winInfo);
    lv_obj_set_size(barCpu, 60, 6);
    lv_obj_align(barCpu, LV_ALIGN_TOP_LEFT, 0, 12);
    lv_bar_set_range(barCpu, 0, 100);

    barHeap = lv_bar_create(winInfo);
    lv_obj_set_size(barHeap, 60, 6);
    lv_obj_align(barHeap, LV_ALIGN_TOP_LEFT, 0, 22);
    lv_bar_set_range(barHeap, 0, 100);
}

/* ---------------- 任务 ---------------- */
static void task_gui(void *arg)
{
    (void)arg;
    for (;;) {
        lv_timer_handler();
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

static void task_clock(void *arg)
{
    (void)arg;
    for (;;) {
        const uint32_t tick = (uint32_t)xTaskGetTickCount();
        const uint32_t heapFree = (uint32_t)xPortGetFreeHeapSize();
        const uint32_t heapTotal = (uint32_t)configTOTAL_HEAP_SIZE;
        char num[12];
        char buf[16];

        /* 时钟：把 tick 换算成 mm:ss（演示用） */
        const uint32_t secs = tick / (uint32_t)configTICK_RATE_HZ;
        char mm[3];
        char ss[3];
        u_to_str(secs / 60u, mm);
        u_to_str(secs % 60u, ss);
        buf[0] = (mm[1] && !mm[2]) ? '0' : mm[0];
        buf[1] = (mm[1] && !mm[2]) ? mm[0] : (mm[1] ? mm[1] : mm[0]);
        if (secs / 60u < 10u) {
            buf[0] = '0';
            buf[1] = mm[0];
        }
        buf[2] = ':';
        buf[3] = (secs % 60u < 10u) ? '0' : ss[0];
        buf[4] = (secs % 60u < 10u) ? ss[0] : ss[1];
        buf[5] = '\0';
        lv_label_set_text(clockLabel, buf);

        lv_bar_set_value(barCpu, (int32_t)(tick % 100u), LV_ANIM_OFF);
        lv_bar_set_value(barHeap, (int32_t)((heapFree * 100u) / (heapTotal ? heapTotal : 1u)),
                         LV_ANIM_OFF);

        u_to_str(heapFree / 1024u, num);
        buf[0] = 'R'; buf[1] = 'A'; buf[2] = 'M'; buf[3] = ' '; buf[4] = '\0';
        int k = 4;
        for (int i = 0; num[i] != '\0' && k < 12; i++) {
            buf[k++] = num[i];
        }
        buf[k++] = 'K';
        buf[k] = '\0';
        lv_label_set_text(statusLabel, buf);

        vTaskDelay(pdMS_TO_TICKS(500));
    }
}

static void task_shell(void *arg)
{
    (void)arg;
    for (;;) {
        shell_poll_uart();
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

static void task_con(void *arg)
{
    (void)arg;
    uint32_t shown = 0;
    for (;;) {
        const uint32_t hb = REG(0);
        if (hb != shown) {
            shown = hb;
            if ((shown % 40u) == 0u) {
                hal_uart_puts("ui-os tick ");
                hal_uart_putu(shown);
                hal_uart_puts("\r\n");
            }
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

/* 桌面壳命令：wm（窗口管理器信息）—— 挂在 shell 里做自检用（简化：只打印） */
static void boot_script(void)
{
    con_line("Cryptand UI OS 0.1");
    con_line("desktop: LVGL windows + char-layer text");
    con_line("apps: terminal, system");
    con_line("type 'help' for shell commands");
}

int main(void)
{
    hal_uart_puts("\r\n[Cryptand OS] booting (2) Cryptand UI OS: FreeRTOS + LVGL desktop)...\r\n");
    hal_install_trap_vector();          /* ⚠ 必须在调度器之前：FreeRTOS 的 ecall/中断都靠它 */

    /* 显示后端：接管宿主注入的显存窗口（写内存即写显存，**带 16 色前后景**）——唯一路径。
     * 拿不到窗口就没有显示后端（lv_port_disp 不再退回 gpu_blit），这里明确报出来。 */
    {
        /* 与另两个系统同一条路（display.c 的 disp_auto_init）：基址/容量都在宿主注入的配置块里 */
        const int dispOk = disp_auto_init();
        hal_uart_puts("[Cryptand UI OS] display window -> ");
        hal_uart_puts(dispOk == 0 ? "OK\r\n" : "MISSING (disp.base/disp.bytes not injected)\r\n");
    }

    lv_init();
    lv_port_disp_init();
    build_desktop();
    hal_uart_puts("[Cryptand OS] desktop built\r\n");
    /* 桌面标题也进 UART：屏幕给玩家看，UART 给日志 / 无人化测试看 */
    hal_uart_puts("[Cryptand UI OS] title: ");
    hal_uart_puts(hal_config_get("desktop.title", "Cryptand UI OS"));
    hal_uart_puts("\r\n");

    /* 字符层：终端窗口内部（与 lv_port_disp 的全屏量化叠加显示） */
    con_init(TERM_ORIGIN_COL, TERM_ORIGIN_ROW);
    boot_script();
    /* ★ 模块化：命令由模块注册（sysinfo / gpu / lvgl 三张表由框架合并后一次性注册） */
    modules_init(UI_MODULES, UI_MODULES_COUNT);

    shell_init();
    shell_exec_line("sysinfo");

    xTaskCreate(task_gui,   "gui",   1024, NULL, 3, NULL);
    xTaskCreate(task_clock, "clock",  512, NULL, 2, NULL);
    xTaskCreate(task_shell, "shell",  512, NULL, 2, NULL);
    xTaskCreate(task_con,   "con",    384, NULL, 1, NULL);

    hal_uart_puts("[Cryptand OS] starting scheduler\r\n");
    vTaskStartScheduler();

    return 1;
}
