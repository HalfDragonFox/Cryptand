package com.hdf.cryptand.soc.peripheral;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ===== 外设映射层（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-18）："沙箱虚拟机的任务之一就是把外设映射为 USB 接口设备或者 UART 或者 SPI 等设备"、
 * "映射应该在操作系统或者安装系统时可以手动配置，当然默认自动接入"、"手动配置可以把设备映射为比如
 * 硬盘映射为 UART 接口也行，不手动则走默认自动"。</p>
 *
 * <h3>资源单位：组件槽位（不是"IO 口"）</h3>
 * <p>用户 2026-09-18 订正："MCU 也不用根据 IO 口来设置了，比如 UART 占用组件槽位为 2 个，SPI 为 4 个这种"。
 * 所以这里的资源就一种：<b>组件槽位</b>。每个接口按它固定占用的槽位数扣账：</p>
 * <ul>
 *   <li>UART = 2（TX/RX 两路）；I2C = 2（共享，挂多设备不再加）；SPI = 4（3 共享 + 每设备 1 片选）；</li>
 *   <li>8080 并口 = 13；FSMC = 24；USB = 2（共享）；</li>
 *   <li><b>PCIe = 16（固定消耗大量槽位）</b>，它下面再挂设备按 PCIe 规则共享带宽（见 SharedBus）。</li>
 * </ul>
 * <p>共享与独占的区分保留（这才让"总线挂多设备"与"点对点"有真实成本差异），但**不再叫引脚/IO 口**
 * —— 引脚级时序那条路我们不模拟（用户定案："一般不用操作 GPIO 口的，这样也不现实"）。</p>
 *
 * <h3>核心不变式</h3>
 * <ul>
 *   <li><b>组件调用语义只有一份</b>（"读这个盘的 512 字节"），<b>载体有多份</b>（mailbox / UART / SPI / 8080 …）。
 *       上层（固件里的文件系统、shell 命令）永远不该知道设备挂在哪条线上。</li>
 *   <li>槽位不够就报错并写明**差几个**，绝不静默挤掉设备或换载体。</li>
 *   <li><b>SOC 档默认走 mailbox</b>（共享内存 + handle 寻址，<b>不占槽位</b>），但允许把某个设备手动改到
 *       UART/USB/SPI —— 例如调试时把盘挂到 UART 上单独看。</li>
 * </ul>
 *
 * <p>⚠ 放 common 的理由：这是纯拓扑计算，跟 Minecraft 毫无关系，而它决定了"固件能不能访问某个设备"——
 * 沙盒里必须能构造各种档位/覆盖组合来验证，而不是靠插满物品的真机去试。</p>
 */
public final class PeripheralMap {

