package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.InterruptSource;
import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Resettable;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== ADC 设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>固件读通道采样值（如电网电压/电流）→ 闭环控制输入；宿主在世界侧写
 * {@link #setChannel}（<b>真实采样链路的游戏内对应</b>）。</p>
 *
 * <h3>寄存器</h3>
 * <pre>
 *   0x00..0x1C  ch0..ch7   通道采样值（12 位无符号；只读）
 *   0x20        ctrl       bit0 = 使能；bit8..15 = 当前通道选择
 *   0x24        status     bit0 = 数据就绪（写 1 清除；使能时置位 → 可拉中断）
 *   0x28        fullScale  满量程（默认 4095；宿主可写以改变量纲）
 * </pre>
 */
public final class AdcDevice implements MemoryMappedDevice, InterruptSource, Resettable {

    public static final int CHANNEL_COUNT = 8;
    private static final int REG_CTRL = 0x20;
    private static final int REG_STATUS = 0x24;
    private static final int REG_FULL_SCALE = 0x28;
    private static final int LENGTH = 0x2C;

    private final int[] channels = new int[CHANNEL_COUNT];
    private boolean enabled;
    private boolean dataReady;
    private int fullScale = 4095;
    private int selectedChannel;
    private final String name;

    public AdcDevice() {
        this("ADC");
    }

    public AdcDevice(String name) {
        this.name = name == null ? "ADC" : name;
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
        if (reg < CHANNEL_COUNT * 4) {
            return channels[reg / 4] & 0xFFFF_FFFFL;
        }
        return switch (reg) {
            case REG_CTRL -> (enabled ? 1 : 0) | (selectedChannel << 8);
            case REG_STATUS -> dataReady ? 1 : 0;
            case REG_FULL_SCALE -> fullScale & 0xFFFF_FFFFL;
            default -> throw new MemoryAccessException(offset, name + ": bad register");
        };
    }

    @Override
    public void store(int offset, long rawValue, int size) {
        final int reg = offset & ~3;
        final int value = (int) rawValue;
        switch (reg) {
            case REG_CTRL -> {
                enabled = (value & 1) != 0;
                selectedChannel = (value >>> 8) & 0xFF;
                if (enabled) {
                    dataReady = true;
                }
            }
            case REG_STATUS -> {
                if ((value & 1) != 0) {
                    dataReady = false;
                }
            }
            case REG_FULL_SCALE -> fullScale = Math.max(1, value);
            default -> {
                if (reg >= CHANNEL_COUNT * 4) {
                    throw new MemoryAccessException(offset, name + ": bad register (write)");
                }
                // 通道寄存器只读（忽略）
            }
        }
    }

    // ==================== 宿主 API（世界侧采样） ====================

    /** 写入通道采样值（宿主：把电网电压/电流换算成 0..fullScale） */
    public void setChannel(int channel, int value) {
        if (channel >= 0 && channel < CHANNEL_COUNT) {
            channels[channel] = Math.max(0, Math.min(value, fullScale));
            if (enabled) {
                dataReady = true;
            }
        }
    }

    public int getChannel(int channel) {
        return channel >= 0 && channel < CHANNEL_COUNT ? channels[channel] : 0;
    }

    /** 归一化读值 0..1（宿主/固件换算用） */
    public double normalized(int channel) {
        return (double) getChannel(channel) / Math.max(1, fullScale);
    }

    public int fullScale() {
        return fullScale;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean isInterrupting(int irq) {
        return enabled && dataReady;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(channels, 0);
        enabled = false;
        dataReady = false;
        fullScale = 4095;
        selectedChannel = 0;
    }

    @Override
    public String toString() {
        return name + "[ch0=" + channels[0] + " full=" + fullScale + " ready=" + dataReady + "]";
    }
}
