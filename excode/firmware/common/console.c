/*
 * ============================================================================
 * Cryptand OS · 字符控制台 + shell 实现（console.c，2026-09-17）
 *
 * 对标 OpenComputers 里 OpenOS 的那一层：一个能收命令、能回显、能打印系统信息的
 * 最小命令行系统。区别是它跑在我们的 RV32 沙箱里，输出走 OC 的 GPU 组件。
 *
 * 输出路径：con_* → 本文件字符缓冲 → con_flush() → 一次 gpu_blit → 宿主 → GPU
 * 输入路径：shell_feed(char) ← **消息缓存区**（宿主写进 guest RAM 的统一入口：键盘/串口/宿主通知）
 *           或 UART RX（真实 16550 硬件那条线；见 shell_poll_uart）
 * ============================================================================
 */

#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"
#include "hal.h"
#include "console.h"
#include "display.h"      /* 屏幕归属：图形帧（disp_image_*）占屏时字符面不刷（见 con_flush） */

/* 系统(1) 不含 LVGL（镜像最小）：版本类命令按编译开关退化，保证 console 两层都能用 */
#ifdef CRYPTAND_WITH_LVGL
#include "lvgl.h"
#endif

/* ---------- 终端缓冲 ---------- */
/* ⚠ 数组按**上限**开（编译期），有效尺寸是运行时变量（见 con_init 的说明） */
static char     cells[CON_ROWS_MAX][CON_COLS_MAX];
static int      conCols = CON_DEF_COLS;   /* 查询失败时的保守默认 */
static int      conRows = CON_DEF_ROWS;
static int      cursorCol;
static int      cursorRow;
static int      originCol = 1;
static int      originRow = 1;
/* ⚠ 没有 flushBuf 了：旧路径要把脏行拷成一份副本交给 gpu_blit（组件调用）；
 * 现在直接写显存窗口的三平面（零往返），副本这一层整个不需要。 */

/* 脏区（行范围）—— 用户 2026-09-26："现实依旧很卡，输出很慢"。
 * 每次 flush 都整屏 blit 是主因；这里只记"哪些行变过"，con_flush 就只送这一段。
 *   · con_putc 写字符 ⇒ 标当前行
 *   · 滚动 / 清屏 / 初始化 ⇒ 标全屏（内容整体位移，逐行标不划算）
 *   · lastCursorRow 是**上一帧**光标行：光标移走时必须把旧行也重绘，否则留残影。 */
static int      dirtyTop = 0;
static int      dirtyBottom = CON_ROWS_MAX - 1;
static int      lastCursorRow = -1;

#define CON_DIRTY_ROW(r)  do { if ((r) < dirtyTop) { dirtyTop = (r); } \
                               if ((r) > dirtyBottom) { dirtyBottom = (r); } } while (0)
#define CON_DIRTY_ALL()   do { dirtyTop = 0; dirtyBottom = conRows - 1; } while (0)

/** 运行时有效列数 / 行数（供其它模块查询；见 console.h） */
int con_cols(void) { return conCols; }
int con_rows(void) { return conRows; }

/* ---------- 命令行缓冲 + 行编辑状态（2026-09-25） ---------- */
#define LINE_MAX 64

static char   lineBuf[LINE_MAX];
static int    lineLen;          /* 行内容长度 */
static int    linePos;          /* 行内**光标**位置（0..lineLen）—— ←→/Home/End 改的就是它 */
static int    lineShown;        /* 屏幕上已画出的行内容长度（重绘要按它把旧内容擦干净） */
static int    lineCursor;       /* 光标相对行首的偏移（重绘要按它把光标退回行首）——
                                 * ⚠ 这两个必须分开：行中间编辑时"光标位置"与"已画长度"不是一回事。
                                 *   2026-09-25 第一版只用一个 lineDrawn，退格时按**新的** linePos 回退，
                                 *   结果内容被画到错误位置（屏幕上出现 "> eecho ABC" 这种鬼影）。 */

/* 历史（↑↓）：环形；最新一条在 histNext-1。histPos == histCount 表示"正在编辑新行" */
#define HIST_MAX 8
static char   histBuf[HIST_MAX][LINE_MAX];
static int    histCount;
static int    histNext;
static int    histPos;          /* 浏览位置：-1 = 正在编辑新行（草稿）；0 = 最新一条；越大越早 */
static char   draftBuf[LINE_MAX];   /* 翻历史前正在编辑的内容，↓ 回到新行时还原 */
static int    draftLen;

/* ESC 序列解析状态（↑↓←→/Home/End/Delete 在真实终端里都是 ESC 序列） */
enum { ESC_NONE = 0, ESC_SEEN, ESC_CSI, ESC_SS3 };
static int    escState;
static int    escParam;         /* CSI 的第一个数字参数（如 ESC [ 3 ~ 的 3） */
static int    escHasParam;
static int    escBad;           /* 已判定"不认得这个序列" ⇒ 整段吃掉，一个字节都不进命令行 */
static TickType_t escTick;      /* 收到 ESC 的时刻：单独按 ESC / 序列被截断时靠它复位 */

/* ==================== 字符终端 ==================== */

