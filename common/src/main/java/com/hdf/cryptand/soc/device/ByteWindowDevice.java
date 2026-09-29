package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== 字节窗口设备（模块接口层的"内存映射"载体，2026-09-18）=====
 *
 * <p>用户定案："对于 RV 来说要么就是内存直接写要么就是调用寄存器" —— 这是**内存映射**那一路：
 * 一段可以由 RV 按 8/16/32 位读写的普通窗口，写入/读取都通过回调交给模块层处理。</p>
 *
 * <p>与 {@code RegBankDevice} 的分工：RegBank 是"32 位寄存器阵列"（控制语义），
 * 本类是"字节流窗口"（数据语义：TX/RX FIFO、显存、共享缓冲）。两者都属于模块接口层，
 * 但一个给寄存器映射、一个给内存映射，正好对应 RV 的两种访问方式。</p>
 */
public final class ByteWindowDevice implements MemoryMappedDevice {

    /** 窗口回调：模块层在这里接住 RV 的读写 */
    public interface Hooks {

        /** RV 写了一次（offset 相对窗口基址） */
        default void onStore(int offset, long value, int size) {
        }

        /** RV 读了一次；返回该次读取的值（默认从窗口存储里取） */
        default Long onLoad(int offset, int size) {
            return null;
        }
    }

    private final String name;
    private final byte[] bytes;
    private final Hooks hooks;
    private long stores;
    private long loads;

    public ByteWindowDevice(String name, int size, Hooks hooks) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        this.name = name == null ? "BYTEWIN" : name;
        this.bytes = new byte[size];
        this.hooks = hooks == null ? new Hooks() {
        } : hooks;
    }

    public String name() {
        return name;
    }

    public long stores() {
        return stores;
    }

    public long loads() {
        return loads;
    }

    /** 直接读窗口存储的一个字节（模块层给自己用，例如把外设数据放进 RX FIFO） */
    public byte get(int offset) {
        return bytes[offset];
    }

    /** 直接写窗口存储的一个字节（模块层给自己用） */
    public void put(int offset, byte value) {
        bytes[offset] = value;
    }

    @Override
    public int getLength() {
        return bytes.length;
    }

    @Override
    public long load(int offset, int size) {
        check(offset, size);
        final Long hooked = hooks.onLoad(offset, size);
        if (hooked != null) {
            loads++;
            return hooked;
        }
        long value = 0;
        for (int i = 0; i < size; i++) {
            value |= ((long) (bytes[offset + i] & 0xFF)) << (8 * i);      // 小端
        }
        loads++;
        return value;
    }

    @Override
    public void store(int offset, long value, int size) {
        check(offset, size);
        for (int i = 0; i < size; i++) {
            bytes[offset + i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        stores++;
        hooks.onStore(offset, value, size);
    }

    private void check(int offset, int size) {
        if (offset < 0 || size <= 0 || offset + size > bytes.length) {
            throw new MemoryAccessException(offset, name + ": 越界或宽度非法（窗口 " + bytes.length + " 字节）");
        }
        if (size != Sizes.SIZE_8 && size != Sizes.SIZE_16 && size != Sizes.SIZE_32) {
            throw new MemoryAccessException(offset, name + ": 只支持 8/16/32 位访问");
        }
    }
}
