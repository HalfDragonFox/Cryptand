/*
 * ============================================================================
 * Cryptand OS 复位入口（start.S，2026-09-17）
 *
 * 复位后硬件状态：PC = 0，寄存器全 0（含 sp）⇒ 任何 C 代码前必须：
 *   ① 装好栈顶（否则 C 序言的 addi sp,sp,-N 会写到 0xFFFFFFxx ⇒ 内存故障）
 *   ② 把 .data 从 ROM（LMA）搬到 RAM（VMA）
 *   ③ 清零 .bss
 *   ④ 才 call main
 *
 * ⚠ 必须放在 .text.start：链接脚本 KEEP 它到 ROM 首地址。
 * ============================================================================
 */

    .section .text.start, "ax", @progbits
    .globl _start
    .type  _start, @function
_start:
    /* ① 栈顶 = RAM 末端（cryptand_os.ld 的 _stack_top） */
    la      sp, _stack_top

    /* ② 搬运 .data：ROM 的 _data_lma → RAM 的 _data_start.._data_end */
    la      a0, _data_start
    la      a1, _data_end
    la      a2, _data_lma
1:
    bgeu    a0, a1, 2f
    lw      t0, 0(a2)
    sw      t0, 0(a0)
    addi    a0, a0, 4
    addi    a2, a2, 4
    j       1b
2:
    /* ③ 清零 .bss */
    la      a0, _bss_start
    la      a1, _bss_end
3:
    bgeu    a0, a1, 4f
    sw      zero, 0(a0)
    addi    a0, a0, 4
    j       3b
4:
    /* ④ 进 C：FreeRTOS 内核 + 我们的任务 */
    call    main

    /* main 不该返回（调度器不返回）；真返回了就原地自旋，别跑飞 */
5:
    j       5b

    .size _start, . - _start