void con_init(int col, int row)
{
    originCol = col;
    originRow = row;

    /* 屏有多大 —— **宿主是权威**（显示拓扑在它手里：谁挂着哪块屏、屏多大）。
     * 来源顺序只有这三档，且每一档都写进 UART 证据行：
     *   ① 宿主注入的 disp.cols / disp.rows（配置块）；
     *   ② 问显卡 gpu.getResolution；
     *   ③ 默认值（CON_DEF_COLS/ROWS）。
     * ⚠ ① 不是"多一条路"，是**纠正 ②的谎**：没有绑定屏时显卡回的是 1x1 而不是报错，
     *   控制台于是把自己当成 1x1（整屏只剩一格）—— 真机实测踩过。
     * ⚠ 必须夹在上限内（缓冲是编译期开的）；非法值**不能**写进去（0 行 =
     *   后面所有循环一次都不执行，屏幕全黑）。 */
    const int cfgCols = hal_config_int("disp.cols", 0);
    const int cfgRows = hal_config_int("disp.rows", 0);
    const char *sizeSrc;
    if (cfgCols > 0 && cfgRows > 0) {
        conCols = cfgCols < CON_COLS_MAX ? cfgCols : CON_COLS_MAX;
        conRows = cfgRows < CON_ROWS_MAX ? cfgRows : CON_ROWS_MAX;
        sizeSrc = " (from host config)\r\n";
    } else {
        const uint32_t res = gpu_get_resolution();
        sizeSrc = " (gpu.getResolution unavailable -> defaults)\r\n";
        if (res != 0u) {
            const int w = (int)((res >> 16) & 0xFFFFu);
            const int h = (int)(res & 0xFFFFu);
            if (w > 0 && h > 0) {
                conCols = w < CON_COLS_MAX ? w : CON_COLS_MAX;
                conRows = h < CON_ROWS_MAX ? h : CON_ROWS_MAX;
                sizeSrc = " (from gpu.getResolution)\r\n";
            }
        }
    }
    /* 显存窗口：**格数必须与控制台一致**（窗口是屏幕的字节镜像，错一格整屏错位）。
     * 拿不到窗口要报出来 —— 宁可日志里有一行，也不要静默黑屏。 */
    if (disp_init_sized((uint32_t)conCols, (uint32_t)conRows) != 0) {
        hal_uart_puts("[Cryptand OS] no display window (disp.base/disp.bytes not injected)\r\n");
    }
    /* 一行证据：控制台到底认为屏有多大、这个尺寸是**问屏问来的**还是默认值。
     * 窗口是屏的字节镜像 ⇒ 尺寸错了画面就只显示左上角（真机上踩过：问不到分辨率时用 80x25）。 */
    hal_uart_puts("[DISP] console ");
    hal_uart_putu((uint32_t)conCols);
    hal_uart_putc('x');
    hal_uart_putu((uint32_t)conRows);
    hal_uart_puts(sizeSrc);
    con_clear();
}

void con_clear(void)
{
    for (int r = 0; r < conRows; r++) {
        for (int c = 0; c < conCols; c++) {
            cells[r][c] = ' ';
        }
    }
    cursorCol = 0;
    cursorRow = 0;
    CON_DIRTY_ALL();
    con_flush();
}

/** 上滚一行（底部滚动，像终端一样） */
static void con_scroll(void)
{
    for (int r = 1; r < conRows; r++) {
        for (int c = 0; c < conCols; c++) {
            cells[r - 1][c] = cells[r][c];
        }
    }
    for (int c = 0; c < conCols; c++) {
        cells[conRows - 1][c] = ' ';
    }
    cursorRow = conRows - 1;
    CON_DIRTY_ALL();          /* 整体上移一行 ⇒ 全屏都需要重绘 */
}

void con_putc(char ch)
{
    /* 退格：只移动光标、不写字符格（原来没有这个分支 ⇒ 退格被当成普通字符写进了屏幕）。
     * ⚠ 行首的退格要**反向换行**到上一行末尾 —— 命令行加上提示符会超过一屏宽度（40 列），
     *   没有反向换行就没法把光标退回行首，整行重绘会错位（2026-09-25 加）。 */
    if (ch == '\b') {
        if (cursorCol > 0) {
            cursorCol--;
        } else if (cursorRow > 0) {
            cursorRow--;
            cursorCol = conCols - 1;
        }
        return;
    }
    if (ch == '\n') {
        cursorCol = 0;
        cursorRow++;
        if (cursorRow >= conRows) {
            con_scroll();
        }
        return;
    }
    if (ch == '\r') {
        cursorCol = 0;
        return;
    }
    if (cursorCol >= conCols) {
        cursorCol = 0;
        cursorRow++;
        if (cursorRow >= conRows) {
            con_scroll();
        }
    }
    cells[cursorRow][cursorCol++] = ch;
    CON_DIRTY_ROW(cursorRow);
}

void con_puts(const char *s)
{
    while (*s != '\0') {
        con_putc(*s++);
    }
}

/**
 * 打印一行 —— **不立即刷新屏幕**（2026-09-26 用户："显示输入和输出都很慢"）。
 *
 * <p>原因：一次 help 输出 30 行就调用 30 次 `con_flush`，每次都要跨语言把字符阵列交给
 * OC 显卡重绘 ⇒ 体感明确地慢。现在只把它标脏，由系统的心跳任务定期调
 * {@link #con_tick()} **批量**刷一次（人眼对 20~50ms 的合并完全无感，而 blit 次数降一个量级）。</p>
 *
 * <p>⚠ 键盘回显同样走这里：所以 {@link #con_tick} 的心跳间隔就是回显延迟上界，
 * 别把它调得太长（当前 50ms）。</p>
 */
void con_line(const char *s)
{
    con_puts(s);
    con_putc('\n');
}

/**
 * 定期刷新（由系统心跳任务调用）：距上次真正 blit 超过 `CON_FLUSH_INTERVAL_MS` 才刷。
 *
 * <p>这是"合并刷新"的落点：`con_line` 只标脏，真正的 blit 由这里统一节流。</p>
 */
void con_tick(void)
{
    static TickType_t last;
    const TickType_t now = xTaskGetTickCount();
    if (last != 0 && (now - last) < pdMS_TO_TICKS(CON_FLUSH_INTERVAL_MS)) {
        return;
    }
    last = now;
    con_flush();
}

