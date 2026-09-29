/*
 * ============================================================================
 * Cryptand Boot 链接脚本（cryptand_boot.ld，2026-09-17）
 *
 * 与 cryptand_os.ld 的分工（**ROM 分区**，别合并成一份）：
 *   cryptand_boot.ld  ROM 0x0000_0000  64KB   Boot（EEPROM 镜像，复位后 PC=0 就跑它）
 *   cryptand_os.ld    ROM 0x0001_0000 448KB   系统（由 Boot 刷进系统区后跳进来）
 *
 * ⚠ 为什么必须分两份：Boot 是**正在运行**的那个程序，系统的载入（宿主把它刷进 guest ROM）
 *   不能落在 Boot 自己的代码上 —— 否则 PC 会继续在"已被换成系统代码"的地址上执行
 *   （2026-09-17 真机实测：guest PC 恒 0x6fc = OS 的 xTaskIncrementTick，UART 无输出、
 *    未映射设备访问每秒上千次，主频被拖到 0.03 MHz）。
 *
 * 其余地址与 SocBoard 装配一致：
 *   0x1000_0000  REGBANK    OC 组件桥 ABI 寄存器组（OcAbi）
 *   0x1000_1000  TIMER      CLINT 风格（FreeRTOS 的 tick 用它）
 *   0x1000_2000  （已删除）UART 16550 寄存器组：2026-09-27 起 UART 是虚拟机建立的一块硬件，
 *               芯片只读写它的寄存器窗口 HAL_UART_CACHE_BASE = 0x2002_4820（RAM 尾部，不在设备区）
 *   0x2000_0000  RAM  128KB 运行时（栈顶在 RAM 末端；跳转到系统后由系统重新初始化）
 *
 * ⚠ 复位后 PC = 0 ⇒ `.text.start` 必须排在 ROM 最前（见 start.S）
 * ============================================================================
 */
OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
  ROM (rx)  : ORIGIN = 0x00000000, LENGTH = 64K
  RAM (rwx) : ORIGIN = 0x20000000, LENGTH = 128K
}

/* 宿主注入配置块的保留字节数（放在 RAM 最高处，见下面的 _config_block） */
CONFIG_RESERVE = 4K;

SECTIONS
{
  /* ---------- 代码 + 只读数据（常驻 ROM） ---------- */
  .text : ALIGN(4)
  {
    KEEP(*(.text.start))         /* 复位入口：必须第一条 */
    *(.text*)
    *(.rodata*)
    *(.srodata*)
  } > ROM

  /* ---------- 已初始化数据：VMA 在 RAM，LMA 在 ROM（由 start.S 搬运） ---------- */
  .data : ALIGN(4)
  {
    _data_start = .;
    *(.data*)
    *(.sdata*)
    _data_end = .;
  } > RAM AT> ROM
  _data_lma = LOADADDR(.data);

  /* ---------- 未初始化数据（由 start.S 清零） ---------- */
  .bss (NOLOAD) : ALIGN(4)
  {
    _bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(COMMON)
    _bss_end = .;
  } > RAM

  /* ---------- 其它 ---------- */
  /* 配置块（上电读配置文件，改文件 + 重启机器即生效）：
     RAM 最高 4KB 留给宿主注入的系统配置（config/cryptand/cryptand-os.cfg）。 */
  _config_block = ORIGIN(RAM) + LENGTH(RAM) - CONFIG_RESERVE;
  _stack_top    = ORIGIN(RAM) + LENGTH(RAM) - CONFIG_RESERVE;
  _heap_start   = _bss_end;

  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
