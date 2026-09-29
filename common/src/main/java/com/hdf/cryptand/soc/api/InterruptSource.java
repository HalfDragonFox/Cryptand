package com.hdf.cryptand.soc.api;

/**
 * ===== 中断源（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>外设向中断控制器拉高/释放自己的中断线（真实 MCU 的 NVIC/PLIC 语义）。</p>
 */
public interface InterruptSource {

    /**
     * 是否正在请求中断。
     *
     * @param irq 中断号（映射到该源的那条线）
     */
    boolean isInterrupting(int irq);
}