void con_putu(uint32_t v)
{
    char tmp[12];
    int i = 0;
    if (v == 0) {
        con_putc('0');
        return;
    }
    while (v > 0 && i < 11) {
        tmp[i++] = (char)('0' + (v % 10u));
        v /= 10u;
    }
    while (i > 0) {
        con_putc(tmp[--i]);
    }
}

void con_puthex(uint32_t v)
{
    static const char hex[] = "0123456789ABCDEF";
    con_puts("0x");
    for (int shift = 28; shift >= 0; shift -= 4) {
        con_putc(hex[(v >> shift) & 0xF]);
    }
}

void con_flush(void)
{
    static int dumped = 0;
    static int hadFrame = 0;
    static int prevCursorCol = -1;
    static int prevCursorRow = -1;

    /* ---- 屏幕归属（2026-09-27 任务 H）----
     * 图形帧（"程序直接画图像"，见 display.h / cmd_gpu.c 的 draw）上屏后屏幕归它：
     * 这里直接返回，**不刷字符面**。否则心跳任务每 50ms 的合并刷新会把刚画上去的图像
     * 立刻盖掉 —— 真机上表现成"命令跑了、图像一帧就没了"。
     * 收回的时机只有一个：来了一个输入字节（shell_feed），那里整屏重绘一次。 */
    if (disp_screen_is_image()) {
        return;
    }
    if (!disp_ready()) {
        /* 没有显存窗口 = 没有输出路径。**不回落**到旧的 gpu_blit 组件路径：
         * 那条路每次刷新要跨线程往返一遍（输入延迟的根因），留着就是两条路。 */
        return;
    }

    /* ---- 无事可做就直接回去（2026-09-28）----
     * 旧路径每次进这里都要把光标行 blit 一次（组件调用按 tick 计价），于是空闲时也在刷。
     * 现在：内容没脏、光标没动 ⇒ 不写窗口、不加门铃 —— 空闲时**零上屏**。 */
    const int contentDirty = (dirtyTop <= dirtyBottom && dirtyTop < conRows);
    if (hadFrame && !contentDirty && cursorCol == prevCursorCol && cursorRow == prevCursorRow) {
        return;
    }

    /* ---- 脏区刷新（2026-09-26）----
     * 用户实测："现实依旧很卡，输出很慢" —— 根因是**每次 flush 都整屏 blit**：
     * 一次 w×h 的字节阵列要跨语言（固件→宿主→GPU），help 那种几十行的输出就要好几秒。
     * 现在只送**真正变过的行范围**：con_putc 标当前行，滚动/清屏标全屏，
     * 光标行（上一帧 + 这一帧）也必须算脏 —— 否则光标会留下残影。 */
    int top = dirtyTop < conRows ? dirtyTop : conRows - 1;
    int bottom = dirtyBottom < conRows ? dirtyBottom : conRows - 1;
    if (lastCursorRow >= 0 && lastCursorRow < conRows) {
        if (lastCursorRow < top) {
            top = lastCursorRow;
        }
        if (lastCursorRow > bottom) {
            bottom = lastCursorRow;
        }
    }
    if (cursorRow >= 0 && cursorRow < conRows) {
        if (cursorRow < top) {
            top = cursorRow;
        }
        if (cursorRow > bottom) {
            bottom = cursorRow;
        }
    }
    if (top < 0) {
        top = 0;
    }
    if (bottom < top) {
        bottom = top;
    }

    /* ---- 写显存窗口（2026-09-28 定案：**控制台不再走组件调用**）----
     * 旧路径是 gpu_blit（OC 组件）：OC 的组件调用必须回主线程兑现（SynchronizedCall），
     * 于是**每次刷新都是一次跨线程往返、按 tick 计价** —— 真机实测一次 flush 把输入任务
     * 按住几百毫秒，键盘回显 0.4~2.7 s 的根因就在这里。
     * 窗口是 guest RAM 的一段：disp_putc 就是普通 sw ⇒ 零往返、零 MMIO
     * （与 UART 外设缓存同一条纪律，见用户定案"任何设备访问都不许停 CPU"）。 */
    if (contentDirty) {
        for (int r = top; r <= bottom; r++) {
            for (int c = 0; c < conCols; c++) {
                disp_putc(originCol - 1 + c, originRow - 1 + r, (unsigned char)cells[r][c]);
            }
        }
        dirtyTop = conRows;          /* 清脏：下一个字符会重新标记 */
        dirtyBottom = -1;
    }
    /* 光标可见：行编辑（←→/Home/End/Delete）看不见光标等于没有反馈。
     * 只改**显存那一格**，真值 cells 不动（否则 '_' 会被当成行内容写进缓冲）。
     * 光标移走时先把旧位置恢复成真字符，不然一路留 '_' 残影。 */
    if (prevCursorRow >= 0 && prevCursorRow < conRows
        && prevCursorCol >= 0 && prevCursorCol < conCols
        && (prevCursorRow != cursorRow || prevCursorCol != cursorCol)) {
        disp_putc(originCol - 1 + prevCursorCol, originRow - 1 + prevCursorRow,
                  (unsigned char)cells[prevCursorRow][prevCursorCol]);
    }
    if (cursorRow >= 0 && cursorRow < conRows && cursorCol >= 0 && cursorCol < conCols) {
        disp_putc(originCol - 1 + cursorCol, originRow - 1 + cursorRow, (unsigned char)'_');
    }
    prevCursorCol = cursorCol;
    prevCursorRow = cursorRow;
    lastCursorRow = cursorRow;
    hadFrame = 1;
    const uint32_t seq = disp_text_present();   /* 声明"字符帧" + 门铃 +1（宿主下个 tick 上屏） */

    /* 诊断：只打"第一帧真的有内容"的那次 flush。
     * 屏幕全黑时靠它把三种病因分开：
     *   · 固件压根没画（这行不出现）
     *   · 固件画了但宿主没读到（这行有字，宿主侧 blit 诊断非空字节=0）
     *   · 画了也读到了，屏幕还是不显示（两边都正常）⇒ 问题在 OC 侧渲染/屏幕状态 */
    if (dumped == 0 && cells[0][0] != ' ' && cells[0][0] != '\0') {
        char line[13];
        for (int c = 0; c < 12; c++) {
            const char ch = (char)cells[0][c];
            line[c] = (ch >= 32 && ch < 127) ? ch : '.';
        }
        line[12] = '\0';
        dumped = 1;
        hal_uart_puts("[Cryptand OS] flush row0=[");
        hal_uart_puts(line);
        hal_uart_puts("] frame=");
        hal_uart_putu(seq);
        hal_uart_puts("\r\n");
    }
}

