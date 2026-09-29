package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Resettable;
import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.api.Steppable;

/**
 * ===== PWM 设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>固件写周期/占空比 → 硬件输出方波；宿主读 {@link #dutyRatio()} 驱动机器转速
 * （<b>真实电机控制的核心外设</b>，与 Cryptand 主题直接咬合）。</p>
 *
 * <h3>寄存器</h3>
 * <pre>
 *   0x00 period   周期（SoC 周期数；0 = 关闭输出）
 *   0x04 duty     高电平周期数（0..period）
 *   0x08 ctrl     bit0 = 使能
 *   0x0C counter  当前计数（只读）
 *   0x10 output   当前输出电平 0/1（只读）
 *   0x14 status   状态位（**有状态位的外设才叫"耗时操作"**，用户定案 #6）：
 *                    bit0 = READY：已使能且周期有效（输出在跑）—— 只读，硬件状态
 *                    bit1 = EDGE ：自上次读以来输出电平**变过**（粘滞位；写 1 清除）
 * </pre>
 *
 * <h3>为什么需要 EDGE 粘滞位（而不是让固件去轮询 0x10 output）</h3>
 * <p>固件两次读 {@code output} 之间的翻转是会被漏掉的（读-读之间硬件自己动了）——
 * 这正是"寄存器要额外状态位"的理由（用户定案：{@code 「uart 等接口由于也是耗时操作，
 * 所以需要额外寄存器位表示状态」}）。EDGE 位把"曾经变过"这件事**记住**，固件读一次
 * STATUS 就知道该不该去读 OUTPUT，写 1 才清除（W1C，与 {@code AdcDevice.REG_STATUS} 同风格）。</p>
 */
public final class PwmDevice implements MemoryMappedDevice, Steppable, Resettable {

    private static final int REG_STATUS = 0x14;
    private static final int LENGTH = 0x18;

    /** status.bit0：已使能且周期有效 */
    public static final int STATUS_READY = 1;
    /** status.bit1：自上次读以来输出电平变过（写 1 清除） */
    public static final int STATUS_EDGE = 2;

    private int period = 1000;
    private int duty;
    private boolean enabled;
    private int counter;
    private boolean output;
    /** EDGE 粘滞位：翻转就置位，固件读 STATUS 后写 1 清除 */
    private boolean edgeSticky;
    private final String name;

    public PwmDevice() {
        this("PWM");
    }

    public PwmDevice(String name) {
        this.name = name == null ? "PWM" : name;
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
        return switch (offset & ~3) {
            case 0x00 -> period & 0xFFFF_FFFFL;
            case 0x04 -> duty & 0xFFFF_FFFFL;
            case 0x08 -> enabled ? 1 : 0;
            case 0x0C -> counter & 0xFFFF_FFFFL;
            case 0x10 -> output ? 1 : 0;
            case REG_STATUS -> status();
            default -> throw new MemoryAccessException(offset, name + ": bad register");
        };
    }

    @Override
    public void store(int offset, long rawValue, int size) {
        final int value = (int) rawValue;
        switch (offset & ~3) {
            case 0x00 -> period = Math.max(0, value);
            case 0x04 -> duty = Math.max(0, value);
            case 0x08 -> enabled = (value & 1) != 0;
            case REG_STATUS -> {
                // W1C：只清 EDGE；READY 是硬件状态（enabled && period>0），清不掉也不需要清
                if ((value & STATUS_EDGE) != 0) {
                    edgeSticky = false;
                }
            }
            default -> throw new MemoryAccessException(offset, name + ": bad register (write)");
        }
    }

    @Override
    public void step(int cycles) {
        if (cycles <= 0 || !enabled || period <= 0) {
            setOutput(false);
            return;
        }
        // 批量推进下唯一能判断的"曾经翻转"：一步跨过整个周期 ⇒ 中间必然有边沿。
        // （不这么做的话 step(1000) 只看首尾，中间翻转全丢 —— 固件由此漏事件。）
        if (cycles >= period) {
            edgeSticky = true;
        }
        counter = (int) ((counter + (long) cycles) % period);
        setOutput(counter < Math.min(duty, period));
    }

    /** 更新输出电平并记下"变过"（EDGE 粘滞位靠这里置位） */
    private void setOutput(boolean value) {
        if (value != output) {
            output = value;
            edgeSticky = true;
        }
    }

    @Override
    public void reset() {
        period = 1000;
        duty = 0;
        enabled = false;
        counter = 0;
        output = false;
        edgeSticky = false;
    }

    // ==================== 宿主 API ====================

    /** 状态位图（{@link #STATUS_READY} / {@link #STATUS_EDGE}） */
    public int status() {
        return (enabled && period > 0 ? STATUS_READY : 0) | (edgeSticky ? STATUS_EDGE : 0);
    }

    /** 输出电平自上次 {@code STATUS} 读/清以来是否变过（等价 STATUS 的 EDGE 位） */
    public boolean outputChanged() {
        return edgeSticky;
    }

    /** 占空比 0..1（宿主驱动机器转速/加热功率） */
    public double dutyRatio() {
        if (!enabled || period <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) Math.min(duty, period) / period);
    }

    public boolean outputHigh() {
        return output;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPeriod() {
        return period;
    }

    public int getDuty() {
        return duty;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name + "[period=" + period + " duty=" + duty + " en=" + enabled
                + " ratio=" + String.format("%.3f", dutyRatio()) + "]";
    }
}
