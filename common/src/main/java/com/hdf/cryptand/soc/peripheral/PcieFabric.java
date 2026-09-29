package com.hdf.cryptand.soc.peripheral;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== PCIe 通道预算 + USB 速度等级（common，纯 Java 零 MC）=====
 *
 * <p>用户定案："USB 需要从 1.0 到 USB3.0，包括 PCIE"、
 * "SOC 以上开始使用 PCIE……把 PCIE 拆分为 USB 等设备（需要消耗 PCIE 通道）然后其他设备走 USB 即可"。</p>
 *
 * <h3>两条不变式</h3>
 * <ul>
 *   <li>PCIe 通道是<b>有限资源</b>（消费级平台 20 / 24 条），每张卡按 x1/x2/x4/x8/x16 申请。
 *       装不下就是装不下 —— 必须报错，否则会出现"插了卡但设备静默不工作"这种最难查的现象。</li>
 *   <li><b>USB 控制器本身就是一张 PCIe 卡</b>：先占通道，才谈得上端点
 *       （端点数量喂给 {@link PeripheralMap.Capacity#usbSlots()}）。
 *       下游的 USB 设备<b>不再吃 PCIe 通道</b>（它们共享控制器带宽），所以拓扑是两层：
 *       {@code PCIe 通道 → 控制器 → USB 端点 → 设备}。</li>
 * </ul>
 *
 * <p>⚠ 放 common：这是纯资源记账，与 Minecraft 无关，而它决定了"插了卡能不能用"——
 * 沙盒里必须能把各种宽度组合试一遍。</p>
 */
public final class PcieFabric {

    /** 链路宽度（真实 PCIe 的常见档位） */
    public enum LinkWidth {
        X1(1), X2(2), X4(4), X8(8), X16(16);

        private final int lanes;

        LinkWidth(int lanes) {
            this.lanes = lanes;
        }

        public int lanes() {
            return lanes;
        }
    }

    /**
     * USB 速度等级与带宽（bit/s）。
     *
     * <p>1.0 与 1.1 都是 12 Mbps（Full Speed），差别在协议细节；带宽值用于将来的限流
     * （U 盘读写速度、摄像头帧率），现在先把等级与数值定下来，避免各处自己写魔数。</p>
     */
    public enum UsbSpeed {
        USB1_0(12_000_000L, "USB 1.0 · 12 Mbps"),
        USB1_1(12_000_000L, "USB 1.1 · 12 Mbps"),
        USB2_0(480_000_000L, "USB 2.0 · 480 Mbps"),
        USB3_0(5_000_000_000L, "USB 3.0 · 5 Gbps");

        private final long bitsPerSecond;
        private final String label;

        UsbSpeed(long bitsPerSecond, String label) {
            this.bitsPerSecond = bitsPerSecond;
            this.label = label;
        }

        public long bitsPerSecond() {
            return bitsPerSecond;
        }

        public String label() {
            return label;
        }
    }

    /** 一张待插的扩展卡（deviceId 是稳定标识，kind 用于日志/界面显示） */
    public record Card(String deviceId, String kind, LinkWidth width) {
    }

    /** 分配结果：从第几条通道开始，占几条（顺序分配，留出的空洞为 0 —— 简化且够用） */
    public record Placement(String deviceId, String kind, int startLane, int lanes) {
    }

    /** 平台的通道预算 */
    public record Budget(int totalLanes) {

        /**
         * 顺序分配通道。
         *
         * @throws IllegalStateException 通道不足（消息里带上"还差几条"，让玩家知道该拔哪张卡）
         */
        public List<Placement> place(List<Card> cards) {
            int next = 0;
            final List<Placement> out = new ArrayList<>();
            for (final Card c : cards) {
                final int need = c.width().lanes();
                if (next + need > totalLanes) {
                    throw new IllegalStateException("PCIe 通道不足：装 " + c.deviceId() + "（" + c.width()
                            + "，需 " + need + " 条）时只剩 " + (totalLanes - next) + " 条（平台共 "
                            + totalLanes + " 条）");
                }
                out.add(new Placement(c.deviceId(), c.kind(), next, need));
                next += need;
            }
            return out;
        }

        /** 已用通道数 */
        public int used(List<Placement> placements) {
            int sum = 0;
            for (final Placement p : placements) {
                sum += p.lanes();
            }
            return sum;
        }

        /** 剩余通道数 */
        public int free(List<Placement> placements) {
            return totalLanes - used(placements);
        }
    }

    private PcieFabric() {
    }

    /** 常见平台档位：消费级 20 条（CPU 16 + 芯片组 4）、工作站 24 条 */
    public static Budget consumer() {
        return new Budget(20);
    }

    public static Budget workstation() {
        return new Budget(24);
    }
}