/* ==================== shell ==================== */

static void cmd_help(int argc, char **argv);
static void cmd_version(int argc, char **argv);
static void cmd_clear(int argc, char **argv);
static void cmd_echo(int argc, char **argv);
static void cmd_mem(int argc, char **argv);
static void cmd_uptime(int argc, char **argv);
static void cmd_sysinfo(int argc, char **argv);
static void cmd_lvgl(int argc, char **argv);
static void cmd_gpu(int argc, char **argv);
static void cmd_tasks(int argc, char **argv);
static void cmd_msg(int argc, char **argv);

/* shell_cmd 的类型定义在 console.h（系统程序要注册自己的命令表） */

static const shell_cmd COMMANDS[] = {
    /* ⚠ 屏幕文本一律 ASCII（用户 2026-09-25 实测："会有奇怪字符"）：
     *   字符格是"一格一字节"，中文是 UTF-8 多字节 ⇒ 一个字占 3 格且被当 Latin-1 显示成乱码，
     *   还会把 40 列的行宽算错、叠字。中文留给注释与 UART 日志（宿主按 UTF-8 解码）。 */
    { "help",    "list commands",           cmd_help },
    { "version", "system & kernel version", cmd_version },
    { "clear",   "clear the screen",        cmd_clear },
    { "echo",    "echo the arguments",      cmd_echo },
    { "mem",     "heap & heartbeat",        cmd_mem },
    { "uptime",  "uptime (ticks)",          cmd_uptime },
    { "sysinfo", "system info (CPU / MHz)", cmd_sysinfo },
    { "lvgl",    "LVGL stack self-check",   cmd_lvgl },
    { "gpu",     "GPU component self-check", cmd_gpu },
    { "tasks",   "FreeRTOS task limits",    cmd_tasks },
    { "msg",     "host message queue state", cmd_msg },
};
#define COMMAND_COUNT (sizeof(COMMANDS) / sizeof(COMMANDS[0]))

/* 扩展命令表（系统程序注册；见 console.h 的 shell_set_commands） */
static const shell_cmd *EXT_CMDS;
static int               EXT_COUNT;

void shell_set_commands(const shell_cmd *cmds, int count)
{
    EXT_CMDS  = cmds;
    EXT_COUNT = count < 0 ? 0 : count;
}

/* ---- 简单字符串工具（-nostdlib，不用 libc 的 str*） ---- */
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

static void prompt(void)
{
    con_puts("\n> ");
    con_flush();
}

/* ==================== 行编辑（2026-09-25） ====================
 *
 * 宿主侧已按**真实终端的表示法**发键（OcKeyboardInput）：控制字符（回车/退格/Tab）直接发字节，
 * 方向键 / Home / End / Delete / 功能键发 ANSI 序列（ESC [ A/B/C/D、ESC [ H/F、ESC [ 3~ …）。
 * 这一层把它们当成行编辑动作，规则与真实终端一致：
 *   · 认得的序列 ⇒ 行动作（历史 / 光标 / 删除）；
 *   · **不认得的 ESC 序列整段丢掉** —— 绝不能把 "ESC [ 1 5 ~" 里的 '1''5''~' 当正文打进命令行
 *     （没有状态机时，用户按 F5 就会看到命令行多出 "15~"）；
 *   · 单独按 ESC（或序列被截断）⇒ 由 shell_poll_uart 超时复位，别把后续正常输入吃掉。
 */

static void esc_reset(void);

static int line_strlen(const char *s)
{
    int n = 0;
    while (s[n] != '\0') {
        n++;
    }
    return n;
}

static int str_eq_n(const char *a, const char *b, int n)
{
    for (int i = 0; i < n; i++) {
        if (a[i] != b[i]) {
            return 0;
        }
    }
    return 1;
}

/** 把屏幕上的输入行整行重画，并把光标定位回 linePos（跨行也正确，靠 con_putc 的反向换行） */
static void line_repaint(void)
{
    int i;
    const int clear = lineShown > lineLen ? lineShown : lineLen;

    for (i = 0; i < lineCursor; i++) {
        con_putc('\b');                     /* 光标从"现在的位置"退回行首（不是按 linePos！） */
    }
    for (i = 0; i < clear; i++) {
        con_putc(' ');                      /* 空格盖掉旧内容（含被删掉的尾巴） */
    }
    for (i = 0; i < clear; i++) {
        con_putc('\b');
    }
    for (i = 0; i < lineLen; i++) {
        con_putc(lineBuf[i]);
    }
    for (i = lineLen; i > linePos; i--) {
        con_putc('\b');                     /* 光标定位回插入点 */
    }
    lineShown = lineLen;
    lineCursor = linePos;
    con_flush();
}