    /**
     * 载体：设备挂在哪条线上。
     *
     * @param sharedSlots    这条总线**首次使用**时一次性占用的槽位（I2C=2、SPI=3、USB=2…）
     * @param perDeviceSlots 每个设备额外占用的**独占**槽位（UART=2、SPI 的 CS=1…）
     * @param pointToPoint   是否点对点（点对点 ⇒ 一设备一控制器，通道号就是身份）
     * @param label          人类可读名（报错信息里用）
     */
    public enum Carrier {
        /**
         * 片上/内部模块：寄存器桥、定时器、调试口、心跳区这类**不属于任何总线**的东西。
         * **不占槽位、不受档位能力限制**（每个档位都有）—— 用户定案里的"模块"指外部链路，
         * 片上外设不在那个账里。
         */
        INTERNAL(0, 0, false, "片上"),
        /** 共享内存邮箱（SOC 默认）：多个设备靠 handle 区分，**不占槽位** */
        MAILBOX(0, 0, false, "共享内存邮箱"),
        /** 点对点串口：固定 2 个槽位 */
        UART(0, 2, true, "UART"),
        /** I2C 总线：2 个共享槽位，设备靠地址区分（地址分发逻辑在模块里，见 I2cDispatchModule） */
        I2C(2, 0, false, "I2C"),
        /** SPI 总线：3 共享 + 每设备 1 片选 = 4 */
        SPI(3, 1, false, "SPI"),
        /** 8080 并口：13 个槽位（D0-7 + WR/RD/CS/RS + RST） */
        PARALLEL8080(0, 13, true, "8080 并口"),
        /** FSMC 外部总线：24 个槽位（16 位数据 + 地址/片选/读写） */
        FSMC(0, 24, true, "FSMC"),
        /** USB 总线：2 个共享槽位，设备靠端点区分 */
        USB(2, 0, false, "USB"),
        /** QSPI：6 共享 + 每设备 1 片选 */
        QSPI(6, 1, false, "QSPI"),
        /** DisplayPort：4 个槽位（差分对 × 4，简化计数） */
        DP(0, 4, true, "DP"),
        /**
         * PCIe：槽位**按链路宽度**算（用户 2026-09-18："PCIE 等模块固定消耗大量组件槽位"、
         * "并且 PCIEx16 和 x8 等占用槽位数量不同"）—— 见 {@link #pcieSlots(PcieFabric.LinkWidth)}：
         * 每通道一对差分线 ⇒ 槽位 = lanes × 2（x1=2 / x4=8 / x8=16 / x16=32）。
         * 所以枚举里不写死数字，实际扣账在 {@link #plan} 的 PCIE 分支里按设备宽度计算。
         */
        PCIE(0, 0, true, "PCIe");

        private final int sharedSlots;
        private final int perDeviceSlots;
        private final boolean pointToPoint;
        private final String label;

        Carrier(int sharedSlots, int perDeviceSlots, boolean pointToPoint, String label) {
            this.sharedSlots = sharedSlots;
            this.perDeviceSlots = perDeviceSlots;
            this.pointToPoint = pointToPoint;
            this.label = label;
        }

        public int sharedSlots() {
            return sharedSlots;
        }

        public int perDeviceSlots() {
            return perDeviceSlots;
        }

        public boolean pointToPoint() {
            return pointToPoint;
        }

        public String label() {
            return label;
        }

        /** 首次使用该载体占用的槽位（总线共享 + 一个设备的独占） */
        public int firstUseSlots() {
            return sharedSlots + perDeviceSlots;
        }

        /** 之后每多一个设备占用的槽位（只有独占部分） */
        public int extraDeviceSlots() {
            return perDeviceSlots;
        }
    }

    /**
     * 一个待映射的设备（id 是稳定标识，kind 用于日志与默认策略）。
     *
     * @param linkWidth PCIe 设备申请的链路宽度（其它载体忽略它）—— 宽度决定占多少槽位：
     *                  x16 与 x8 占的数量**不同**（用户 2026-09-18 定案）
     */
    public record Device(String id, String kind, PcieFabric.LinkWidth linkWidth) {

        public Device {
            if (linkWidth == null) {
                linkWidth = PcieFabric.LinkWidth.X1;
            }
        }

        /** 非 PCIe 设备（默认 x1 宽度，用不到） */
        public Device(String id, String kind) {
            this(id, kind, PcieFabric.LinkWidth.X1);
        }
    }

    /**
     * PCIe 设备占用的组件槽位 = 链路宽度（通道数）× 2。
     *
     * <p>为什么是 ×2：一个 PCIe 通道是一对差分线（TX/RX），这与"槽位=线"的直觉一致，
     * 也让宽度差异直接体现在资源账上：x1 只要 2 个槽位，x16 要 32 个
     * —— 这正是用户说的"PCIe 固定消耗大量组件槽位，且 x16 与 x8 不同"。</p>
     */
    public static int pcieSlots(PcieFabric.LinkWidth width) {
        final PcieFabric.LinkWidth w = width == null ? PcieFabric.LinkWidth.X1 : width;
        // 每张 PCIe 设备 = **基础开销** + 每通道 2 个槽位（一对差分线）。
        // ⚠ 基础开销是故意留的：它让"两张 x8"比"一张 x16"多花一点（2×18=36 > 34），
        //   正是用户定案（"两个 x8 比单独 x16 要多一点消耗槽位"）—— 现实里两张卡各有一套
        //   接口/PHY/插槽，本来就比一张 x16 贵。同时保证 **x16 单卡槽位最高**（34 > 18 > 10 > …）。
        return PCIE_BASE_SLOTS + w.lanes() * 2;
    }

