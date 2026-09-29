package com.hdf.cryptand.soc.link;

import java.util.List;

/**
 * ===== 链路协议（2026-09-28，纯 Java 零 MC）=====
 *
 * <p>用户定案：<b>每个设备声明「支持接口列表」，列表顺序即优先级 —— 最前面的协议优先对接</b>；
 * 两端连线时取交集里<b>全局优先级最小</b>的那个协议；没有交集就接不上。</p>
 *
 * <p>协议是**总线/链路**，不是具体外设：{@link #PCIE}/{@link #USB} 接扩展卡与外设（设计原话
 * "外置模块的 CPU 可以通过 PCIE 或 SPI … 其他 USB 这种接口也可以连接外置模块"），
 * {@link #DP}/{@link #SPI}/{@link #UART}/{@link #I2C}/{@link #P8080} 是显示与外设侧通道。</p>
 *
 * <p>{@link #priority} 是全局顺序；具体设备的列表**必须按 priority 升序**（列表顺序 = 优先级，
 * 闸门里有断言），这样不会出现两套口径。{@link #bitsPerSecond} 只用于**木桶速率**
 * （链路速率 = 整条路径上最低的那一段，用户裁定 I）。</p>
 */
public enum LinkProtocol {

    /** PCI Express：扩展卡/外置模块的正路。 */
    PCIE("pcie", 0, 8_000_000_000L, 4),
    /** DisplayPort：真彩屏像素输出（GPU 的主输出）。一路一屏 ⇒ 点对点。 */
    DP("dp", 1, 4_000_000_000L, 1),
    /** USB：键盘/存储/外设（含 USB 扩展）。经 hub 可挂多台。 */
    USB("usb", 2, 5_000_000_000L, 8),
    /** SPI：小屏初始化/传感器/桥接。多从（片选）⇒ 可挂多台。 */
    SPI("spi", 3, 50_000_000L, 4),
    /** UART：串口终端/调试。**点对点**（用户 2026-09-29 定案）。 */
    UART("uart", 4, 921_600L, 1),
    /** I2C：低速控制。多从总线 ⇒ 可挂多台。 */
    I2C("i2c", 5, 400_000L, 8),
    /** 8080 并口：老式 TFT。点对点。 */
    P8080("8080", 6, 20_000_000L, 1);

    private final String id;
    private final int priority;
    private final long bitsPerSecond;
    private final int maxDevices;

    LinkProtocol(String id, int priority, long bitsPerSecond, int maxDevices) {
        this.id = id;
        this.priority = priority;
        this.bitsPerSecond = bitsPerSecond;
        this.maxDevices = maxDevices;
    }

    /**
     * 这条链路（协议）上**能挂几台设备** —— 点对点协议 = 1，总线协议 &gt; 1。
     *
     * <p>用户 2026-09-29 定案：「端点能连接设备是否具备多设备连接<b>取决于协议</b>，比如 UART 一般就是点对点」。
     * 所以"每点最多几个连接"不是设备属性，而是**协议属性**：{@link #UART}/{@link #DP}/{@link #P8080} = 1，
     * {@link #I2C}/{@link #USB}/{@link #SPI}/{@link #PCIE} 可多设备。</p>
     */
    public int maxDevices() {
        return maxDevices;
    }

    public String id() {
        return id;
    }

    /** 全局优先级：数值越小越优先。 */
    public int priority() {
        return priority;
    }

    public long bitsPerSecond() {
        return bitsPerSecond;
    }

    /** 找不到返回 null（调用方自己决定怎么报错）。 */
    public static LinkProtocol byId(String id) {
        if (id == null) {
            return null;
        }
        for (final LinkProtocol p : values()) {
            if (p.id.equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 协商：取双方列表交集里优先级最小的协议；没有交集返回 {@code null}。
     *
     * <p>与参数顺序无关（判据是全局 priority，不是谁的列表更靠前）。</p>
     */
    public static LinkProtocol firstCommon(List<LinkProtocol> a, List<LinkProtocol> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return null;
        }
        LinkProtocol best = null;
        for (final LinkProtocol p : a) {
            if (p == null || !b.contains(p)) {
                continue;
            }
            if (best == null || p.priority < best.priority) {
                best = p;
            }
        }
        return best;
    }
}