static void line_insert(char c)
{
    if (lineLen >= LINE_MAX - 1) {
        return;                             /* 行满：丢弃（真实终端也只是响一声不插入） */
    }
    if (linePos == lineLen) {               /* 行尾追加：直接打字，不必整行重画 */
        lineBuf[lineLen] = c;
        lineLen++;
        linePos = lineLen;
        lineShown = lineLen;
        lineCursor = lineLen;
        con_putc(c);
        con_flush();
        return;
    }
    for (int i = lineLen; i > linePos; i--) {
        lineBuf[i] = lineBuf[i - 1];
    }
    lineBuf[linePos] = c;
    lineLen++;
    linePos++;
    line_repaint();
}

static void line_backspace(void)
{
    if (linePos == 0) {
        return;
    }
    for (int i = linePos - 1; i < lineLen - 1; i++) {
        lineBuf[i] = lineBuf[i + 1];
    }
    lineLen--;
    linePos--;
    line_repaint();
}

/** Delete：删**光标处**的字符（与退格的区别就是它删右边的那一个） */
static void line_delete(void)
{
    if (linePos >= lineLen) {
        return;
    }
    for (int i = linePos; i < lineLen - 1; i++) {
        lineBuf[i] = lineBuf[i + 1];
    }
    lineLen--;
    line_repaint();
}

static void line_set_pos(int pos)
{
    if (pos < 0) {
        pos = 0;
    }
    if (pos > lineLen) {
        pos = lineLen;
    }
    if (pos != linePos) {
        linePos = pos;
        line_repaint();
    }
}

/* ---------- 历史（↑↓） ---------- */

/** 第 k 条历史（k = 0 是最新一条）；只在 k < histCount 时调用 */
static const char *hist_at(int k)
{
    int idx = histNext - 1 - k;
    while (idx < 0) {
        idx += HIST_MAX;
    }
    return histBuf[idx % HIST_MAX];
}

static void hist_store(int len)
{
    if (len <= 0) {
        return;
    }
    /* 与上一条完全相同就不入历史（真实 shell 的 ignoredups：连按回车不该把历史刷满） */
    if (histCount > 0 && line_strlen(hist_at(0)) == len && str_eq_n(hist_at(0), lineBuf, len)) {
        return;
    }
    for (int i = 0; i < len; i++) {
        histBuf[histNext][i] = lineBuf[i];
    }
    histBuf[histNext][len] = '\0';
    histNext = (histNext + 1) % HIST_MAX;
    if (histCount < HIST_MAX) {
        histCount++;
    }
}

/* ⚠ hist_at(k) 的 k 是"倒数第 k 条"（0 = 最新），histPos 与它同向 —— 2026-09-25 第一版把
 *   histPos 当成"顺数"索引（histCount-1 = 最新），于是 ↑ 取到的是**最老**的那条。 */



/** 把某条历史（pos == histCount 表示草稿）装进当前行并重画 */
static void hist_show(int pos)
{
    const char *src;
    int len;

    if (pos >= histCount) {
        src = draftBuf;
        len = draftLen;
    } else {
        src = hist_at(pos);
        len = line_strlen(src);
    }
    for (int i = 0; i < len; i++) {
        lineBuf[i] = src[i];
    }
    lineLen = len;
    linePos = len;                          /* 与真实终端一致：取出历史后光标停在行尾 */
    line_repaint();
}

static void hist_prev(void)                 /* ↑：更早的一条 */
{
    if (histCount == 0) {
        return;
    }
    if (histPos < 0) {                      /* 从"新行"进入历史：先把正在编辑的内容存成草稿 */
        for (int i = 0; i < lineLen; i++) {
            draftBuf[i] = lineBuf[i];
        }
        draftLen = lineLen;
    }
    if (histPos < histCount - 1) {
        histPos++;                          /* 向更早走 */
    }
    hist_show(histPos);                     /* histPos < histCount ⇒ 显示第 histPos 条历史 */
}

static void hist_next(void)                 /* ↓：更晚的一条；到底后回到草稿（新行） */
{
    if (histPos < 0) {
        return;                             /* 已经在新行 */
    }
    if (histPos > 0) {
        histPos--;
        hist_show(histPos);
    } else {
        histPos = -1;                       /* 走回"新行" ⇒ 恢复草稿 */
        hist_show(histCount);               /* pos >= histCount ⇒ hist_show 用草稿 */
    }
}

/* ---------- ESC 序列 ---------- */

static void esc_reset(void)
{
    escState = ESC_NONE;
    escParam = 0;
    escHasParam = 0;
    escBad = 0;
}

/** 执行一个认得的序列；只有这里会动命令行（escBad 时根本不会被调用） */
static void esc_apply(char final)
{
    switch (final) {
    case 'A': hist_prev(); break;                   /* ↑ 上一条历史 */
    case 'B': hist_next(); break;                   /* ↓ 下一条 */
    case 'C': line_set_pos(linePos + 1); break;     /* → */
    case 'D': line_set_pos(linePos - 1); break;     /* ← */
    case 'H': line_set_pos(0); break;               /* Home */
    case 'F': line_set_pos(lineLen); break;         /* End */
    case '~':
        if (escHasParam && escParam == 3) {
            line_delete();                          /* Delete = ESC [ 3 ~ */
        }
        /* Ins=2~ / PgUp=5~ / PgDn=6~ / F5..F12=15~..24~：本 shell 没有动作 ⇒ 丢弃 */
        break;
    default:
        break;                                      /* F1..F4（ESC O P..S）等：丢弃 */
    }
}

/**
 * 吃掉一个属于 ESC 序列的字节。
 *
 * <p>按 CSI 语法收：{@code ESC [ 数字 终止字节(0x40..0x7E)}。不认得的序列用 escBad 标记，
 * 但仍然一路吃到终止字节 —— 这样它一个字节都不会漏进命令行。</p>
 */