    /** 每张 PCIe 设备的基础槽位开销（接口/PHY/插槽；见 {@link #pcieSlots}） */
    public static final int PCIE_BASE_SLOTS = 2;

    /**
     * 映射结果：设备 → 载体 + 该载体上的通道号（UART 端口 / SPI 片选 / USB 端点 / mailbox 槽位）。
     *
     * @param slotsUsed 这一次绑定**新增**占用的组件槽位（首次用总线时含共享部分；mailbox 恒为 0）
     */
    public record Binding(String deviceId, String kind, Carrier carrier, int channel, int slotsUsed) {
    }

    /**
     * 平台能力（由芯片档位决定）。
     *
     * @param mailbox   有没有共享内存邮箱（MCU 没有）
     * @param slots     **可用组件槽位总数**（芯片档位的核心资源）
     * @param usbSlots  USB 端点数量（0 = 没接 USB 控制器）
     * @param pcieLanes PCIe 通道数（USB 控制器要从这里扣，见 PcieFabric）
     */
    public record Capacity(boolean mailbox, int slots, int usbSlots, int pcieLanes) {

        /** MCU 档：没有 mailbox，只有组件槽位 */
        public static Capacity mcu(int slots) {
            return new Capacity(false, slots, 0, 0);
        }

        /** SOC/CPU 档：有 mailbox（不占槽位）；USB 端点要先有控制器（控制器占 PCIe 通道） */
        public static Capacity soc(int slots, int usbSlots, int pcieLanes) {
            return new Capacity(true, slots, usbSlots, pcieLanes);
        }
    }

    private PeripheralMap() {
    }

