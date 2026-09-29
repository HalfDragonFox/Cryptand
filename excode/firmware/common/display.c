/*
 * 显示后端实现（display.c）—— 设计说明见 display.h。
 *
 * 关键点：**写内存就是写显存**。disp_putc/disp_color 直接改窗口里的三个平面，
 * 没有中间帧缓冲、没有逐字符的组件调用；上屏只是把门铃加一。
 * 宿主侧（Java）用 DisplayWindow.Scanner 比对门铃后一次性取整屏，再按能力上屏。
 *
 * 两条数据流（2026-09-27 任务 H 补上第二条）—— 控制块的 format 字段说明"程序画了什么"：
 *   · 字符帧（format = DISP_FORMAT_TEXT）  ：code/fg/bg 三平面（disp_putc / disp_color）；
 *   · 图形帧（format = DISP_FORMAT_RGB565）：像素（disp_image_begin/put/fill/present）。
 * 两条**各有各的写入函数**，窗口只有一块 —— 谁上屏谁先把 format/尺寸写清楚（begin）。
 */
#include <stdint.h>

#include "display.h"
#include "hal.h"        /* hal_config_int / hal_config_get：窗口基址由宿主经配置块注入 */

static volatile uint8_t *g_win;
static uint32_t g_bytes;        /* 窗口容量（图形帧要按它校验装不装得下） */
static uint32_t g_cols;         /* **字符格**列数（disp_putc/disp_color 用；图形帧不改它） */
static uint32_t g_rows;
static uint32_t g_plane;
static uint32_t g_seq;

/* ---- 图形帧（"程序直接画图像"）---- */
static uint32_t g_img_w;        /* 当前图形帧的像素宽（disp_image_begin 之后有效） */
static uint32_t g_img_h;
static int      g_img_mode;     /* 1 = 窗口现在描述的是图形帧 */

/* ---- 屏幕归属（见 display.h 的说明）---- */
static int g_img_holds_screen;

static inline uint32_t rd32(uint32_t off)
{
    return *(volatile uint32_t *)(g_win + off);
}

static inline void wr32(uint32_t off, uint32_t value)
{
    *(volatile uint32_t *)(g_win + off) = value;
}

int disp_init(uint32_t base, uint32_t bytes, uint32_t cols, uint32_t rows)
{
    const uint32_t need = DISP_HEADER_BYTES + 3u * cols * rows;
    if (base == 0u || bytes < need || cols == 0u || rows == 0u) {
        g_win = 0;
        return -1;
    }
    g_win = (volatile uint8_t *)base;
    g_bytes = bytes;
    g_cols = cols;
    g_rows = rows;
    g_plane = cols * rows;
    g_seq = 0u;
    g_img_w = 0u;
    g_img_h = 0u;
    g_img_mode = 0;
    g_img_holds_screen = 0;
    wr32(DISP_OFF_MAGIC, DISP_MAGIC);
    wr32(DISP_OFF_COLS, cols);
    wr32(DISP_OFF_ROWS, rows);
    wr32(DISP_OFF_FORMAT, DISP_FORMAT_TEXT);
    wr32(DISP_OFF_FLAGS, DISP_FLAG_ALLOW_GPU);
    wr32(DISP_OFF_FRAME_SEQ, 0u);
    /* 三个平面一次清干净：字符格留空、前景/背景给默认色。
     * ⚠ 不给默认色的后果是**白底**：宿主把平面当调色板索引解释（0 = white），
     *   背景平面全 0 ⇒ 白底白字。这里写 code=' ' / fg=white / bg=black。 */
    for (uint32_t i = 0; i < g_plane; i++) {
        g_win[DISP_HEADER_BYTES + i] = (uint8_t)' ';
        g_win[DISP_HEADER_BYTES + g_plane + i] = (uint8_t)DISP_FG_DEFAULT;
        g_win[DISP_HEADER_BYTES + 2u * g_plane + i] = (uint8_t)DISP_BG_DEFAULT;
    }
    return 0;
}

/*
 * 接管宿主注入的显存窗口：**所有系统拿显示窗口只有这一条路**（基址/容量都在配置块里，
 * 固件不硬编码地址）。失败返回 -1，调用方必须把它报出来（不许静默黑屏）。
 */
int disp_auto_init(void)
{
    const uint32_t base = (uint32_t)hal_config_int("disp.base", 0);
    const uint32_t bytes = (uint32_t)hal_config_int("disp.bytes", 0);
    return disp_init(base, bytes, 80u, 25u);
}

/*
 * 控制台要的那条路：**按字符格数**接管宿主注入的窗口。
 * 与 disp_auto_init 的区别只有尺寸来源 —— 那个是写死的 80x25，只适合图形帧；
 * 控制台的格数是问屏得到的（con_init），窗口必须与它逐格对齐。
 */
int disp_init_sized(uint32_t cols, uint32_t rows)
{
    const uint32_t base = (uint32_t)hal_config_int("disp.base", 0);
    const uint32_t bytes = (uint32_t)hal_config_int("disp.bytes", 0);
    return disp_init(base, bytes, cols, rows);
}

int disp_ready(void)
{
    return g_win != 0 ? 1 : 0;
}