static void esc_feed(char c)
{
    switch (escState) {
    case ESC_SEEN:
        if (c == '[') {
            escState = ESC_CSI;
        } else if (c == 'O') {
            escState = ESC_SS3;
        } else {
            esc_reset();                /* ESC 后面不是 [ / O：整段丢弃 */
        }
        return;
    case ESC_SS3:
        esc_reset();                    /* ESC O x（F1..F4）：吃掉终止字节后丢弃 */
        return;
    case ESC_CSI:
        if (c >= '0' && c <= '9') {
            if (escParam > 1000) {
                escBad = 1;             /* 参数长到离谱 ⇒ 不认得（防跑飞），照样吃到终止字节 */
            }
            escParam = escParam * 10 + (c - '0');
            escHasParam = 1;
            return;
        }
        if (c >= 0x40 && c <= 0x7E) {   /* 终止字节：序列结束 */
            if (!escBad) {
                esc_apply(c);
            }
            esc_reset();
            return;
        }
        if (c >= 0x20 && c <= 0x3F) {
            escBad = 1;                 /* 多参数（;）或中间字节：本 shell 没有这种动作 ⇒ 丢 */
            return;
        }
        esc_reset();                    /* 别的（控制字符等）：序列断了，丢弃 */
        return;
    default:
        esc_reset();
        return;
    }
}

void shell_help(void)
{
    con_line("Cryptand shell - built-in commands:");
    for (unsigned i = 0; i < COMMAND_COUNT; i++) {
        con_puts("  ");
        con_puts(COMMANDS[i].name);
        con_puts(" - ");
        con_line(COMMANDS[i].help);
    }
    /* 扩展命令（系统程序注册的，如 PE 的 disks/format/install）单独列一段：
     * 玩家要能一眼看出"这个系统多给了哪些命令"，而不是把它们混进内置表。 */
    if (EXT_COUNT > 0) {
        con_line("");
        con_line("system commands:");
        for (int i = 0; i < EXT_COUNT; i++) {
            con_puts("  ");
            con_puts(EXT_CMDS[i].name);
            con_puts(" - ");
            con_line(EXT_CMDS[i].help);
        }
    }
}

void shell_init(void)
{
    lineLen = 0;
    linePos = 0;
    lineShown = 0;
    lineCursor = 0;
    histCount = 0;
    histNext = 0;
    histPos = -1;
    draftLen = 0;
    esc_reset();
    con_line("Cryptand OS shell 0.1  (type 'help')");
    prompt();
}

/** 按空格切分成 argv（就地修改缓冲） */
static int split_args(char *line, char **argv, int maxArgc)
{
    int argc = 0;
    char *p = line;
    while (*p != '\0' && argc < maxArgc) {
        while (*p == ' ' || *p == '\t') {
            p++;
        }
        if (*p == '\0') {
            break;
        }
        argv[argc++] = p;
        while (*p != '\0' && *p != ' ' && *p != '\t') {
            p++;
        }
        if (*p != '\0') {
            *p++ = '\0';
        }
    }
    return argc;
}

void shell_exec_line(const char *text)
{
    char buf[LINE_MAX];
    int n = 0;
    while (text[n] != '\0' && n < LINE_MAX - 1) {
        buf[n] = text[n];
        n++;
    }
    buf[n] = '\0';

    char *argv[6];
    const int argc = split_args(buf, argv, 6);
    if (argc == 0) {
        return;
    }
    for (unsigned i = 0; i < COMMAND_COUNT; i++) {
        if (str_eq(argv[0], COMMANDS[i].name)) {
            COMMANDS[i].fn(argc, argv);
            return;
        }
    }
    /* 内置找不到再找扩展表（顺序固定：内置优先，系统命令不得遮蔽内置命令） */
    for (int i = 0; i < EXT_COUNT; i++) {
        if (str_eq(argv[0], EXT_CMDS[i].name)) {
            EXT_CMDS[i].fn(argc, argv);
            return;
        }
    }
    con_puts("unknown command: ");
    con_line(argv[0]);
}

void shell_feed(char c)
{
    /* ── 屏幕归属：图形帧（draw 画的图像）占着屏幕时，**第一个输入字节**把它交还控制台 ──
     * 这就是"图像一直显示到玩家按第一个键"这条语义的落点（用户 2026-09-27：程序画图像输出）。
     * ⚠ 必须**整屏重绘**：控制台平时只刷脏行（见 con_flush 的脏区逻辑），
     *   图形帧留下的残影不会自己消失 —— 只刷脏行的话屏幕会一半字一半图。 */
    if (disp_screen_is_image()) {
        disp_screen_release();
        CON_DIRTY_ALL();
    }

    /* ── ESC 序列优先：行编辑的按键（方向键 / Home / End / Delete / 功能键）全从这里进来 ── */
    if (escState != ESC_NONE) {
        escTick = xTaskGetTickCount();        /* 序列还在继续 ⇒ 刷新超时基准 */
        esc_feed(c);
        return;
    }
    if (c == 27) {                            /* ESC：先挂起，看后面是 [ 还是 O（单独按 ESC 由 poll 超时复位） */
        escState = ESC_SEEN;
        escTick = xTaskGetTickCount();
        escParam = 0;
        escHasParam = 0;
        escBad = 0;
        return;
    }

    if (c == '\r' || c == '\n') {
        lineBuf[lineLen] = '\0';
        con_putc('\n');
        con_flush();
        if (lineLen > 0) {
            hist_store(lineLen);              /* 提交的行进历史（供 ↑ 取回） */
            shell_exec_line(lineBuf);
        }
        lineLen = 0;
        linePos = 0;
        lineShown = 0;
        lineCursor = 0;
        histPos = -1;                         /* 回到"新行"（草稿态） */
        draftLen = 0;
        prompt();
        return;
    }
    if (c == 8 || c == 127) {                 /* 退格：8 = BS、127 = DEL（退格键的两种编码） */
        line_backspace();
        return;
    }
    if (c >= 0x20 && c < 0x7F) {
        line_insert(c);
    }
}