    /**
     * 排布映射。
     *
     * @param capacity  平台能力
     * @param devices   设备列表（顺序 = 自动分配的优先序，稳定可复现）
     * @param overrides 手动覆盖（设备 id → 指定载体）；不在列表里的设备按档位默认
     * @throws IllegalArgumentException 覆盖里出现未知设备 id
     * @throws IllegalStateException    槽位/端点/能力不够（报错里带"还差几个"）
     */
    public static List<Binding> plan(Capacity capacity, List<Device> devices, Map<String, Carrier> overrides) {
        final Map<String, Carrier> wanted = overrides == null ? Map.of() : overrides;
        for (final String id : wanted.keySet()) {
            boolean known = false;
            for (final Device d : devices) {
                if (d.id().equals(id)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalArgumentException("override for unknown device: " + id);
            }
        }

        final List<Binding> out = new ArrayList<>();
        final Map<Carrier, Integer> channelUsed = new HashMap<>();
        final Set<Carrier> busOpened = new LinkedHashSet<>();
        int slotsUsed = 0;

        for (final Device d : devices) {
            final Carrier carrier = wanted.getOrDefault(d.id(), defaultCarrier(capacity));
            final int channel = channelUsed.getOrDefault(carrier, 0);

            switch (carrier) {
                case MAILBOX -> {
                    if (!capacity.mailbox()) {
                        // 明确报错而不是退回 UART：退回会让"映射结果"与调用方以为的不一致
                        throw new IllegalStateException("device " + d.id() + ": 这个档位没有 mailbox 载体");
                    }
                    // 共享内存邮箱不占组件槽位（这就是 SOC 档"接口多"的原因）
                    out.add(new Binding(d.id(), d.kind(), carrier, channel, 0));
                }
                case USB -> {
                    if (capacity.usbSlots() <= 0) {
                        throw new IllegalStateException("device " + d.id()
                                + ": 没有 USB 控制器（端点数为 0）—— 先接控制器（要占 PCIe 通道）");
                    }
                    if (channel >= capacity.usbSlots()) {
                        throw new IllegalStateException("device " + d.id() + ": USB 端点用尽（"
                                + capacity.usbSlots() + " 个）");
                    }
                    final int cost = chargeSlots(capacity, slotsUsed, busOpened, carrier, d.id());
                    slotsUsed += cost;
                    out.add(new Binding(d.id(), d.kind(), carrier, channel, cost));
                }
                case PCIE -> {
                    if (capacity.pcieLanes() <= 0) {
                        throw new IllegalStateException("device " + d.id()
                                + ": 这个档位没有 PCIe —— PCIe 只有 SOC/CPU 档才有");
                    }
                    // PCIe 按**链路宽度**占槽位：x1=2 / x4=8 / x8=16 / x16=32（用户 2026-09-18）
                    final int cost = pcieSlots(d.linkWidth());
                    if (slotsUsed + cost > capacity.slots()) {
                        throw new IllegalStateException("device " + d.id() + ": 组件槽位不够（PCIe "
                                + d.linkWidth() + " 这次要 " + cost + " 个，已用 " + slotsUsed + "/"
                                + capacity.slots() + "，还差 "
                                + (slotsUsed + cost - capacity.slots()) + " 个）—— "
                                + "换更窄的链路宽度（x16 → x8），或减设备、换档位");
                    }
                    slotsUsed += cost;
                    out.add(new Binding(d.id(), d.kind(), carrier, channel, cost));
                }
                default -> {
                    // UART / I2C / SPI / QSPI / DP / 8080 / FSMC：统一走组件槽位预算
                    final int cost = chargeSlots(capacity, slotsUsed, busOpened, carrier, d.id());
                    slotsUsed += cost;
                    out.add(new Binding(d.id(), d.kind(), carrier, channel, cost));
                }
            }
            channelUsed.put(carrier, channel + 1);
        }
        return out;
    }

    /**
     * 扣一次组件槽位预算；不够就报错并写明**差几个**。
     *
     * <p>为什么报错信息必须带数字：挂不上某个外设最可能的原因就是槽位不够，而"差 2 个"
     * 能直接告诉人"少挂一个 UART"还是"该换 SPI 总线"—— 只说"资源不足"等于没说。</p>
     *
     * @return 本次新增占用的槽位数（0 = 只是复用已开的总线）
     */
    private static int chargeSlots(Capacity capacity, int used, Set<Carrier> busOpened,
                                  Carrier carrier, String deviceId) {
        final boolean first = !busOpened.contains(carrier);
        final int cost = first ? carrier.firstUseSlots() : carrier.extraDeviceSlots();
        if (used + cost > capacity.slots()) {
            throw new IllegalStateException("device " + deviceId + ": 组件槽位不够（" + carrier.label()
                    + " 这次要 " + cost + " 个，已用 " + used + "/" + capacity.slots()
                    + "，还差 " + (used + cost - capacity.slots()) + " 个）—— "
                    + "减设备、改用更省槽位的总线（SPI/I2C 比 UART 省），或换槽位更多的档位");
        }
        busOpened.add(carrier);
        return cost;
    }

    /** 按容量选默认载体（界面上显示"自动"时会用到的推荐值） */
    public static Carrier defaultCarrier(Capacity capacity) {
        return capacity.mailbox() ? Carrier.MAILBOX : Carrier.UART;
    }

    /** 映射结果 → "设备 id → 绑定"（查询用；保序） */
    public static Map<String, Binding> index(List<Binding> bindings) {
        final Map<String, Binding> out = new LinkedHashMap<>();
        for (final Binding b : bindings) {
            out.put(b.deviceId(), b);
        }
        return out;
    }

    /** 这批绑定一共占了多少组件槽位（诊断/界面显示；含各总线首次的共享部分） */
    public static int totalSlots(List<Binding> bindings) {
        int sum = 0;
        for (final Binding b : bindings) {
            sum += b.slotsUsed();
        }
        return sum;
    }
}
