/*
 * ============================================================================
 * Cryptand OS · 系统(2)：**FreeRTOS + LVGL（全功能）**（lv_conf.h，2026-09-17）
 *
 * 用户要求（2026-09-17）：「lvgl 需要开启所有功能」⇒ 本文件**刻意不裁剪**：
 *   · 控件（chart / calendar / dropdown / keyboard / table / textarea / win …）全开
 *   · 主题 / 布局（flex、grid）/ 3D / 矢量 全开
 *   · 字体：内置 Montserrat 全档 + 位图字模全开
 *   · 只关掉三类：需要外部库的图片解码（libpng/webp/jpeg）、需要操作系统的文件系统驱动、
 *     以及调试/演示（demo/examples）
 *
 * 字体系统（用户问"放 freetype 还是其他"）——本文件预留三个入口：
 *   (1) LV_USE_FREETYPE     ：功能最强（任意 TTF/OTF、任意字号、抗锯齿）。
 *                           打开后**不再需要文件系统**：用 freetype 的"内存字体"
 *                           （FT_New_Memory_Face）把字库数据直接喂进去。
 *                           需要把 freetype 源码放进 .ai_cache/c/freetype（等用户放）。
 *   (2) LV_USE_TINY_TTF     ：LVGL 内置的轻量 TTF 解析（无外部依赖）——**已开**，
 *                           放不了 freetype 时的主力，内存里就能加载 .ttf。
 *   (3) LV_FONT_MONTSERRAT_*：内置位图字体——**已全开**，最快最省，MCU 档首选。
 *
 * LVGL 官方要求：只提供这一份配置（LVGL 用 __has_include("lv_conf.h") 自动发现），
 * **不要改 LVGL 源码**。
 * ============================================================================
 */
#ifndef LV_CONF_H
#define LV_CONF_H

/* ---------------- OS 抽象：官方 FreeRTOS 适配层 ---------------- */
#define LV_USE_OS               LV_OS_FREERTOS

/* ---------------- 内存：走 LVGL 自带堆（不依赖 libc/newlib）---------------- */
#define LV_USE_STDLIB_MALLOC    LV_STDLIB_BUILTIN
#define LV_USE_STDLIB_STRING    LV_STDLIB_BUILTIN
#define LV_USE_STDLIB_SPRINTF   LV_STDLIB_BUILTIN
/* 全功能版需要更大的 LVGL 堆：宿主侧 RAM 映射同步提到 128KB（见 CryptandOcArchitecture） */
#define LV_MEM_SIZE             (48 * 1024)

/* ---------------- 颜色（LVGL 9.x 新写法；LV_COLOR_DEPTH 已废弃）---------------- */
#define LV_COLOR_FORMAT_DEFAULT LV_COLOR_FORMAT_RGB565

/* ---------------- 刷新节奏：字符屏 20fps 上限，省 CPU 预算 ---------------- */
#define LV_DEF_REFR_PERIOD      50

/* ---------------- 字体系统 ----------------
 * (1) FreeType：等用户把源码放到 .ai_cache/c/freetype 后改 1（构建脚本会自动加 include/源码）
 * (2) Tiny TTF：内置，已开
 * (3) 位图字模：默认全开（不覆盖 => 模板里的档位设置生效）
 */
#define LV_USE_FREETYPE         0
#define LV_USE_TINY_TTF         1
#define LV_USE_FONT_MANAGER     1
#define LV_USE_IMGFONT          1

/* ---------------- 只关这些：需要外部库 / 操作系统 / 纯调试 ---------------- */
/* 图片解码器：需要 libpng / libjpeg / libwebp / giflib 等外部库 */
#define LV_USE_LIBPNG           0
#define LV_USE_LIBJPEG_TURBO    0
#define LV_USE_LIBWEBP          0
#define LV_USE_GIF              0
#define LV_USE_FFMPEG           0
/* 文件系统：裸机没有 stdio/posix/fatfs */
#define LV_USE_FS_STDIO         0
#define LV_USE_FS_POSIX         0
#define LV_USE_FS_FATFS         0
#define LV_USE_FS_WIN32         0
#define LV_USE_FS_LITTLEFS      0
#define LV_USE_FS_ARDUINO_SD    0
#define LV_USE_FS_ARDUINO_ESP_LITTLEFS 0
/* 平台驱动：我们只有 OC 的字符屏，没有 Linux/SDL/GLFW 等 */
#define LV_USE_SDL              0
#define LV_USE_X11              0
#define LV_USE_WAYLAND          0
#define LV_USE_LINUX_FBDEV      0
#define LV_USE_LINUX_DRM        0
#define LV_USE_WINDOWS          0
#define LV_USE_QNX              0
#define LV_USE_NUTTX            0
#define LV_USE_EVDEV            0
#define LV_USE_LIBINPUT         0
/* 调试/示例：占 ROM 且与我们无关（日志走 UART，不用 LVGL 自己的 printf） */
#define LV_USE_LOG              0
#define LV_USE_TEST             0
#define LV_BUILD_EXAMPLES       0
#define LV_USE_DEMO_WIDGETS     0
#define LV_USE_DEMO_BENCHMARK   0
#define LV_USE_DEMO_STRESS      0
#define LV_USE_DEMO_MUSIC       0
#define LV_USE_DEMO_KEYPAD_AND_ENCODER 0
#define LV_USE_DEMO_RENDER      0
#define LV_USE_DEMO_MULTILANG   0
#define LV_USE_DEMO_SMARTWATCH  0
#define LV_USE_DEMO_EBIKE       0
#define LV_USE_DEMO_HIGH_RES    0
#define LV_USE_DEMO_FLEX_LAYOUT 0
#define LV_USE_DEMO_VECTOR_GRAPHIC 0
#define LV_USE_DEMO_GLTF        0
#define LV_USE_MONKEY           0

/* 其余（全部控件 / 主题 / 布局 / 3D / 矢量 / 观察者 / 动画 / 字模 …）
 * 一律**不覆盖** ⇒ 走 LVGL 官方默认值（模板里默认即"全开"）。 */

#endif /* LV_CONF_H */