/**
 * 从**消息缓存区**取输入（宿主 → 虚拟机 的统一入口，2026-09-27）。
 *
 * <p>宿主把键盘 / 串口 / 宿主通知 / 外部事件都写进 guest RAM 的那段窗口
 * （{@code HAL_MSG_BASE}，布局见 hal.h），固件在这里取：
 * 键盘与串口类按字节喂给行编辑（{@link shell_feed}），宿主通知/外部事件<b>当作一行文本打出来</b>
 * —— 后者不走行编辑，免得把玩家正在敲的那一行弄乱。</p>
 *
 * <p>三条纪律（与 hal_msg_take 的返回值一一对应）：空（0）就停；没就绪（-1）就停，
 * <b>绝不在这里死循环重试</b>（宿主没接这条通道时，轮询会把这个任务卡死）；
 * 装不下的帧（-2）已经整条消费掉，记进 {@code msgOversize}，由 {@code msg} 命令可见。</p>
 */
static uint32_t msgOversize;
static uint32_t msgBytes;       /* 本机经消息缓存区消费掉的字节数（诊断） */

static void shell_poll_msg(void)
{
    uint32_t kind = 0u;
    char buf[128];
    uint32_t got = 0u;

    /* 上限保护：一次调用最多消化 64 条，避免异常情况下把 shell 任务占住（正常一次也就一两条） */
    for (int guard = 0; guard < 64; guard++) {
        const int n = hal_msg_take(&kind, buf, (uint32_t)(sizeof(buf) - 1u));
        if (n == 0) {
            break;                              /* 空 */
        }
        if (n == -1) {
            break;                              /* 窗口没就绪 / 头部不自洽：本 tick 不取（不重试死循环） */
        }
        if (n == -2) {
            msgOversize++;                      /* 整条已消费但装不下：明确计数，不静默 */
            continue;
        }
        buf[n] = '\0';
        got += (uint32_t)n;
        msgBytes += (uint32_t)n;
        if (kind == HAL_MSG_KIND_KEY || kind == HAL_MSG_KIND_SERIAL) {
            for (int i = 0; i < n; i++) {
                shell_feed(buf[i]);
            }
        } else {
            /* 宿主通知 / 外部事件：直接打出来（ASCII 之外一律显示成 '.'，屏幕文本一律 ASCII） */
            con_puts("\n[host] ");
            for (int i = 0; i < n; i++) {
                const char c = buf[i];
                con_putc((c >= 0x20 && c < 0x7F) ? c : '.');
            }
            /* 通知不进命令行，但屏幕上的行编辑状态必须与编辑器一致：
             * prompt() 收掉这一行并重打提示符，再把"正在编辑的那一行"照原样重画（含光标位置）。 */
            prompt();
            for (int i = 0; i < lineLen; i++) {
                con_putc(lineBuf[i]);
            }
            for (int i = lineLen; i > linePos; i--) {
                con_putc('\b');
            }
            lineShown = lineLen;
            lineCursor = linePos;
            con_flush();
            /* 宿主通知也打一行 UART：屏幕上的那行无人化读不到，日志里必须有一份 */
            LOG("[MSG] host notice (%u bytes): %s\r\n", (uint32_t)n, buf);
        }
    }
    /* 一行证据（无输入时一行不打）：宿主 → 虚拟机这条路真的被走了，而且能看出积压与丢弃。
     * ⚠ 这里用 head-tail 现算积压（头里的 used 是宿主发布的，宿主还没刷新时是上一轮的值）。 */
    if (got > 0u) {
        LOG("[MSG] consumed %u bytes via MSG_QUEUE (queue %u/%u bytes, head=%u tail=%u, dropped=%u, taken=%u)\r\n",
            got, (uint32_t)(hal_msg_head() - hal_msg_tail()), hal_msg_capacity(),
            hal_msg_head(), hal_msg_tail(), hal_msg_dropped(), hal_msg_taken());
    }
}

void shell_poll_uart(void)
{
    /* ① 统一输入通道（宿主写 guest RAM 的消息缓存区）：键盘、串口、宿主通知都在这里 */
    shell_poll_msg();

    /* ② UART 那条线（**虚拟机建立的 UART 硬件**的 DR；键盘不再走它 —— 宿主统一从消息缓存区进）。
     * 取到几个喂几个，取不到就是没有：hal_uart_getc 读的是状态位 RXNE，绝不阻塞。 */
    int uartByte;
    while ((uartByte = hal_uart_getc()) >= 0) {
        shell_feed((char)uartByte);
    }
    /* ESC 序列被截断（单独按 ESC / 序列只到一半）：超时复位状态。
     * 不复位的话那个 ESC 会一直"等后续字节"，把接下来的正常按键全吃掉 ——
     * 用户看到的现象就是"按了 ESC 之后键盘失灵"（2026-09-25）。 */
    if (escState != ESC_NONE && (xTaskGetTickCount() - escTick) > pdMS_TO_TICKS(50)) {
        esc_reset();
    }
}

/* ==================== 内置命令 ==================== */

static void cmd_help(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    shell_help();
}

static void cmd_version(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_line("Cryptand OS 0.1");
    con_puts("  FreeRTOS   : " tskKERNEL_VERSION_NUMBER "\n");
#ifdef CRYPTAND_WITH_LVGL
    con_puts("  LVGL       : ");
    con_putu((uint32_t)LVGL_VERSION_MAJOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_MINOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_PATCH);
    con_putc('\n');
#else
    con_puts("  LVGL       : (not compiled in)\n");
#endif
    con_puts("  shell      : 0.1\n");
}

static void cmd_clear(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_clear();
}

