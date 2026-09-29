package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.InterruptSource;
import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Resettable;
import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.api.Steppable;

/**
 * ===== 定时器设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>CLINT 风格的机器定时器：固件写 {@code mtimecmp}，{@code mtime} 随
 * <b>SoC 周期</b>递增（<b>与 MC tick 无关</b>——这正是固件内 1ms 定时正确的前提），
 * 到期拉中断线。</p>
 *
 * <p>⚠ <b>中断条件必须与真实 CLINT 同构</b>：真实硬件只有 {@code mtime}/{@code mtimecmp}
 * 两个寄存器，比较成立即拉中断 —— <b>没有"使能位"</b>。本类曾要求先写自创的 {@code ctrl}，
 * 结果 FreeRTOS（它只认标准地址）永远等不到 tick，任务调度整个不存在
 * （见 {@code REG_MTIMECMP_*}} 分支里的教训）。所以现在：<b>写 mtimecmp 即启用</b>；
 * {@code ctrl} 退化为可选的显式开关（关掉它仍能强制停表，便于宿主调试）。</p>
 *
 * <h3>寄存器（小端 32 位）</h3>
 * <pre>
 *   0x00 mtime_lo      机器时间低位（只读）
 *   0x04 mtime_hi      机器时间高位
 *   0x08 mtimecmp_lo   比较值低位（读写）
 *   0x0C mtimecmp_hi   比较值高位
 *   0x10 ctrl          bit0 = 使能（**可选**：写 mtimecmp 时已自动置位，标准 CLINT 无此寄存器）
 *   0x14 status        bit0 = 到期（写任意值清除）
 *   0x18 div           分频（默认 1；mtime 每 div 个周期 +1）
 *   0x1C cycles_lo     累计周期数低位（只读，诊断/性能计数）
 * </pre>
 */
public final class TimerDevice implements MemoryMappedDevice, Steppable, InterruptSource, Resettable {

    public static final int REG_MTIME_LO = 0x00;
    public static final int REG_MTIME_HI = 0x04;
    public static final int REG_MTIMECMP_LO = 0x08;
    public static final int REG_MTIMECMP_HI = 0x0C;
    public static final int REG_CTRL = 0x10;
    public static final int REG_STATUS = 0x14;
    public static final int REG_DIV = 0x18;
    public static final int REG_CYCLES_LO = 0x1C;
    public static final int REG_CYCLES_HI = 0x20;

    private static final int LENGTH = 0x24;

    private long mtime;
    private long mtimecmp;
    private long totalCycles;
    private int div = 1;
    private boolean enabled;
    private boolean pending;
    private final String name;

    public TimerDevice() {
        this("TIMER");
    }

    public TimerDevice(String name) {
        this.name = name == null ? "TIMER" : name;
    }

