/*
 * ============================================================================
 * Cryptand UI OS 的显示移植层（lv_port_disp.c，2026-09-18 升级）
 *
 * 职责：把 LVGL 渲染出的像素量化成 **OC 字符屏能表达的东西** ——
 * 每个 2×2 像素块 → 一个字符格，用「**形状 + 16 色前景/背景**」表达（ANSI art 原理），
 * 而不是过去那种"只有 5 档灰度、丢掉颜色"的做法。
 *
 * 上屏**只有一条路**：**显存窗口**（宿主注入 disp.base/disp.bytes）——写内存就是写显存
 * （三平面 code/fg/bg），门铃 +1 交给宿主扫描上屏（有 GPU 的宿主走"显存页 + 一次 blit"）。
 * 2026-09-28 删掉"没有窗口退回 gpu_blit"的第二条路：OC 的组件调用必须回主线程兑现
 * （每 tick 一次的同步往返），一次刷新就能把本任务的输入按住几百毫秒 —— 键盘延迟的根因。
 * 没有窗口就是没有显示后端，启动时用 UART 报一次（见 main.c），不静默、也不回落。
 *
 * 屏幕：160×50 像素 = 80×25 字符格（全屏）；每 2×2 像素 → 一格。
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"

#include "lvgl.h"
#include "hal.h"
#include "display.h"

#define DISP_COLS        80
#define DISP_ROWS        25
#define DISP_HOR_RES     (DISP_COLS * 2)     /* 160 */
#define DISP_VER_RES     (DISP_ROWS * 2)     /* 50  */

/** 覆盖率字符（0..4 个亮像素） */
static const char SHADES[5] = { ' ', 0xB0, 0xB1, 0xB2, 0xDB };

/** OC 调色板（**索引顺序必须是 OC 的**：0 = white … 0xF = black，见 OpenOS 的
 *  lib/colors.lua）。RGB 取 MC 染料色 —— 这张表只用于"像素色 → 索引"的就近匹配；
 *  索引最终由 OC 侧按它自己的调色板解析，所以顺序错一位颜色就整体错位。 */
static const uint8_t PAL16[16][3] = {
    { 249, 255, 254 }, { 249, 128,  29 }, { 199,  78, 189 }, {  58, 179, 218 },
    { 254, 216,  61 }, { 128, 199,  31 }, { 243, 139, 170 }, {  71,  79,  82 },
    { 157, 157, 151 }, {  22, 156, 156 }, { 137,  50, 184 }, {  60,  68, 170 },
    { 131,  84,  52 }, {  94, 124,  22 }, { 176,  46,  38 }, {  29,  29,  33 },
};

/*
 * 双缓冲（用户 2026-09-18："lvgl 底层最好使用双层刷新"）：两块 draw buffer 交替，
 * LVGL 渲染 A 的同时 B 正在被量化 —— 直接收益是不撕裂，结构上为异步就绪。
 */
#define DRAW_BUF_PX  (DISP_HOR_RES * 5)
static uint8_t drawBufA[DRAW_BUF_PX * 2];
static uint8_t drawBufB[DRAW_BUF_PX * 2];

/** 量化结果：字符 + 前景 + 背景（三平面，正好对应显存窗口的布局） */
static uint8_t cellBuf[DISP_COLS * DISP_ROWS];
static uint8_t fgBuf[DISP_COLS * DISP_ROWS];
static uint8_t bgBuf[DISP_COLS * DISP_ROWS];

static lv_display_t *disp;

/** RGB888 → 最近的 16 色调色板索引（欧氏距离，够快也够准） */
static uint8_t nearest16(uint32_t r, uint32_t g, uint32_t b)
{
    uint32_t best = 0xFFFFFFFFu;
    uint8_t bestIndex = 0;
    for (uint8_t i = 0; i < 16u; i++) {
        const int32_t dr = (int32_t)r - (int32_t)PAL16[i][0];
        const int32_t dg = (int32_t)g - (int32_t)PAL16[i][1];
        const int32_t db = (int32_t)b - (int32_t)PAL16[i][2];
        const uint32_t d = (uint32_t)(dr * dr + dg * dg + db * db);
        if (d < best) {
            best = d;
            bestIndex = i;
        }
    }
    return bestIndex;
}

