/*
 * ============================================================================
 * Cryptand OS · 系统(1)：**仅 FreeRTOS + 类 Lua 命令行**（main.c，2026-09-17 定稿）
 *
 * 用户定义：Cryptand OS = 仅 FreeRTOS，进入**类似 Lua（OpenOS）的命令行系统**；
 *          LVGL 是**可选包**，后续可以再加载进来（所以这个镜像里不链 LVGL，镜像只有十几 KB）。
 *
 * 屏幕：OC 的屏幕是字符设备 ⇒ 文字走字符层（console.c：字符缓冲 + 一次 gpu_blit）。
 * 输入：UART RX（宿主可注入）；OC 键盘组件接上后再加一路（组件桥）。
 *
 * 任务：shell（收命令） + con（心跳/日志）
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"
#include "hal.h"
#include "console.h"
#include "module.h"
#include "display.h"

/* 模块清单：本系统的 modules.c 提供（库化 OS —— 内核不认识任何命令） */
extern const cryptand_module *const OS_MODULES[];
extern const int OS_MODULES_COUNT;

#define REG(i)  (*(volatile uint32_t *)(HAL_HEARTBEAT_BASE + 4u * (uint32_t)(i)))

static volatile uint32_t g_heartbeat;

static void task_shell(void *arg)
{
    (void)arg;
    for (;;) {
        shell_poll_uart();
        /* 合并刷新（2026-09-26）：con_line 只标脏，屏幕在这里按 CON_FLUSH_INTERVAL_MS 节流批量更新。
         * con_tick 自己带节流，多调几次不会多刷。
         * ⚠ 轮询周期 2026-09-27 由 20ms 降到 **2ms**：按键延迟实测 1-2s，而这里 20ms + 刷新节流 50ms
         * 都是**虚拟时间**，虚拟机整体只跑到标称 ~25% ⇒ 实际被放大 4 倍。把轮询压到 2ms、
         * 节流压到 20ms（见 console.h），键盘回显的虚拟延迟就从 70ms 降到 22ms。 */
        con_tick();
        vTaskDelay(pdMS_TO_TICKS(2));
    }
}

