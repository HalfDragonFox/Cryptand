package com.hdf.cryptand.soc.api;

/**
 * ===== 可周期推进设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>按抽象"周期"推进的设备（定时器/PWM/ADC/串口移位寄存器等）。
 * <b>周期是 SoC 自己的时间单位，与 MC tick 无关</b>——这是固件内定时语义正确的
 * 前提（一 tick 跑 10 万周期时，1ms 定时中断仍按周期正确触发）。</p>
 *
 * <p>实现不得假定单次 {@code cycles} 的数量（可能大范围变化）。</p>
 */
public interface Steppable {

    /**
     * 推进指定周期数。
     *
     * @param cycles 周期数（&gt; 0）
     */
    void step(int cycles);
}
