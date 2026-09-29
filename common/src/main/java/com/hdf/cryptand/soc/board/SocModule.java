package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.peripheral.PeripheralMap;

/**
 * ===== 一个板级模块（虚拟机创建，RV 只看到地址与寄存器，2026-09-18）=====
 *
 * <p>用户定案："虚拟机可以定义创建 RV 的模块，比如 UART、SPI 等各种模块，然后 RV 有哪些模块也是
 * 虚拟机控制，然后底层就是接口 + 速率，不管 PCIE 等，不用模仿真实，只需要定义接口 + 速率 +
 * 寄存器映射之类的即可"。</p>
 *
 * <p>所以这个 record 就只有三要素：</p>
 * <ol>
 *   <li><b>接口</b>：{@link PeripheralMap.Carrier} —— 复用外设映射层的载体枚举，不留第二套；
 *       它同时决定"这条链路要占几根 IO 线"（见 {@code PeripheralMap}）。</li>
 *   <li><b>速率</b>：{@code bytesPerSecond}（0 = 控制类寄存器，不受带宽限）；
 *       带宽纪律由 {@code AutoForwardWindowDevice} 那样的窗口设备兑现。</li>
 *   <li><b>寄存器映射</b>：{@code baseAddress} + {@code spanBytes} + 中断号 {@code irq}
 *       —— 固件只认这三件事。</li>
 * </ol>
 *
 * <p>另外记一个 {@code deviceId}（这个模块挂着哪个设备；纯控制器为 null）。</p>
 *
 * <p>⚠ <b>刻意不建模总线协议细节</b>（PCIe 链路训练、I2C 仲裁、8080 的建立/保持时间、差分对电气特性…）：
 * 对游戏零收益，而我们真正需要的是"这台机器能挂几个模块、每个多快、寄存器在哪"。
 * 真实感留给"接口类型 + 速率"这两条足够表达差异的量。</p>
 */
public record SocModule(String name, PeripheralMap.Carrier iface, long bytesPerSecond,
                        long baseAddress, int spanBytes, int irq, String deviceId) {

    public SocModule {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("module name must not be empty");
        }
        if (iface == null) {
            throw new IllegalArgumentException(name + ": iface must not be null");
        }
        if (bytesPerSecond < 0) {
            throw new IllegalArgumentException(name + ": bytesPerSecond must be >= 0");
        }
        if (baseAddress < 0) {
            throw new IllegalArgumentException(name + ": baseAddress must be >= 0");
        }
        if (spanBytes <= 0) {
            throw new IllegalArgumentException(name + ": spanBytes must be > 0");
        }
    }

    /** 窗口结束地址（开区间） */
    public long endAddress() {
        return baseAddress + spanBytes;
    }

    /** 是否带中断线 */
    public boolean hasIrq() {
        return irq >= 0;
    }

    /** 两个模块的寄存器窗口是否重叠（真机上重叠 = 装配错误，必须拒绝） */
    public boolean overlaps(SocModule other) {
        return baseAddress < other.endAddress() && other.baseAddress < endAddress();
    }

    /** 是不是控制类模块（速率 0：定时器/调试口/心跳区这类不受带宽约束） */
    public boolean controlOnly() {
        return bytesPerSecond == 0;
    }

    @Override
    public String toString() {
        return String.format("%s[%s] @0x%08X+%d %s%s", name, iface.label(), baseAddress, spanBytes,
                bytesPerSecond == 0 ? "(control)" : (bytesPerSecond / 1000) + "KB/s",
                hasIrq() ? (" irq=" + irq) : "");
    }
}
