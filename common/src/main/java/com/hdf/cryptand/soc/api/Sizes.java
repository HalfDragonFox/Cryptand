package com.hdf.cryptand.soc.api;

/**
 * ===== SoC 访问宽度常量（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>内存/设备访问的宽度集合。{@code *_LOG2} 用于位掩码（如
 * {@code 1 << Sizes.SIZE_32_LOG2} 表示"支持 4 字节访问"）。</p>
 */
public interface Sizes {
    int SIZE_8_LOG2 = 0;
    int SIZE_16_LOG2 = 1;
    int SIZE_32_LOG2 = 2;
    int SIZE_64_LOG2 = 3;

    int SIZE_8 = 1;
    int SIZE_16 = 2;
    int SIZE_32 = 4;
    int SIZE_64 = 8;

    /** 全部宽度掩码（8/16/32/64） */
    int ALL = (1 << SIZE_8_LOG2) | (1 << SIZE_16_LOG2) | (1 << SIZE_32_LOG2) | (1 << SIZE_64_LOG2);
}