int disp_backend(void)
{
    if (g_win == 0) {
        return DISP_BACKEND_UNKNOWN;
    }
    return (int)rd32(DISP_OFF_BACKEND);
}

uint32_t disp_cols(void)
{
    return g_cols;
}

uint32_t disp_rows(void)
{
    return g_rows;
}

void disp_putc(int x, int y, unsigned char ch)
{
    if (g_win == 0 || x < 0 || y < 0 || (uint32_t)x >= g_cols || (uint32_t)y >= g_rows) {
        return;     /* 越界不写（调用方自己保证；这里不静默改别处的内容） */
    }
    g_win[DISP_HEADER_BYTES + (uint32_t)y * g_cols + (uint32_t)x] = ch;
}

void disp_color(int x, int y, unsigned char fg, unsigned char bg)
{
    if (g_win == 0 || x < 0 || y < 0 || (uint32_t)x >= g_cols || (uint32_t)y >= g_rows) {
        return;
    }
    const uint32_t at = (uint32_t)y * g_cols + (uint32_t)x;
    g_win[DISP_HEADER_BYTES + g_plane + at] = fg;           /* 前景平面 */
    g_win[DISP_HEADER_BYTES + 2u * g_plane + at] = bg;      /* 背景平面 */
}

uint32_t disp_present(void)
{
    if (g_win == 0) {
        return 0u;
    }
    g_seq++;
    wr32(DISP_OFF_FRAME_SEQ, g_seq);        /* 门铃：宿主据此知道有新帧 */
    return g_seq;
}

/* =====================================================================
 * 图形帧 —— "程序直接画图像"（2026-09-27 任务 H）
 *
 * 协议依据（宿主实际接受的字节，全部来自 common 的 DisplayWindow.java）：
 *   · 控制块 OFF_COLS/OFF_ROWS 在图形帧里是**像素**宽高（parseImage :179-180）；
 *   · 一格 == 2 字节，紧跟在 0x20 控制块之后，**整块就是 width*height*2 字节**
 *     （rgb565WindowBytes :68-73 / parseImage :189-190）；
 *   · 像素**小端**：与真彩设备 16bpp VRAM 逐字节一致（ImageFrame 的文档 :129-132）。
 * 于是固件只需：写控制块（尺寸 + format）→ 写像素 → 门铃 +1。零转换、零组件调用。
 * ===================================================================== */

void disp_text_begin(void)
{
    if (g_win == 0) {
        return;
    }
    g_img_mode = 0;
    wr32(DISP_OFF_COLS, g_cols);            /* 尺寸字段回到字符格 */
    wr32(DISP_OFF_ROWS, g_rows);
    wr32(DISP_OFF_FORMAT, DISP_FORMAT_TEXT);
}

int disp_image_begin(uint32_t width, uint32_t height)
{
    if (g_win == 0 || width == 0u || height == 0u) {
        return -1;
    }
    /* 容量校验走**宿主那一条算式**（DisplayWindow.rgb565WindowBytes）：窗口装不下就明确拒绝，
     * 绝不截断 —— 截断在宿主看来是一帧"尺寸对但内容半截"的真彩帧，最难查。 */
    if (DISP_HEADER_BYTES + width * height * 2u > g_bytes) {
        return -1;
    }
    g_img_w = width;
    g_img_h = height;
    g_img_mode = 1;
    wr32(DISP_OFF_COLS, width);             /* 图形帧里这两个字段是像素宽高 */
    wr32(DISP_OFF_ROWS, height);
    wr32(DISP_OFF_FORMAT, DISP_FORMAT_RGB565);
    return 0;
}

void disp_image_put(uint32_t x, uint32_t y, uint16_t rgb565)
{
    if (g_win == 0 || g_img_mode == 0 || x >= g_img_w || y >= g_img_h) {
        return;     /* 越界不写（与 disp_putc 同一纪律：不静默改别处的内容） */
    }
    const uint32_t at = DISP_HEADER_BYTES + 2u * (y * g_img_w + x);
    g_win[at] = (uint8_t)(rgb565 & 0xFFu);          /* 小端：低字节在前 */
    g_win[at + 1u] = (uint8_t)(rgb565 >> 8);
}

void disp_image_fill(uint32_t x, uint32_t y, uint32_t w, uint32_t h, uint16_t rgb565)
{
    if (g_img_mode == 0) {
        return;
    }
    for (uint32_t row = 0; row < h; row++) {
        for (uint32_t col = 0; col < w; col++) {
            disp_image_put(x + col, y + row, rgb565);
        }
    }
}

uint32_t disp_text_present(void)
{
    disp_text_begin();          /* 先声明"这一帧是字符帧"，再打门铃（顺序不能反） */
    return disp_present();
}

uint32_t disp_image_present(void)
{
    const uint32_t seq = disp_present();
    if (seq != 0u) {
        g_img_holds_screen = 1;             /* 屏幕归图形帧（字符面在 release 之前不刷） */
    }
    return seq;
}

int disp_screen_is_image(void)
{
    return g_img_holds_screen;
}

void disp_screen_release(void)
{
    g_img_holds_screen = 0;
}
