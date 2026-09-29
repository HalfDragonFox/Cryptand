package com.hdf.cryptand.soc.api;

/**
 * ===== MMIO 设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>可由 {@link MemoryMap} 映射到物理地址空间的设备——CPU 用与访问 RAM 相同的
 * 机制访问它（load/store）。<b>设备是沙箱能力表的唯一入口</b>：SoC 能做什么，
 * 取决于挂了哪些设备。</p>
 */
public interface MemoryMappedDevice {

    /** 该设备占用的字节数（{@link MemoryMap} 据此计算映射区间） */
    int getLength();

    /** 支持的访问宽度掩码（{@link Sizes}；默认 8/16/32 位） */
    default int getSupportedSizes() {
        return (1 << Sizes.SIZE_8_LOG2) | (1 << Sizes.SIZE_16_LOG2) | (1 << Sizes.SIZE_32_LOG2);
    }

    /**
     * 读设备偏移处的值。
     *
     * @param offset 相对设备基址的偏移
     * @param size   访问宽度（{@link Sizes} 的 {@code SIZE_*}）
     * @return 读出的值（零扩展至 long）
     */
    long load(int offset, int size);

    /**
     * 写设备偏移处的值。
     *
     * @param offset 相对设备基址的偏移
     * @param value  待写入的值（低 {@code size} 字节有效）
     * @param size   访问宽度（{@link Sizes} 的 {@code SIZE_*}）
     */
    void store(int offset, long value, int size);
}