static void cmd_echo(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        con_puts(argv[i]);
        if (i + 1 < argc) {
            con_putc(' ');
        }
    }
    con_putc('\n');
}

static void cmd_mem(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_puts("REG[0] (heartbeat) = ");
    con_putu(*(volatile uint32_t *)(HAL_HEARTBEAT_BASE + 0x00u));   /* 心跳区（原来误读组件桥偏移 0 = OC_REG_CALL）*/
    con_puts("\n");
    con_puts("FreeRTOS heap free = ");
    con_putu((uint32_t)xPortGetFreeHeapSize());
    con_puts(" bytes\n");
}

static void cmd_uptime(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_puts("ticks = ");
    con_putu((uint32_t)xTaskGetTickCount());
    con_puts("  (");
    con_putu((uint32_t)(xTaskGetTickCount() / (TickType_t)configTICK_RATE_HZ));
    con_line(" s)");
}

static void cmd_sysinfo(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_line("--- system info ---");
    con_puts("kernel   : Cryptand OS 0.1 / FreeRTOS\n");
    /* ⚠ 真实标称频率与 ISA 由**宿主按插着的处理器规格**注入（配置块 cpu.mhz / cpu.isa）——
     *   这里绝不能用编译期常量 configCPU_CLOCK_HZ：它写死 20 MHz，与芯片实际规格无关，
     *   于是 cpu1_32（1000 MHz / RV32IMAC）会被谎报成 "RV32IM @ 20 MHz"（用户 2026-09-26 实测）。
     *   读不到就打 unknown：宁可说不知道，也不编一个数字。 */
    {
        const int mhz = hal_config_int("cpu.mhz", 0);
        const char *isa = hal_config_get("cpu.isa", "");
        con_puts("cpu      : ");
        con_line((isa != 0 && isa[0] != 0) ? isa : "unknown-isa");
        con_puts("           @ ");
        if (mhz > 0) {
            con_putu((uint32_t)mhz);
            con_line(" MHz (nominal, from host)");
        } else {
            con_line("unknown MHz (host did not provide cpu.mhz)");
        }
    }
    con_puts("tick     : ");
    con_putu((uint32_t)configTICK_RATE_HZ);
    con_line(" Hz");
    con_puts("heap     : ");
    con_putu((uint32_t)configTOTAL_HEAP_SIZE);
    con_line(" bytes (FreeRTOS)");
    con_puts("gpu      : ");
    con_line(gpu_set(1u, 1u, "") >= 0 ? "component bridge ONLINE" : "component bridge OFFLINE (host not wired yet)");
}

static void cmd_lvgl(int argc, char **argv)
{
    (void)argc;
    (void)argv;
#ifdef CRYPTAND_WITH_LVGL
    con_puts("LVGL     : ");
    con_putu((uint32_t)LVGL_VERSION_MAJOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_MINOR);
    con_putc('.');
    con_putu((uint32_t)LVGL_VERSION_PATCH);
    con_putc('\n');
    con_puts("OS layer : ");
    con_line("LV_OS_FREERTOS (official osal)");
#else
    con_line("LVGL is not compiled into this system (FreeRTOS only)");
#endif
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

static void cmd_tasks(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_puts("FreeRTOS priorities  = ");
    con_putu((uint32_t)configMAX_PRIORITIES);
    con_puts("\n");
    con_line("(full task list needs configUSE_TRACE_FACILITY; off to save RAM)");
}

/**
 * host 消息缓存区状态（诊断 / 无人化断言）。
 *
 * <p>这一行能把三件事分开：<b>宿主有没有发</b>（seq / head 在涨）、
 * <b>固件有没有收</b>（taken 在涨、pending 归零）、<b>有没有塞满丢过</b>（dropped）。
 * 少了它，"注入了却没反应"根本分不清是宿主没发、还是固件没读。</p>
 */
static void cmd_msg(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    con_line("--- host message queue ---");
    con_puts("state    : ");
    con_line(hal_msg_ready() ? "ONLINE (host published the window)" : "OFFLINE (no window: host not wired)");
    con_puts("capacity : ");
    con_putu(hal_msg_capacity());
    con_line(" bytes (ring data area)");
    con_puts("pending  : ");
    con_putu(hal_msg_used());
    con_puts(" / ");
    con_putu(hal_msg_capacity());
    con_line(" bytes");
    con_puts("head/tail: ");
    con_putu(hal_msg_head());
    con_puts(" / ");
    con_putu(hal_msg_tail());
    con_line("  (monotonic byte counters)");
    con_puts("dropped  : ");
    con_putu(hal_msg_dropped());
    con_line(" messages (host queue was full: nothing lost silently)");
    con_puts("seq      : ");
    con_putu(hal_msg_seq());
    con_line(" messages enqueued by host (cumulative)");
    con_puts("taken    : ");
    con_putu(hal_msg_taken());
    con_line(" messages consumed by firmware");
    con_puts("flags    : ");
    con_putu(hal_msg_state());
    con_line("  (1=READY 2=FULL 4=DROPPED)");
    con_puts("oversize : ");
    con_putu(msgOversize);
    con_line(" messages consumed but too long for the reader buffer");
    con_puts("consumed : ");
    con_putu(msgBytes);
    con_line(" bytes total (this boot)");
    /* 同一份事实也打一行 UART：无人化只需在 latest.log 里找 \"[MSG] queue\"（屏幕读不到字符级内容时靠它） */
    LOG("[MSG] queue state=%u capacity=%u pending=%u dropped=%u seq=%u taken=%u oversize=%u consumed=%u\r\n",
        hal_msg_state(), hal_msg_capacity(), (uint32_t)(hal_msg_head() - hal_msg_tail()),
        hal_msg_dropped(), hal_msg_seq(), hal_msg_taken(), msgOversize, msgBytes);
}
