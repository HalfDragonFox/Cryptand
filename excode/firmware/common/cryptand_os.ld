/*
 * ============================================================================
 * Cryptand OS 链接脚本（2026-09-17）
 *
 * 与 SocBoard 装配一致（改了 SocBoard 的地址就必须同步改这里）：
 *   0x0000_0000  ROM  64KB  **Boot 区**（Cryptand Boot / EEPROM 住这里，复位向量在 0x0）
 *   0x0001_0000  ROM 448KB  **系统区**（本脚本链接的系统镜像由 Boot 刷到这里执行）
 *   0x1000_0000  REGBANK    OC 组件桥 ABI 寄存器组（OcAbi）
 *   0x1000_1000  TIMER      CLINT 风格（FreeRTOS 的 tick 用它）
 *   0x1000_2000  （已删除）UART 16550 寄存器组：2026-09-27 起 UART 是虚拟机建立的一块硬件，
 *               芯片只读写它的寄存器窗口 HAL_UART_CACHE_BASE = 0x2002_4820（RAM 尾部，不在设备区）
 *   0x2000_0000  RAM  128KB 运行时（栈顶在 RAM 末端）
 *
 * ⚠ **为什么系统不与 Boot 共用 0x0**（2026-09-17 真机踩坑）：Boot 自己就跑在 0x0，
 *   系统镜像若也刷到 0x0，等于在 Boot 执行到一半时把它的指令换掉 —— PC 落在已被替换成
 *   系统代码的地址上继续跑（实测 guest PC 恒 0x6fc，正是 OS 的 xTaskIncrementTick，
 *   UART 一行不出、未映射设备访问每秒上千次）。⇒ 分区：Boot 0x0 / 系统 0x10000。
 *
 * ⚠ 本脚本只用于**系统**；Boot 用 `cryptand_boot.ld`（ROM 0x0, 64KB）。
 *   入口不再是复位向量：`.text.start` 仍是首地址，但由 Boot 跳进来（入口 = 0x0001_0000）。
 * ============================================================================
 */
OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
  /* ⚠ 尺寸按"最大的那个系统"取（链接脚本三系统共用）：
     · 系统① 仅 FreeRTOS：实际用 ~7KB
     · 系统② FreeRTOS+LVGL 全功能：控件/主题/全字体档位到 300KB+ ⇒ ROM 512KB
     · 系统③ Cryptand UI OS：还要加窗口管理器与应用框架
     宿主侧 SocBoard 的 rom 尺寸 = 镜像长度，不需要同步改；RAM 见 MIN_RAM_BYTES。 */
  ROM (rx)  : ORIGIN = 0x00010000, LENGTH = 448K
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
  /* 配置块（用户 2026-09-17：**上电读配置文件，改文件 + 重启机器即生效**）：
     RAM 最高 4KB 留给宿主注入的系统配置（config/cryptand/cryptand-os.cfg），
     guest 用 hal_config_* 直接读这块内存 ⇒ 零 MMIO 成本、不占设备地址。
     栈顶因此下移 4KB。 */
  _config_block = ORIGIN(RAM) + LENGTH(RAM) - CONFIG_RESERVE;
  _stack_top    = ORIGIN(RAM) + LENGTH(RAM) - CONFIG_RESERVE;
  _heap_start   = _bss_end;

  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
