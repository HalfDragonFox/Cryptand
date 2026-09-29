/*
 * =====================================================================
 * Cryptand firmware display backend (display.h, 2026-09-18)
 *
 * 用户定案："刷新的话可以使用 GPU，如果没有 GPU 则 CPU 手动刷新"。
 *
 * 三层分工（外设层 -> 模块接口层 -> 内核层）里，本文件是**内核侧唯一碰显示的地方**，
 * 它只做两件事：
 *   1. 往显存窗口写字符与颜色（code / fg / bg 三平面）——**写内存就是写显存**（零拷贝）；
 *   2. 上屏 = 门铃 +1（frame_seq++），宿主扫描窗口后按后端上屏。
 *
 * ⚠ 后端**不在固件里猜**：宿主把实际使用的后端回写到控制块 backend 字段，固件读它决定
 * 要不要自己做 CPU 手动刷新。窗口基址由**宿主经配置块注入**（disp.base / disp.bytes），
 * 固件不硬编码地址。
 *
 * 控制块布局必须与 common 的 DisplayWindow 完全一致（那里是 Java 侧的单一来源）。
 * =====================================================================
 */
#ifndef CRYPTAND_DISPLAY_H
#define CRYPTAND_DISPLAY_H

#include <stdint.h>

/* 后端：宿主回写（0 = 还没人扫过 / 宿主没接显示） */
#define DISP_BACKEND_UNKNOWN 0
#define DISP_BACKEND_CPU     1
#define DISP_BACKEND_GPU     2

/* 控制块偏移（与 common/.../board/DisplayWindow.java 一一对应） */
#define DISP_OFF_MAGIC      0x00u
#define DISP_OFF_COLS       0x04u
#define DISP_OFF_ROWS       0x08u
#define DISP_OFF_FRAME_SEQ  0x0Cu
#define DISP_OFF_DIRTY      0x10u
#define DISP_OFF_FORMAT     0x14u
#define DISP_OFF_BACKEND    0x18u
#define DISP_OFF_FLAGS      0x1Cu
#define DISP_HEADER_BYTES   0x20u

#define DISP_MAGIC          0x4356524Du     /* 'CVRM' */
#define DISP_FORMAT_TEXT    0u
/* 图形帧（"程序直接画图像"，2026-09-27 任务 H）：**与 common 的 DisplayWindow.FORMAT_RGB565 同值**。
 * 字节序也是那边定的：每像素 2 字节**小端** = 真彩屏 16bpp VRAM 布局（那边整块 arraycopy，零转换）。
 * 宿主按这个字段决定怎么解释数据面（DisplayWindow.formatOf / parseImage）。 */
#define DISP_FORMAT_RGB565  1u
#define DISP_FLAG_ALLOW_GPU 1u

/* 字符帧的默认色 —— **OC 调色板索引**，不是 RGB（0 = white，0x0F = black，
 * 与 OpenOS 的 lib/colors.lua 同表）。宿主把三平面的颜色当调色板索引解释
 * （OcComponentBus.paintRows 传 palette=true），给 RGB 会被当成 0x00000F 那种深色。 */
#define DISP_FG_DEFAULT     0x00u
#define DISP_BG_DEFAULT     0x0Fu

/*
 * 初始化：接管宿主注入的显存窗口。
 *
 * @param base  窗口基址（0 = 宿主没提供 ⇒ 本机没有显存窗口）
 * @param bytes 窗口容量
 * @param cols/rows 字符格数
 * @return 0 = 成功；-1 = 没有窗口或容量不够（调用方应明确报出来，别静默黑屏）
 */
int disp_init(uint32_t base, uint32_t bytes, uint32_t cols, uint32_t rows);

/*
 * 按**字符格数**接管宿主注入的窗口（基址/容量仍从配置块读 disp.base / disp.bytes）。
 *
 * ⚠ 控制台必须走这条：窗口就是屏幕的字节镜像，格数由 con_init 从屏查询得到，
 *   两者不一致 ⇒ 行列整体错位（不是"少画一点"）。窗口容量不够返回 -1（调用方要报出来）。
 */
