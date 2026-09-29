/*
 * ============================================================================
 * Cryptand OS · FreeRTOS 配置（2026-09-17）
 *
 * 目标：在 Cryptand 的 RV32IM 沙箱上跑一个"模仿 OC Lua 那套"的基本系统：
 *   BIOS/内核（本工程） → 任务（控制台/心跳） → 组件 API（GPU/键盘/文件系统，走 MMIO ABI）
 *
 * 硬件对齐（与 SocBoard 装配 + TimerDevice 寄存器布局一致）：
 *   ROM  0x0000_0000（16KB，复位向量）
 *   REG  0x1000_0000  RegBank（OC 组件桥 ABI）
 *   TIMER 0x1000_1000 CLINT 风格：mtime_lo@+0x00 / mtimecmp_lo@+0x08（**FreeRTOS 直接吃这对地址**）
 *   UART 0x2002_4820  虚拟机建立的 UART 硬件之寄存器窗口（2026-09-27：不再有 MMIO 寄存器组）
 *   RAM  0x2000_0000（默认 64KB；OC 机箱里按内存条容量变）
 * ============================================================================
 */
#ifndef FREERTOS_CONFIG_H
#define FREERTOS_CONFIG_H

/* ---------------- 调度器 ---------------- */
#define configUSE_PREEMPTION                    1
#define configUSE_PORT_OPTIMISED_TASK_SELECTION 0
#define configUSE_TICKLESS_IDLE                 0
#define configCPU_CLOCK_HZ                      ( 20000000UL )   /* SoC 档：spec 1e6 cycles/tick × 20 */
#define configTICK_RATE_HZ                      ( 100 )          /* 10ms 一个 tick，够用且省预算 */
#define configMAX_PRIORITIES                    ( 7 )
#define configMINIMAL_STACK_SIZE                ( 256 )          /* 单位：字（256×4 = 1KB） */
#define configMAX_TASK_NAME_LEN                 ( 12 )
#define configUSE_16_BIT_TICKS                  0
#define configIDLE_SHOULD_YIELD                 1
#define configUSE_TIME_SLICING                  1
#define configUSE_TASK_NOTIFICATIONS            1
#define configUSE_MUTEXES                       1
/* ⚠ LVGL 的官方 FreeRTOS OSAL（src/osal/lv_freertos.c）用**递归互斥量**
   （xSemaphoreCreateRecursiveMutex / Take·GiveRecursive）⇒ 必须打开，否则编译报隐式声明。 */
#define configUSE_RECURSIVE_MUTEXES             1
#define configUSE_COUNTING_SEMAPHORES           1
#define configUSE_QUEUE_SETS                    0
#define configQUEUE_REGISTRY_SIZE               4

/* ---------------- 内存 ---------------- */
/* 内核堆 32KB，任务栈从堆上分（heap_4）。
   ⚠ 系统(2)(3)（LVGL / UI OS）要建 4 个任务（gui 4KB + panel/ui 2KB + shell 2KB + con 1.5KB）
   再加 LVGL 官方 OSAL 的递归互斥量 —— 12KB 会 heap exhausted（实测），所以给 32KB。
   系统(1) 只用几 KB，多余部分只是 bss（不进 bin）。 */
#define configSUPPORT_STATIC_ALLOCATION         0
#define configSUPPORT_DYNAMIC_ALLOCATION        1
#define configTOTAL_HEAP_SIZE                   ( 32 * 1024 )
#define configAPPLICATION_ALLOCATED_HEAP        0

/* ---------------- 内建服务（先关掉省 RAM） ---------------- */
#define configUSE_TIMERS                        0
#define configUSE_TRACE_FACILITY                0
#define configUSE_STATS_FORMATTING_FUNCTIONS    0
#define configGENERATE_RUN_TIME_STATS           0
#define configUSE_NEWLIB_REENTRANT              0
#define configENABLE_BACKWARD_COMPATIBILITY     0
#define configNUM_THREAD_LOCAL_STORAGE_POINTERS 0

/* ---------------- 钩子与检查 ---------------- */
#define configUSE_IDLE_HOOK                     0
#define configUSE_TICK_HOOK                     1     /* LVGL 时基由 hal.c 的 vApplicationTickHook 提供 */
#define configUSE_MALLOC_FAILED_HOOK            1
#define configCHECK_FOR_STACK_OVERFLOW          2
#define configUSE_DAEMON_TASK_STARTUP_HOOK      0

/* ---------------- RISC-V port 必备 ---------------- */
/* CLINT 地址：对准 TimerDevice 的 mtime / mtimecmp（lo 在前，hi 在后 4 字节 —— port 就是这么写的） */
#define configMTIME_BASE_ADDRESS                ( 0x10001000UL + 0x00UL )
#define configMTIMECMP_BASE_ADDRESS             ( 0x10001000UL + 0x08UL )

/*
 * 中断栈：定义本宏 ⇒ port.c 用静态数组当 ISR 栈（否则要求链接脚本提供
 * __freertos_irq_stack_top）。静态数组更省事，也避免链接脚本出错。
 *
 * ⚠ 必须满足 configCHECK_FOR_STACK_OVERFLOW ≤ 2 才允许（port.c 有静态断言）。
 */
#define configISR_STACK_SIZE_WORDS              ( 256 )

/* 机器模式中断优先级常量（RISC-V port 不真正使用 PLIC，占位以免第三方代码引用报错） */
#define configKERNEL_INTERRUPT_PRIORITY         0

/* ---------------- 断言 ---------------- */
void vAssertCalled(const char *pcFile, unsigned long ulLine);
#define configASSERT( x )                       \
    if( ( x ) == 0 ) {                          \
        vAssertCalled( __FILE__, __LINE__ );    \
    }

/* ---------------- 函数可见性（现代内核要求显式开关） ---------------- */
#define INCLUDE_vTaskPrioritySet                1
#define INCLUDE_uxTaskPriorityGet               1
#define INCLUDE_vTaskDelete                     1
#define INCLUDE_vTaskSuspend                    1
#define INCLUDE_vTaskDelayUntil                 1
#define INCLUDE_vTaskDelay                      1
#define INCLUDE_xTaskGetSchedulerState          1
#define INCLUDE_xTaskGetCurrentTaskHandle       1
#define INCLUDE_uxTaskGetStackHighWaterMark     1

#endif /* FREERTOS_CONFIG_H */
