package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== 调试控制台（MMIO 单字节输出，纯 Java 零 MC）=====
 *
 * <p>用户 2026-09-24："可以通过定义 #define + LOG 的形式打印输出，把 uart0 输出转接到屏幕上"。</p>
 *
 * <p><b>为什么必须要有它</b>：UART 会在系统初始化时被重新配置（分频/使能），于是"引导阶段之后"
 * 的固件输出在宿主看来就像消失了 —— 实测正是这一点把诊断误导了两轮（一度以为固件没有重复输出）。
 * 调试控制台是一个<b>独立、无状态、永远可用</b>的 MMIO 字节口：固件往 {@link
 * com.hdf.cryptand.soc.board.OcBoardLayout#DEBUG_BASE} 写一个字节，宿主立刻拿到，
 * 完全绕开 UART 的任何配置。</p>
 *
 * <p>固件侧由 `CRYPTAND_DEBUG_CONSOLE` 控制是否镜像（见 `hal.c` 的 `hal_uart_putc`）。</p>
 */
public final class DebugConsoleDevice implements MemoryMappedDevice {

    private final String name;
    private java.util.function.IntConsumer sink = b -> {
    };
    private long writes;

    public DebugConsoleDevice(String name) {
        this.name = name == null ? "DEBUG-CONSOLE" : name;
    }

    public void setSink(java.util.function.IntConsumer s) {
        this.sink = s == null ? b -> {
        } : s;
    }

    /** 写进来的字节数（诊断：能直接看出"固件到底输出了多少"） */
    public long writeCount() {
        return writes;
    }

    @Override
    public int getLength() {
        return Sizes.SIZE_32;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_8_LOG2) | (1 << Sizes.SIZE_16_LOG2) | (1 << Sizes.SIZE_32_LOG2);
    }

    @Override
    public long load(int offset, int size) {
        return 0;                       // 只写：读回 0（避免固件误当状态寄存器）
    }

    @Override
    public void store(int offset, long rawValue, int size) {
        writes++;
        if (size == Sizes.SIZE_8) {
            sink.accept((int) (rawValue & 0xFF));
            return;
        }
        for (int i = 0; i < size; i++) {
            sink.accept((int) ((rawValue >>> (i * 8)) & 0xFF));
        }
    }

    @Override
    public String toString() {
        return name + "(debug console, " + writes + " bytes)";
    }
}
