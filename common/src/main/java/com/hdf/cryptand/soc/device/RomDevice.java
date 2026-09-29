package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryAccessException;

/**
 * ===== ROM 设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>只读固件存储：写入触发 store 访问错误（真实 ROM 语义）。装配期用
 * {@link #writeImage} 载入固件镜像。</p>
 */
public final class RomDevice extends RamDevice {

    public RomDevice(int length) {
        super(length, "ROM");
    }

    public RomDevice(int length, String name) {
        super(length, name);
    }

    @Override
    public void store(int offset, long value, int size) {
        throw new MemoryAccessException(offset, getName() + ": read-only (ROM)");
    }
}
