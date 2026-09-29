package com.hdf.cryptand.soc.api;

/**
 * ===== 可复位设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>复位到上电初始状态（真实芯片的 RESET 引脚语义）。</p>
 */
public interface Resettable {

    void reset();
}
