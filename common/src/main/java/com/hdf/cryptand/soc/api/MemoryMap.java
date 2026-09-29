package com.hdf.cryptand.soc.api;

import java.util.List;

/**
 * ===== 物理地址空间（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>设备到物理地址的映射表：CPU 的每次访存都先经这里定位设备。
 * 与真实 SoC 的地址译码器同构。</p>
 *
 * <p><b>越界/未映射一律抛 {@link MemoryAccessException}</b>——由 CPU 转为故障态，
 * 而不是静默读写（真实芯片的 BusFault 语义）。</p>
 */
public interface MemoryMap {

    /**
     * 在指定物理地址挂载设备。
     *
     * @return 成功返回 true；与既有映射重叠则返回 false（不做隐式覆盖）
     */
    boolean addDevice(long address, MemoryMappedDevice device);

    /** 卸载设备（不在本表中则无操作） */
    void removeDevice(MemoryMappedDevice device);

    /** 设备当前占用的物理区间 {@code [start, end)}；未挂载返回 null */
    long[] getMemoryRange(MemoryMappedDevice device);

    /** 该地址命中的设备；未映射返回 null */
    MemoryMappedDevice getDeviceAt(long address);

    /** 读字节（不越映射边界；跨界视为访问错误） */
    long load(long address, int size);

    /** 写字节（不越映射边界） */
    void store(long address, long value, int size);

    /** 已挂载的全部设备（装配/诊断/序列化用） */
    List<MemoryMappedDevice> getDevices();
}
