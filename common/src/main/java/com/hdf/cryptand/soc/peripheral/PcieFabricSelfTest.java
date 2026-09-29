package com.hdf.cryptand.soc.peripheral;

import java.util.List;
import java.util.Map;

/**
 * ===== PCIe 通道预算沙盒自测（纯 Java 零 MC）=====
 *
 * <p>要点：通道按顺序分配、装不下必须**报错**（不能静默少给通道）、
 * 以及"控制器占通道 → 端点挂设备"这个两层拓扑真的串得起来（与 {@link PeripheralMap} 联合验证）。</p>
 */
public final class PcieFabricSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        final PcieFabric.Budget pc = PcieFabric.consumer();          // 20 条

        // ---- 1. 正常装机：显卡 x16 + USB 控制器 x1 + 网卡 x1 ----
        final List<PcieFabric.Placement> placed = pc.place(List.of(
                new PcieFabric.Card("gpu-card", "gpu", PcieFabric.LinkWidth.X16),
                new PcieFabric.Card("usb-ctrl", "usb", PcieFabric.LinkWidth.X1),
                new PcieFabric.Card("nic", "net", PcieFabric.LinkWidth.X1)));
        check("按顺序分配起始通道 0/16/17",
                placed.get(0).startLane() == 0 && placed.get(1).startLane() == 16 && placed.get(2).startLane() == 17);
        check("已用 18 条、剩 2 条", pc.used(placed) == 18 && pc.free(placed) == 2);

        // ---- 2. 装不下 ⇒ 报错（且消息说清还差多少）----
        boolean over = false;
        try {
            pc.place(List.of(
                    new PcieFabric.Card("gpu1", "gpu", PcieFabric.LinkWidth.X16),
                    new PcieFabric.Card("gpu2", "gpu", PcieFabric.LinkWidth.X16)));
        } catch (IllegalStateException e) {
            over = e.getMessage().contains("通道不足") && e.getMessage().contains("只剩 4 条");
        }
        check("20 条平台插两张 x16 ⇒ 报错并说明剩几条", over);

        // ---- 3. 单卡超过平台总通道 ⇒ 报错 ----
        boolean tooWide = false;
        try {
            new PcieFabric.Budget(8).place(List.of(
                    new PcieFabric.Card("gpu", "gpu", PcieFabric.LinkWidth.X16)));
        } catch (IllegalStateException e) {
            tooWide = e.getMessage().contains("通道不足");
        }
        check("8 条平台要 x16 ⇒ 报错", tooWide);

        // ---- 4. 空装机 ⇒ 空结果，剩余 = 全部 ----
        final List<PcieFabric.Placement> none = pc.place(List.of());
        check("空装机 ⇒ 0 已用 / 20 剩余", none.isEmpty() && pc.used(none) == 0 && pc.free(none) == 20);

        // ---- 5. 工作站档位更宽 ----
        check("工作站 24 条能插 x16 + x8", PcieFabric.workstation()
                .used(PcieFabric.workstation().place(List.of(
                        new PcieFabric.Card("gpu", "gpu", PcieFabric.LinkWidth.X16),
                        new PcieFabric.Card("hba", "storage", PcieFabric.LinkWidth.X8)))) == 24);

        // ---- 6. USB 速度等级（用户要求 1.0 ~ 3.0）----
        check("USB 1.0 = 12 Mbps", PcieFabric.UsbSpeed.USB1_0.bitsPerSecond() == 12_000_000L);
        check("USB 2.0 = 480 Mbps", PcieFabric.UsbSpeed.USB2_0.bitsPerSecond() == 480_000_000L);
        check("USB 3.0 = 5 Gbps", PcieFabric.UsbSpeed.USB3_0.bitsPerSecond() == 5_000_000_000L);
        check("速度档位随版本递增", PcieFabric.UsbSpeed.USB1_0.bitsPerSecond()
                < PcieFabric.UsbSpeed.USB2_0.bitsPerSecond()
                && PcieFabric.UsbSpeed.USB2_0.bitsPerSecond() < PcieFabric.UsbSpeed.USB3_0.bitsPerSecond());

        // ---- 7. 两层拓扑：控制器占 PCIe 通道 → 它的端点才挂得上 USB 设备 ----
        //      （这正是用户说的"把 PCIE 拆分为 USB 等设备"）
        final List<PcieFabric.Placement> withCtrl = pc.place(List.of(
                new PcieFabric.Card("usb-ctrl", "usb", PcieFabric.LinkWidth.X1)));
        final int endpoints = 4;                                     // 一块 x1 控制器给 4 个端点
        final var bindings = PeripheralMap.plan(
                // ⚠ 第一个参数是 **IO 线数**（2026-09-18 起）：USB 总线首次要占 2 根共享线，
                //   所以这里给 16 根；旧代码写的 1 是"UART 端口数"的老语义（已随 IO 线模型退役）。
                PeripheralMap.Capacity.soc(16, endpoints, pc.totalLanes()),
                List.of(new PeripheralMap.Device("disk-0", "disk"),
                        new PeripheralMap.Device("cam-0", "camera")),
                Map.of("disk-0", PeripheralMap.Carrier.USB, "cam-0", PeripheralMap.Carrier.USB));
        final var idx = PeripheralMap.index(bindings);
        check("控制器占了 1 条 PCIe 通道", pc.used(withCtrl) == 1);
        check("该控制器的端点上挂得住 USB 设备",
                idx.get("disk-0").carrier() == PeripheralMap.Carrier.USB
                        && idx.get("cam-0").channel() == 1);
        check("下游 USB 设备不再吃 PCIe 通道（只吃端点）", pc.free(withCtrl) == 19);

        System.out.println("[PCIE] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