/** 一次上屏：写显存窗口三平面 + 门铃（**唯一路径**，没有 gpu_blit 回落） */
static void present(void)
{
    /* 屏幕归属（2026-09-27 任务 H，与 console.c 的 con_flush 同一条规则）：
     * 图形帧（draw 画的图像，format=RGB565）占着屏幕时不刷字符面，否则 LVGL 这一帧
     * 会立刻把图像盖掉；玩家的第一个按键（shell_feed）把屏幕交还字符面。 */
    if (disp_screen_is_image()) {
        return;
    }
    if (!disp_ready()) {
        return;                         /* 没有窗口 = 没有后端（main.c 启动时已用 UART 报过） */
    }
    disp_text_begin();                  /* 这一帧是字符帧：尺寸/format 必须声明清楚（上一次可能是图形帧） */
    for (int y = 0; y < DISP_ROWS; y++) {
        for (int x = 0; x < DISP_COLS; x++) {
            const int at = y * DISP_COLS + x;
            disp_putc(x, y, cellBuf[at]);
            disp_color(x, y, fgBuf[at], bgBuf[at]);
        }
    }
    disp_present();                     /* 门铃 +1：宿主扫描窗口后上屏 */
}

static void disp_flush(lv_display_t *d, const lv_area_t *area, uint8_t *pxMap)
{
    const int32_t w = lv_area_get_width(area);
    const int32_t h = lv_area_get_height(area);
    lv_color16_t *px = (lv_color16_t *)pxMap;

    for (int32_t y = 0; y < h; y++) {
        for (int32_t x = 0; x < w; x++) {
            const int32_t gx = area->x1 + x;
            const int32_t gy = area->y1 + y;
            if ((gx & 1) != 0 || (gy & 1) != 0) {
                continue;               /* 只在每个 2×2 块的左上角处理一次 */
            }
            const int32_t col = gx >> 1;
            const int32_t row = gy >> 1;
            if (col < 0 || col >= DISP_COLS || row < 0 || row >= DISP_ROWS) {
                continue;
            }

            /* 取 2×2 的四个像素（越界的用块内第一个补齐，避免读越界） */
            uint32_t lum[4];
            uint32_t rr[4];
            uint32_t gg[4];
            uint32_t bb[4];
            const int32_t ox[4] = { 0, 1, 0, 1 };
            const int32_t oy[4] = { 0, 0, 1, 1 };
            for (int k = 0; k < 4; k++) {
                int32_t sx = x + ox[k];
                int32_t sy = y + oy[k];
                if (sx >= w) {
                    sx = x;
                }
                if (sy >= h) {
                    sy = y;
                }
                const lv_color16_t c = px[sy * w + sx];
                const uint32_t r = (c.red * 255u) / 31u;
                const uint32_t g = (c.green * 255u) / 63u;
                const uint32_t b = (c.blue * 255u) / 31u;
                rr[k] = r;
                gg[k] = g;
                bb[k] = b;
                lum[k] = (r * 77u + g * 151u + b * 28u) >> 8;
            }

            /* 最亮 = 前景色，最暗 = 背景色，覆盖率 = 亮像素占比（形状） */
            int hi = 0;
            int lo = 0;
            for (int k = 1; k < 4; k++) {
                if (lum[k] > lum[hi]) {
                    hi = k;
                }
                if (lum[k] < lum[lo]) {
                    lo = k;
                }
            }
            const uint32_t mid = (lum[hi] + lum[lo]) / 2u;
            int bright = 0;
            for (int k = 0; k < 4; k++) {
                if (lum[k] >= mid) {
                    bright++;
                }
            }
            const int at = row * DISP_COLS + col;
            cellBuf[at] = (uint8_t)SHADES[bright];
            fgBuf[at] = nearest16(rr[hi], gg[hi], bb[hi]);
            bgBuf[at] = nearest16(rr[lo], gg[lo], bb[lo]);
        }
    }

    if (lv_display_flush_is_last(d)) {
        present();
    }
    lv_display_flush_ready(d);
}

void lv_port_disp_init(void)
{
    disp = lv_display_create(DISP_HOR_RES, DISP_VER_RES);
    lv_display_set_flush_cb(disp, disp_flush);
    /* 两块缓冲都交给 LVGL（双缓冲）；渲染模式仍是 PARTIAL（字符屏按块量化） */
    lv_display_set_buffers(disp, drawBufA, drawBufB, sizeof(drawBufA), LV_DISPLAY_RENDER_MODE_PARTIAL);
}
