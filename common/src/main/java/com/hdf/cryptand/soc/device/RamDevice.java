package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== RAM 设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>字节数组实现的读写内存，支持 8/16/32 位访问（小端）。长度即"芯片内存规格"，
 * 属于沙箱配额的一部分（不可动态扩张）。</p>
 */
public class RamDevice implements MemoryMappedDevice {

    protected final byte[] data;
    private final String name;

    public RamDevice(int length) {
        this(length, "RAM");
    }

    public RamDevice(int length, String name) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
        this.data = new byte[length];
        this.name = name;
    }

    @Override
    public int getLength() {
        return data.length;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_8_LOG2) | (1 << Sizes.SIZE_16_LOG2) | (1 << Sizes.SIZE_32_LOG2);
    }

    @Override
    public long load(int offset, int size) {
        checkRange(offset, size);
        return switch (size) {
            case Sizes.SIZE_8 -> data[offset] & 0xFFL;
            case Sizes.SIZE_16 -> (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8);
            case Sizes.SIZE_32 -> (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | ((data[offset + 2] & 0xFFL) << 16)
                    | ((data[offset + 3] & 0xFFL) << 24);
            default -> throw new MemoryAccessException(offset, "unsupported size " + size);
        };
    }

    @Override
    public void store(int offset, long value, int size) {
        checkRange(offset, size);
        switch (size) {
            case Sizes.SIZE_8 -> data[offset] = (byte) value;
            case Sizes.SIZE_16 -> {
                data[offset] = (byte) value;
                data[offset + 1] = (byte) (value >>> 8);
            }
            case Sizes.SIZE_32 -> {
                data[offset] = (byte) value;
                data[offset + 1] = (byte) (value >>> 8);
                data[offset + 2] = (byte) (value >>> 16);
                data[offset + 3] = (byte) (value >>> 24);
            }
            default -> throw new MemoryAccessException(offset, "unsupported size " + size);
        }
    }

    /** 直接写入镜像（加载固件；绕过 store 的宽度/只读约束） */
    public void writeImage(int offset, byte[] image, int imageOffset, int length) {
        System.arraycopy(image, imageOffset, data, offset, length);
    }

    /** 读出全部内容（快照/存档/自测） */
    public byte[] snapshot() {
        return data.clone();
    }

    /** 设备名（UI/诊断） */
    public String getName() {
        return name;
    }

    protected final void checkRange(int offset, int size) {
        if (offset < 0 || offset + size > data.length) {
            throw new MemoryAccessException(offset, name + ": out of bounds (len=" + data.length + ")");
        }
    }
}