    @Override
    public int getLength() {
        return LENGTH;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_8_LOG2) | (1 << Sizes.SIZE_16_LOG2) | (1 << Sizes.SIZE_32_LOG2);
    }

    @Override
    public long load(int offset, int size) {
        final int reg = offset & ~3;
        return switch (reg) {
            case REG_MTIME_LO -> (mtime & 0xFFFF_FFFFL);
            case REG_MTIME_HI -> (mtime >>> 32) & 0xFFFF_FFFFL;
            case REG_MTIMECMP_LO -> mtimecmp & 0xFFFF_FFFFL;
            case REG_MTIMECMP_HI -> (mtimecmp >>> 32) & 0xFFFF_FFFFL;
            case REG_CTRL -> enabled ? 1 : 0;
            case REG_STATUS -> pending ? 1 : 0;
            case REG_DIV -> div;
            case REG_CYCLES_LO -> totalCycles & 0xFFFF_FFFFL;
            case REG_CYCLES_HI -> (totalCycles >>> 32) & 0xFFFF_FFFFL;
            default -> throw new MemoryAccessException(offset, name + ": bad register offset");
        };
    }

    @Override
    public void store(int offset, long value, int size) {
        final int reg = offset & ~3;
        final long v = value & 0xFFFF_FFFFL;
        switch (reg) {
            case REG_MTIME_LO -> mtime = (mtime & 0xFFFF_FFFF_0000_0000L) | v;
            case REG_MTIME_HI -> mtime = (mtime & 0xFFFF_FFFFL) | (v << 32);
            case REG_MTIMECMP_LO -> {
                mtimecmp = (mtimecmp & 0xFFFF_FFFF_0000_0000L) | v;
                // ★ 写 mtimecmp **就是**"启用定时器"这个动作：标准 CLINT 根本没有独立的使能位。
                //   真机教训（2026-09-18，症状是"进系统后打不了字"）：FreeRTOS 的 RISC-V port
                //   只写 configMTIMECMP_BASE_ADDRESS（=0x10001008），从不碰我们自创的 0x10 ctrl
                //   ⇒ enabled 永远 false ⇒ pending 永不置位 ⇒ **MTIP 永不送达** ⇒ tick 中断不发生
                //   ⇒ vTaskDelay 永不返回 ⇒ shell/beat/con 任务从未跑过（键盘只是最显眼的症状）。
                enabled = true;
            }
            case REG_MTIMECMP_HI -> {
                mtimecmp = (mtimecmp & 0xFFFF_FFFFL) | (v << 32);
                enabled = true;      // 同上：port 会先写 hi 再写 lo（防误触发），两次都算启用
            }
            case REG_CTRL -> {
                enabled = (v & 1) != 0;
                if (enabled && mtime >= mtimecmp) {
                    pending = true;   // 使能瞬间已到期（避免遗漏）
                }
            }
            case REG_STATUS -> pending = false;   // 写任意值清除
            case REG_DIV -> div = (int) Math.max(1, Math.min(v, 0xFFFF));
            default -> throw new MemoryAccessException(offset, name + ": bad register offset (write)");
        }
    }

    @Override
    public void step(int cycles) {
        if (cycles <= 0) {
            return;
        }
        totalCycles += cycles;
        final int d = Math.max(1, div);
        final long ticks = cycles / d;
        if (ticks <= 0) {
            return;
        }
        mtime += ticks;
        if (enabled && !pending && mtime >= mtimecmp) {
            pending = true;
        }
    }

    /**
     * 中断条件 = <b>电平语义</b>（mtime 到达比较值），与真实 CLINT 一致。
     *
     * <p>⚠ 曾经写成"锁存"（{@code enabled && pending}，要写 STATUS 才清）—— 真机后果很隐蔽：
     * FreeRTOS 每个 tick 都会把 {@code mtimecmp} 推到下一个到期点，靠的正是"比较条件不再成立
     * 就自然撤除"；锁存位不让它撤除 ⇒ MTIP 恒高 ⇒ guest 交付完立刻又进中断 ⇒
     * <b>中断风暴</b>，任务永远拿不到 CPU（症状：第一个 tick 的钩子打过一次，之后
     * "console ready." 之类再也不出现、键盘毫无反应）。</p>
     *
     * <p>{@code pending} 保留为"曾经到期"的**诊断位**（{@code REG_STATUS} 读它、写清它），
     * 但**不参与**中断判定。</p>
     */
    @Override
    public boolean isInterrupting(int irq) {
        return enabled && mtime >= mtimecmp;
    }

    @Override
    public void reset() {
        mtime = 0;
        mtimecmp = 0;
        totalCycles = 0;
        div = 1;
        enabled = false;
        pending = false;
    }

    // ==================== 宿主 API ====================

    public long getMtime() {
        return mtime;
    }

    public long getMtimecmp() {
        return mtimecmp;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPending() {
        return pending;
    }

    /** 当前等效频率（Hz；由宿主按 tick 预算换算，供 UI 显示） */
    public double effectiveHz(int cyclesPerTick, double ticksPerSecond) {
        return cyclesPerTick * ticksPerSecond / Math.max(1, div);
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name + "[mtime=" + mtime + " cmp=" + mtimecmp + " en=" + enabled + " pend=" + pending + "]";
    }
}