int disp_init_sized(uint32_t cols, uint32_t rows);

/* 有没有可用的显存窗口 */
int disp_ready(void);

/* 当前后端（宿主回写；UNKNOWN = 还没人扫过） */
int disp_backend(void);

uint32_t disp_cols(void);
uint32_t disp_rows(void);

/* 写一个字符 / 设一格的前景与背景色（16 色索引）——直接写显存平面，无中间缓冲 */
void disp_putc(int x, int y, unsigned char ch);
void disp_color(int x, int y, unsigned char fg, unsigned char bg);

/* 上屏：门铃 +1；返回新的帧号（宿主扫描后会上屏，并按能力回写 backend） */
uint32_t disp_present(void);

/*
 * **字符帧上屏**：声明"这一帧是字符三平面"（尺寸回到字符格、format = TEXT）再打门铃。
 *
 * ⚠ 每次都要声明：窗口只有一块，上一次可能是图形帧（disp_image_*）。
 * 写字符面之前调它，宿主才会按字符解释那 3 个平面。
 */
uint32_t disp_text_present(void);

/* =====================================================================
 * 图形帧 —— "程序直接画图像"（2026-09-27 任务 H，定案见
 * ai_memory/repo/gpu-auto-convert-output-2026-09-27.md）
 *
 * 用户定案：**程序侧只有一种输出：画图像**；至于这一帧最终是"直接画像素"还是
 * "转成字符"，由虚拟机（GPU/屏链路）按目标屏的能力**自动决定** —— 固件不管、也没有开关。
 * 固件这一侧只有一个义务：**把"我画的是图像"这件事如实写进窗口**（format = RGB565），
 * 宿主的 DisplayWindow.parseImage + ScreenOutputFace.derive 才能做出那个决定。
 *
 * 用法（一帧三步）：
 *   disp_image_begin(w, h);          // 声明这一帧是图像 + 尺寸（顺带校验窗口装得下）
 *   disp_image_fill(0, 0, w, h, c);  // 写像素（RGB565 小端）
 *   disp_image_present();            // 门铃 +1（= 上屏），屏幕归图形帧
 * ===================================================================== */

/* 读配置块（disp.base / disp.bytes）接管显存窗口，字符格 80x25 —— 所有系统都走这一条。 */
int disp_auto_init(void);

/* 这一帧是图形帧：写窗口的 cols/rows = **像素**尺寸、format = RGB565。
 * @return 0 = 可以画；-1 = 没有窗口 / 窗口装不下这一帧（调用方必须明确报出来） */
int disp_image_begin(uint32_t width, uint32_t height);

/* 写一个像素（越界不写；调用方自己保证坐标） */
void disp_image_put(uint32_t x, uint32_t y, uint16_t rgb565);

/* 填一块纯色矩形（x/y/w/h 像素；裁剪到这一帧之内） */
void disp_image_fill(uint32_t x, uint32_t y, uint32_t w, uint32_t h, uint16_t rgb565);

/* 图形帧上屏：门铃 +1，并把屏幕交给图形帧（见 disp_screen_is_image）。返回新的帧号。 */
uint32_t disp_image_present(void);

/* ---- 屏幕归属：图形帧上屏后屏幕归它，直到"有新文本输出/玩家按键"把它收回 ----
 * 为什么需要它：控制台每 50ms 都会把字符刷上屏（con_flush），不挡住的话
 * 图形帧活不过一帧（真机上会表现成"画了但看不见"）。 */
int  disp_screen_is_image(void);      /* 屏幕现在归图形帧吗 */
void disp_screen_release(void);       /* 交还字符面（控制台在按键时调，并整屏重绘） */

/* 文本帧：把窗口的尺寸/格式还原成字符三平面（文本生产者每次刷屏开头调一次） */
void disp_text_begin(void);

#endif /* CRYPTAND_DISPLAY_H */
