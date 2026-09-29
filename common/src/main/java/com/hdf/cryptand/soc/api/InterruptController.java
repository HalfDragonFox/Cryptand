package com.hdf.cryptand.soc.api;

import java.util.List;

/**
 * ===== 中断控制器（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>汇聚各中断源并给出"当前待处理中断号"（简化版 CLINT/PLIC）。
 * CPU 每周期/{@code step} 结束时查询即可，避免外设直接回调 CPU。</p>
 */
public interface InterruptController {

    /** 已注册的中断源数量（= 可用中断线数） */
    int getInterruptCount();

    /** 注册中断源（返回其分配到的中断号） */
    int registerSource(InterruptSource source, String name);

    /** 当前所有有效中断源的位图（bit i = 中断号 i 在请求） */
    long getPendingInterrupts();

    /** 当前优先级最高的待处理中断号；无则返回 -1 */
    int getHighestPendingInterrupt();

    /** 清空（复位时调用） */
    void reset();

    /** 已注册中断源的名称（诊断/UI） */
    List<String> getSourceNames();
}
