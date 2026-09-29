package com.hdf.cryptand.soc.memory;

import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMap;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Sizes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ===== 物理地址空间实现（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>设备区间表 + 地址译码。<b>越界/未映射/跨设备访问一律抛
 * {@link MemoryAccessException}</b>——由 CPU 转为陷阱（BusFault 语义），
 * 绝不做静默读写。</p>
 *
 * <p>设备数量为个位数（MCU 级别），线性查找即可，无需树结构。</p>
 */
public final class SimpleMemoryMap implements MemoryMap {

    /** 一段设备映射 [start, end) */
    private static final class Mapping {
        final long start;
        final long end;
        final MemoryMappedDevice device;

        Mapping(long start, long end, MemoryMappedDevice device) {
            this.start = start;
            this.end = end;
            this.device = device;
        }
    }

    private final List<Mapping> mappings = new ArrayList<>();

    @Override
    public boolean addDevice(long address, MemoryMappedDevice device) {
        if (device == null || address < 0 || device.getLength() <= 0) {
            return false;
        }
        final long end = address + device.getLength();
        if (end < address) {
            return false;   // 溢出
        }
        for (Mapping m : mappings) {
            if (address < m.end && end > m.start) {
                return false;   // 与既有映射重叠（不做隐式覆盖）
            }
        }
        mappings.add(new Mapping(address, end, device));
        mappings.sort(Comparator.comparingLong(m -> m.start));
        return true;
    }

    @Override
    public void removeDevice(MemoryMappedDevice device) {
        mappings.removeIf(m -> m.device == device);
    }

    @Override
    public long[] getMemoryRange(MemoryMappedDevice device) {
        for (Mapping m : mappings) {
            if (m.device == device) {
                return new long[]{m.start, m.end};
            }
        }
        return null;
    }

    @Override
    public MemoryMappedDevice getDeviceAt(long address) {
        final Mapping m = find(address);
        return m == null ? null : m.device;
    }

    @Override
    public long load(long address, int size) {
        final Mapping m = resolve(address, size, "load");
        return m.device.load((int) (address - m.start), size);
    }

    @Override
    public void store(long address, long value, int size) {
        final Mapping m = resolve(address, size, "store");
        m.device.store((int) (address - m.start), value, size);
    }

    @Override
    public List<MemoryMappedDevice> getDevices() {
        final List<MemoryMappedDevice> devices = new ArrayList<>(mappings.size());
        for (Mapping m : mappings) {
            devices.add(m.device);
        }
        return devices;
    }

    // ==================== 内部 ====================

    private Mapping find(long address) {
        for (Mapping m : mappings) {
            if (address >= m.start && address < m.end) {
                return m;
            }
        }
        return null;
    }

    /** 定位并校验：整个访问区间必须落在同一设备内，且设备支持该宽度 */
    private Mapping resolve(long address, int size, String op) {
        if (address < 0) {
            throw new MemoryAccessException(address, op + ": negative address");
        }
        final Mapping m = find(address);
        if (m == null) {
            throw new MemoryAccessException(address, op + ": unmapped address");
        }
        if (address + size > m.end) {
            throw new MemoryAccessException(address, op + ": crosses device boundary");
        }
        final int sizeBit = 1 << Integer.numberOfTrailingZeros(size);
        if ((m.device.getSupportedSizes() & sizeBit) == 0) {
            throw new MemoryAccessException(address, op + ": device does not support size " + size);
        }
        return m;
    }

    /** 诊断：地址空间布局（一行一设备） */
    public String describe() {
        final StringBuilder sb = new StringBuilder();
        for (Mapping m : mappings) {
            sb.append(String.format("0x%08X-0x%08X  %s (%d bytes)%n",
                    m.start, m.end - 1, m.device.getClass().getSimpleName(), m.device.getLength()));
        }
        return sb.toString();
    }

    /** 便捷方法：判断是否支持该宽度（供装配期校验） */
    public static boolean supports(long sizesMask, int size) {
        return (sizesMask & (1 << Integer.numberOfTrailingZeros(size))) != 0;
    }

    /** Sizes 掩码辅助（装配期常用） */
    public static int maskOf(int... sizes) {
        int mask = 0;
        for (int s : sizes) {
            mask |= 1 << Integer.numberOfTrailingZeros(s);
        }
        return mask;
    }

    static {
        // 编译期常量引用，确保 Sizes 语义与本文档一致
        assert Sizes.SIZE_32 == 4;
    }
}
