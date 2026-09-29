/* ============================================================================
 * Cryptand SoC 链接脚本模板（cryptand.ld，2026-09-15）
 *
 * 内存布局（与 SocBoard 装配一致；玩家固件必须匹配本布局）：
 *   0x0000_0000  ROM  固件（复位向量处，16KB）
 *   0x1000_0000  REGBANK  寄存器区（可选）
 *   0x1000_1000  TIMER    定时器
 *   0x1000_2000  UART     串口
 *   0x1000_3000  PWM      PWM
 *   0x1000_4000  ADC      ADC
 *   0x2000_0000  RAM  运行时内存（默认 64KB）
 *
 * 用法：
 *   riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -nostdlib -ffreestanding \
 *       -T cryptand.ld -o firmware.elf firmware.c
 *   riscv-none-elf-objcopy -O binary firmware.elf firmware.bin
 * ========================================================================== */

OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
  ROM (rx)  : ORIGIN = 0x00000000, LENGTH = 16K
  RAM (rwx) : ORIGIN = 0x20000000, LENGTH = 64K
}

SECTIONS
{
  /* 复位向量：入口必须是 ROM 的第一条指令（SoC 复位后 PC = 0） */
  .text : ALIGN(4)
  {
    KEEP(*(.text.start))
    *(.text*)
    *(.rodata*)
    *(.srodata*)
  } > ROM

  .data : ALIGN(4)
  {
    _data_start = .;
    *(.data*)
    *(.sdata*)
    _data_end = .;
  } > RAM AT> ROM

  .bss : ALIGN(4)
  {
    _bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(COMMON)
    _bss_end = .;
  } > RAM

  /* 栈在 RAM 顶端向下增长 */
  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
  _heap_start = _bss_end;

  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