static void task_con(void *arg)
{
    (void)arg;
    const int heartbeatMs = hal_config_int("heartbeat.ms", 1000);
    const int logTick = hal_config_bool("console.tick_log", 1);

    /* 等心跳先动起来（条件写反过一次：写成 != 0 ⇒ g_heartbeat 只增不减，
     * 于是这条循环永远不退出，"console ready." 与周期 tick 日志从来没出现过）。 */
    while (g_heartbeat == 0) {
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    hal_uart_puts("console ready.\r\n");

    for (;;) {
        con_tick();                     /* 合并刷新：con_line 只标脏，屏幕在这里批量更新 */
        if (logTick && (g_heartbeat % 10u) == 0u) {
            hal_uart_puts("tick ");
            hal_uart_putu(g_heartbeat);
            hal_uart_puts("\r\n");
        }
        vTaskDelay(pdMS_TO_TICKS(heartbeatMs));
    }
}

static void task_beat(void *arg)
{
    (void)arg;
    for (;;) {
        g_heartbeat++;
        REG(0) = g_heartbeat;
        REG(1) = (uint32_t)xTaskGetTickCount();
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

int main(void)
{
    hal_uart_puts("\r\n[Cryptand OS] booting (1) Cryptand OS: FreeRTOS + shell)...\r\n");
    hal_install_trap_vector();          /* ⚠ 必须在调度器之前：FreeRTOS 的 ecall/中断都靠它 */
    hal_config_init();                  /* 上电读配置块（改 config/cryptand/cryptand-os.cfg + 重启即生效） */

    /* 字符控制台 + 类 Lua 命令行 */
    con_init(1, 1);
    con_line(hal_config_get("system.banner", "Cryptand OS 0.1"));
    /* 横幅同时进 UART：屏幕给玩家看，UART 给日志 / 无人化测试看 */
    hal_uart_puts("[Cryptand OS] banner: ");
    hal_uart_puts(hal_config_get("system.banner", "Cryptand OS 0.1"));
    hal_uart_puts("\r\n");
    hal_uart_puts("[Cryptand OS] config: ");
    hal_uart_puts(hal_config_present() ? "loaded" : "defaults");
    hal_uart_puts("\r\n");
    /* ★ 模块化：按清单初始化模块（命令由模块自己注册）；模块名会打到 UART。
     *   必须在 shell_init() 之前 —— 命令表要在 shell 首次解析前就位。 */
    modules_init(OS_MODULES, OS_MODULES_COUNT);

    hal_uart_puts("[Cryptand OS] shell ready (type 'help')\r\n");
    shell_init();

    /* GPU 桥自检：屏幕能不能出画面，全看宿主有没有把组件调用兑现。
     * 结果同时进 UART —— 无人化测试靠这一行判断"桥通了没有"（纯 ASCII，方便脚本断言）。 */
    {
        const int gpu = gpu_set(1u, 1u, "");
        hal_uart_puts("[Cryptand OS] gpu bridge: ");
        hal_uart_puts(gpu >= 0 ? "ONLINE" : "OFFLINE");
        hal_uart_puts("\r\n");
    }

    shell_exec_line("version");
    shell_exec_line("sysinfo");

    /* ---- 文件系统自检（盘走 OC：盘是 OC 组件表里的一项，句柄由宿主注入启动参数块）----
     * 无盘时 fs_disk_handle() = -1、所有 fs_* 返回 -OC_ERR_UNKNOWN_COMPONENT ⇒ 这里打 SKIP，
     * 绝不把"没插盘"误报成故障（也绝不静默）。 */
    {
        char rd[64];
        int n;
        hal_uart_puts("[FS] disk handle = ");
        hal_uart_putu((uint32_t)fs_disk_handle());
        hal_uart_puts("\r\n");
        if (fs_disk_handle() >= 0) {
            hal_uart_puts("[FS] mkdir /home -> ");
            hal_uart_putu((uint32_t)fs_mkdir("/home"));
            hal_uart_puts("\r\n");

            const int h = fs_open("/home/selftest.txt", OC_FS_MODE_W);
            hal_uart_puts("[FS] open(w) -> ");
            hal_uart_putu((uint32_t)h);
            hal_uart_puts("\r\n");
            if (h > 0) {
                const char *msg = "hello fs";
                hal_uart_puts("[FS] write -> ");
                hal_uart_putu((uint32_t)fs_write(h, msg, 8u));
                hal_uart_puts("\r\n");
                fs_close(h);

                const int h2 = fs_open("/home/selftest.txt", OC_FS_MODE_R);
                n = fs_read(h2, rd, sizeof(rd));
                hal_uart_puts("[FS] read -> ");
                hal_uart_putu((uint32_t)n);
                hal_uart_puts(": ");
                if (n > 0) {
                    rd[n < 63 ? n : 63] = 0;
                    hal_uart_puts(rd);
                }
                hal_uart_puts("\r\n");
                fs_close(h2);

                hal_uart_puts("[FS] size -> ");
                hal_uart_putu((uint32_t)fs_size("/home/selftest.txt"));
                hal_uart_puts("\r\n");
            } else {
                /* ⚠ 系统层一律英文（用户 2026-09-25："尽量都是英文系统，不要出现中文"）；
                 *   中文只允许出现在 UI 系统（cryptand-ui-os，可切换语言）与源码注释里。 */
                hal_uart_puts("[FS] open failed (see the negative errcode above)\r\n");
            }
        } else {
            hal_uart_puts("[FS] SKIP (no disk attached)\r\n");
        }
    }

    /* ---- 显示后端自检（显存窗口由宿主经配置块注入）----
     * 用户定案："刷新的话可以使用 GPU，如果没有 GPU 则 CPU 手动刷新"。
     * 固件这里只做两件事：写显存窗口（**写内存 = 写显存**，零拷贝）、门铃 +1；
     * 实际用哪个后端由宿主回写到 backend 字段，固件读出来打 UART（无人化据此断言）。 */
    {
        /* 拿显示窗口只有这一条路（基址/容量由宿主经配置块注入，见 display.c 的 disp_auto_init）。
         * 字符三平面的那一帧仍是这台机器的**文字**通路；"程序直接画图像"那条（format=RGB565）
         * 由 draw 命令 / 其它程序按同一份窗口协议写（display.h 的图形帧 API）。 */
        const int dispOk = disp_auto_init();

        hal_uart_puts("[DISP] window init -> ");
        if (dispOk == 0) {
            hal_uart_puts("OK\r\n");
            disp_text_begin();               /* 这一帧是字符帧：把尺寸/format 声明清楚 */
            disp_putc(0, 0, 'C');
            disp_putc(1, 0, 'V');
            /* 三平面的颜色是 **OC 调色板索引**（0 = white … 0xF = black，见 OpenOS 的
             * lib/colors.lua；宿主按 palette=true 解释，见 OcComponentBus.paintRows）——
             * 写成 VGA 那种"0 黑 15 白"的顺序就是黑底黑字。 */
            disp_color(0, 0, 0x00u, 0x0Fu);
            hal_uart_puts("[DISP] presented seq=");
            hal_uart_putu(disp_present());
            hal_uart_puts(" backend=");
            hal_uart_putu((uint32_t)disp_backend());
            hal_uart_puts("\r\n");
        } else {
            hal_uart_puts("NO-WINDOW (host did not provide disp.base)\r\n");
        }
    }

    if (xTaskCreate(task_shell, "shell", 512, NULL, 2, NULL) != pdPASS) {
        hal_uart_puts("[FATAL] cannot create shell task\r\n");
        return 1;
    }
    if (xTaskCreate(task_beat, "beat", 256, NULL, 1, NULL) != pdPASS) {
        hal_uart_puts("[FATAL] cannot create beat task\r\n");
        return 1;
    }
    if (xTaskCreate(task_con, "con", 384, NULL, 1, NULL) != pdPASS) {
        hal_uart_puts("[FATAL] cannot create console task\r\n");
        return 1;
    }

    hal_uart_puts("[Cryptand OS] starting scheduler\r\n");
    vTaskStartScheduler();

    hal_uart_puts("[FATAL] scheduler returned\r\n");
    return 1;
}
