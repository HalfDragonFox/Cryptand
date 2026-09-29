/*
 * GPU / 显示自检命令模块（cmd_gpu.c）—— 从 common/console.c 搬出来（库化 OS，2026-09-18）。
 *
 * 它验证的是"宿主有没有把组件调用兑现"这条通路，所以属于**系统功能**而不是 shell 本身。
 *
 * ★ 2026-09-27 任务 H：补上"**程序直接画图像**"的那个生产者。
 *   用户定案（ai_memory/repo/gpu-auto-convert-output-2026-09-27.md）：程序只画图像，
 *   转字符还是直接画由虚拟机（GPU/屏链路）按目标屏能力**自动决定**。固件这一侧只负责
 *   把"我画的是图像"如实写进显存窗口（format = RGB565，见 display.h 的图形帧 API）；
 *   于是**同一条命令**：
 *     · 目标是真彩屏   ⇒ 宿主直接写像素进 VRAM（直画）；
 *     · 目标是字符屏   ⇒ 宿主自动量化成字符格 + 前/后景色（转字符）。
 *   固件里**没有**"如果是字符屏就改画字符"这条退路 —— 那正是定案要消灭的第二条路。
 */
#include "hal.h"
#include "console.h"
#include "display.h"
#include "module.h"

/* ---- 演示图像：8 格 x 6 格，每格 8x8 像素（与设备的一格同宽，见 TrueColorScreen.CELL）----
 * 64x48 像素 x 2 字节 + 32 字节控制块 = 6176 字节，装得进 8KB 显存窗口（OcBoardLayout.VRAM_BYTES）。
 * 每格一个纯色：字符屏上量化后就是 8x6 的纯色块阵列（可辨认的彩条/测试图），
 * 真彩屏上就是同一张图的像素形态 —— 两边看到的是同一份图像。 */
#define DRAW_CELL    8u
#define DRAW_COLS    8u
#define DRAW_ROWS    6u
#define DRAW_W       (DRAW_COLS * DRAW_CELL)
#define DRAW_H       (DRAW_ROWS * DRAW_CELL)

/*
 * 16 色调色板：**OpenComputers 原版默认调色板**
 * （.ai_cache/OpenComputers/src/main/scala/li/cil/oc/util/PackedColor.scala:108-112 的
 *  MutablePaletteFormat.palette —— 原位复制，顺序即索引）。
 * 为什么用它的颜色值：字符屏量化时按"就近色"归索引（ScreenImageQuantizer），
 * 图像里用的就是屏自己的调色板色 ⇒ 每格恰好落到唯一索引，量化结果确定、可断言。
 * 真彩屏那一支不看调色板（RGB565 直色），所以这份表只影响字符屏那一支。
 */
static const uint32_t DRAW_PALETTE[16] = {
    0xFFFFFFu, 0xFFCC33u, 0xCC66CCu, 0x6699FFu,
    0xFFFF33u, 0x33CC33u, 0xFF6699u, 0x333333u,
    0xCCCCCCu, 0x336699u, 0x9933CCu, 0x333399u,
    0x663300u, 0x336600u, 0xFF3333u, 0x000000u
};

/* RGB888 -> RGB565（5-6-5）。小端字节序由 disp_image_put 负责（与真彩设备 VRAM 布局一致）。 */
static uint16_t rgb565(uint32_t rgb)
{
    return (uint16_t)((((rgb >> 16) & 0xF8u) << 8)
                    | (((rgb >> 8) & 0xFCu) << 3)
                    | ((rgb >> 3) & 0x1Fu));
}

/**
 * draw —— 画一帧**图像**（图形帧）并上屏。真彩屏直画像素，字符屏由宿主自动转字符。
 *
 * 顺序很关键：先把这一行状态刷成**字符帧**（玩家看得见"命令跑了"），再写图像帧；
 * 图像帧之后屏幕归它，直到玩家按下一个键（见 console.c 的 shell_feed / con_flush）。
 */
static void cmd_draw(int argc, char **argv)
{
    (void)argc;
    (void)argv;

    if (!disp_ready()) {
        con_line("draw: no display window (host did not provide disp.base)");
        LOG("[DISP] image frame: NO-WINDOW (host did not provide disp.base)\r\n");
        return;
    }

    con_puts("draw: image frame ");
    con_putu(DRAW_W);
    con_putc('x');
    con_putu(DRAW_H);
    con_line(" RGB565 (same frame: pixels on a true-color screen, characters on a char screen)");
    con_flush();                    /* 状态行先上屏（这一帧还是字符帧） */

    if (disp_image_begin(DRAW_W, DRAW_H) != 0) {
        /* 容量不够就明确报出来（需要多少字节也说清），绝不截断成"半张图" */
        con_line("draw: display window is too small for this image frame");
        LOG("[DISP] image frame: WINDOW-TOO-SMALL need=%u bytes\r\n",
            (uint32_t)(DISP_HEADER_BYTES + DRAW_W * DRAW_H * 2u));
        return;
    }

    for (uint32_t row = 0; row < DRAW_ROWS; row++) {
        for (uint32_t col = 0; col < DRAW_COLS; col++) {
            const uint32_t index = (row * DRAW_COLS + col) % 16u;
            disp_image_fill(col * DRAW_CELL, row * DRAW_CELL, DRAW_CELL, DRAW_CELL,
                            rgb565(DRAW_PALETTE[index]));
        }
    }

    const uint32_t seq = disp_image_present();

    /* 无人化断言就看这一行：format=1 = 图形帧（RGB565），pixels/bytes 说明数据面大小。
     * ⚠ 后端（宿主用 GPU 还是 CPU、直画还是转字符）是**宿主扫描之后**才回写的，这里读到的
     *   一定是上一帧的值 ⇒ 不打它，免得误导；那个决定在宿主日志里（"图形面直接画像素"/
     *   "图形面自动转字符"）。 */
    LOG("[DISP] image frame %ux%u format=%u pixels=%u bytes=%u seq=%u\r\n",
        (uint32_t)DRAW_W, (uint32_t)DRAW_H, (uint32_t)DISP_FORMAT_RGB565,
        (uint32_t)(DRAW_W * DRAW_H), (uint32_t)(DRAW_W * DRAW_H * 2u), seq);
}

static void cmd_gpu(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    const int r = gpu_set(1u, 1u, "");
    con_puts("gpu.set() -> ");
    if (r < 0) {
        con_line("no response (component bridge not wired yet)");
    } else {
        con_puts("ok, results=");
        con_putu((uint32_t)r);
        con_putc('\n');
    }
}

static const shell_cmd GPU_COMMANDS[] = {
    { "gpu",  "GPU component self-check", cmd_gpu },
    /* 图形帧生产者：真彩屏直画像素 / 字符屏由宿主自动转字符（同一份命令，不分支） */
    { "draw", "draw an image frame (RGB565 VRAM window): pixels on a true-color screen, auto-converted characters on a char screen", cmd_draw },
};

const cryptand_module GPU_COMMAND_MODULE = {
    "gpu-commands",
    GPU_COMMANDS,
    (int)(sizeof(GPU_COMMANDS) / sizeof(GPU_COMMANDS[0])),
    0,
};
