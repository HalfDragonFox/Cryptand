package com.hdf.cryptand.soc.memory;

import com.hdf.cryptand.soc.api.InterruptController;
import com.hdf.cryptand.soc.api.InterruptSource;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 简化中断控制器（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>汇聚各 {@link InterruptSource}，给出"当前待处理中断位图/最高优先级中断号"
 * （简化版 CLINT/PLIC）。CPU 在指令边界查询——外设不直接回调 CPU，保持单向依赖。</p>
 *
 * <p>中断号 = 注册顺序（0 起）。优先级 = 中断号小的优先（真实 MCU 的固定优先级语义）。</p>
 */
public final class SimpleInterruptController implements InterruptController {

    private final List<InterruptSource> sources = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    @Override
    public int getInterruptCount() {
        return sources.size();
    }

    @Override
    public int registerSource(InterruptSource source, String name) {
        if (source == null) {
            return -1;
        }
        if (sources.size() >= 32) {
            return -1;   // 位图上限（与真实 MCU 的中断线数量级一致）
        }
        sources.add(source);
        names.add(name == null || name.isBlank() ? ("irq" + sources.size()) : name);
        return sources.size() - 1;
    }

    /**
     * 注册到**指定中断号**（2026-09-17 加：FreeRTOS 等实时内核要求"固定中断号"）。
     *
     * <p>RISC-V 机器模式的标准中断号是固定的：{@code 3 = MSIP（软中断）}、
     * {@code 7 = MTIP（机器定时器）}、{@code 11 = MEIP（外部）}。FreeRTOS 的 RISC-V port
     * 打开的是 {@code mie} 的 {@code 1<<7} 位 ⇒ 定时器**必须**落在 7 号，否则 tick 永远不来。
     * 中间的空位用占位保留（读取时按"无源"处理）。</p>
     *
     * @return 实际中断号；越界/被占用/源为空返回 -1
     */
    public int registerSourceAt(int irq, InterruptSource source, String name) {
        if (source == null || irq < 0 || irq >= 32) {
            return -1;
        }
        while (sources.size() <= irq) {
            sources.add(null);          // 占位：该中断号暂时没有源
            names.add("");
        }
        if (sources.get(irq) != null) {
            return -1;                  // 已被占用：不覆盖，交由调用方决定
        }
        sources.set(irq, source);
        names.set(irq, name == null || name.isBlank() ? ("irq" + irq) : name);
        return irq;
    }

    @Override
    public long getPendingInterrupts() {
        long pending = 0;
        for (int i = 0; i < sources.size(); i++) {
            final InterruptSource s = sources.get(i);
            if (s != null && s.isInterrupting(i)) {
                pending |= 1L << i;
            }
        }
        return pending;
    }

    @Override
    public int getHighestPendingInterrupt() {
        final long pending = getPendingInterrupts();
        return pending == 0 ? -1 : Long.numberOfTrailingZeros(pending);
    }

    @Override
    public void reset() {
        // 源自身由 Board 统一复位；这里仅清理登记表状态（保留注册，便于复位后继续用）
    }

    @Override
    public List<String> getSourceNames() {
        return new ArrayList<>(names);
    }

    /** 诊断：当前中断状态（UI/日志） */
    public String describe() {
        final long pending = getPendingInterrupts();
        final StringBuilder sb = new StringBuilder("irq pending=0x").append(Long.toHexString(pending));
        for (int i = 0; i < names.size(); i++) {
            sb.append("\n  [").append(i).append("] ").append(names.get(i))
                    .append((pending & (1L << i)) != 0 ? "  *请求中*" : "");
        }
        return sb.toString();
    }
}
