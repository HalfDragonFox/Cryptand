package com.hdf.cryptand.soc.api;

/**
 * ===== SoC 内存访问异常（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>设备访问失败（地址越界 / 宽度不支持 / 未映射）时抛出。CPU 侧捕获后转为
 * 故障态（见 {@link SocFault}），<b>绝不逃逸到宿主</b>。</p>
 */
public class MemoryAccessException extends RuntimeException {

    private final long address;

    public MemoryAccessException(long address, String message) {
        super(message + " (address=0x" + Long.toHexString(address) + ")");
        this.address = address;
    }

    /** 触发异常的物理地址 */
    public long getAddress() {
        return address;
    }
}
