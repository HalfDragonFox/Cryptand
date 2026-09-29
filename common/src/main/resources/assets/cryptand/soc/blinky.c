/* ============================================================================
 * Cryptand SoC 示例固件：blinky.c（2026-09-15）
 *
 * 演示裸机 RV32IM 固件如何读写 Cryptand SoC 外设：
 *   - 每 ~1000 个 SoC 周期把计数写入寄存器区 REG[0]（世界侧可读，看到"闪烁"）
 *   - 通过 UART 输出启动信息与心跳
 *   - 用定时器 TIMER 计算时间（不依赖 MC tick）
 *
 * 编译（需 RISC-V 裸机工具链；见 cryptand.ld 头部注释）：
 *   riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -nostdlib -ffreestanding \
 *       -T cryptand.ld -o blinky.elf blinky.c
 *   riscv-none-elf-objcopy -O binary blinky.elf blinky.bin
 *
 * ⚠ 无浮点（内核无 F 扩展）；不要用 printf/malloc（-nostdlib）。
 * ========================================================================== */

/* ---- SoC 设备地址（与 SocBoard 装配一致）---- */
#define REG_BASE    0x10000000u
#define TIMER_BASE  0x10001000u
#define UART_BASE   0x10002000u
#define PWM_BASE    0x10003000u
#define ADC_BASE    0x10004000u

#define REG(i)      (*(volatile unsigned int  *)(REG_BASE   + 4u * (unsigned)(i)))
#define MTIME_LO    (*(volatile unsigned int  *)(TIMER_BASE + 0x00u))
#define MTIMECMP_LO (*(volatile unsigned int  *)(TIMER_BASE + 0x08u))
#define TIMER_CTRL  (*(volatile unsigned int  *)(TIMER_BASE + 0x10u))
#define UART_THR    (*(volatile unsigned char *)(UART_BASE  + 0x00u))
#define UART_LSR    (*(volatile unsigned char *)(UART_BASE  + 0x05u))
#define PWM_PERIOD  (*(volatile unsigned int  *)(PWM_BASE   + 0x00u))
#define PWM_DUTY    (*(volatile unsigned int  *)(PWM_BASE   + 0x04u))
#define PWM_CTRL    (*(volatile unsigned int  *)(PWM_BASE   + 0x08u))
#define ADC_CH0     (*(volatile unsigned int  *)(ADC_BASE   + 0x00u))
#define ADC_CTRL    (*(volatile unsigned int  *)(ADC_BASE   + 0x20u))

/* ---- 极简串口输出（轮询 LSR.THRE）---- */
#define UART_LSR_THRE 0x20u

static void uart_putc(char c)
{
    while ((UART_LSR & UART_LSR_THRE) == 0) {
        /* 等待发送保持寄存器空（设备随指令切片前进 ⇒ 不会死锁） */
    }
    UART_THR = (unsigned char)c;
}

static void uart_puts(const char *s)
{
    while (*s != '\0') {
        uart_putc(*s++);
    }
}

static void uart_putu(unsigned int v)
{
    char buf[12];
    int i = 0;
    if (v == 0) {
        uart_putc('0');
        return;
    }
    while (v > 0 && i < 11) {
        buf[i++] = (char)('0' + (v % 10u));
        v /= 10u;
    }
    while (i > 0) {
        uart_putc(buf[--i]);
    }
}

/* 前置声明：_start 的裸汇编里要 call 它。
 * ⚠ 不能是 static：C 层没有引用它（只被汇编 call），编译器会判定"未使用"而**根本不生成**，
 *   于是链接报 undefined reference。外部链接 + 前置声明即可。 */
void blinky_main(void);

/* ---- 复位入口（2026-09-17 修）----
 *
 * ⚠ 两个必须做对的事（原先缺失 ⇒ 固件一上电就跑飞）：
 *   ① **必须是 ROM 的第一条指令**：我们的 RV32 复位后 PC=0，而 C 函数在 .text 里的顺序
 *      由编译器决定（实测 uart_putc 排在了最前面）。所以入口要显式放进 `.text.start`，
 *      链接脚本的 `KEEP(*(.text.start))` 会把它固定在最前面。
 *   ② **sp 必须在任何 C 代码前设好**：复位时寄存器全 0，C 序言 `addi sp,sp,-64` 会写到
 *      0xFFFFFFC0 ⇒ 立即内存故障。这里用 naked + 汇编先把栈顶装上。
 *   `.data` 的 LMA→VMA 搬运与 `.bss` 清零见 firmware/cryptand-os/start.S（更大工程用）。
 */
__attribute__((section(".text.start"), naked, used))
void _start(void)
{
    __asm volatile (
        "la   sp, _stack_top\n"   /* 栈顶 = RAM 末端（cryptand.ld 里定义） */
        "call blinky_main\n"
        "1:   j 1b\n"             /* main 返回则原地自旋（固件不该返回） */
    );
}

/* ---- 主逻辑（ENTRY(_start) 之后的真正入口）---- */
void blinky_main(void)
{
    uart_puts("Cryptand SoC boot\r\n");

    /* 使能定时器（仅演示寄存器写入） */
    MTIMECMP_LO = 100000u;
    TIMER_CTRL = 1u;

    /* 使能 ADC 通道 0 */
    ADC_CTRL = 1u;

    /* PWM：周期 100，占空比 25% */
    PWM_PERIOD = 100u;
    PWM_DUTY = 25u;
    PWM_CTRL = 1u;

    unsigned int last = MTIME_LO;
    unsigned int count = 0;
    unsigned int heart = 0;

    for (;;) {
        const unsigned int now = MTIME_LO;
        if ((unsigned int)(now - last) >= 1000u) {
            last = now;
            count++;
            REG(0) = count;            /* 世界侧读 REG[0] 观察"闪烁" */
            REG(1) = ADC_CH0;          /* 把 ADC 采样值暴露给世界 */
            if ((count % 10u) == 0u) {
                uart_puts("tick ");
                uart_putu(heart++);
                uart_putc('\r');
                uart_putc('\n');
            }
        }
    }
}
