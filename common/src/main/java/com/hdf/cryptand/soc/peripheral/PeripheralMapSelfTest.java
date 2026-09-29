package com.hdf.cryptand.soc.peripheral;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 外设映射层沙盒自测（纯 Java 零 MC）=====
 *
 * <p>验证 2026-09-18 定案的资源模型：<b>资源单位是组件槽位</b>（用户订正："MCU 也不用根据 IO 口来
 * 设置了，比如 UART 占用组件槽位为 2 个，SPI 为 4 个这种"），总线共享槽位只扣一次，
 * 独占槽位按设备扣，不够时报错并写明**差几个**；SOC 档默认 mailbox（不占槽位）；
 * <b>PCIe 本体固定吃 16 个槽位，下游设备不再重复吃</b>。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runPeripheralMapTest}</p>
 */
public final class PeripheralMapSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        final List<PeripheralMap.Device> devices = List.of(
                new PeripheralMap.Device("disk-0", "disk"),
                new PeripheralMap.Device("uart-card", "uart"),
                new PeripheralMap.Device("gpio-card", "gpio"));

        // ---- 1. 载体自身的槽位代价（用户点名的口径）----
        check("UART 占 2 个组件槽位", PeripheralMap.Carrier.UART.firstUseSlots() == 2);
        check("SPI 占 4 个组件槽位（3 共享 + 1 片选）", PeripheralMap.Carrier.SPI.firstUseSlots() == 4);
        check("I2C 占 2 个组件槽位", PeripheralMap.Carrier.I2C.firstUseSlots() == 2);
        check("8080 并口占 13 个槽位", PeripheralMap.Carrier.PARALLEL8080.firstUseSlots() == 13);
        check("PCIe x16 占 34 个槽位（基础 2 + 通道 32）",
                PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X16) == 34);
        check("PCIe 宽度不同、槽位不同：x16(34) > x8(18) > x4(10) > x1(4)",
                PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X16) > PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X8)
                        && PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X8) > PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X4)
                        && PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X4) > PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X1));
        check("两张 x8 比一张 x16 多消耗槽位（拆分有额外开销，用户定案）",
                2 * PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X8)
                        > PeripheralMap.pcieSlots(PcieFabric.LinkWidth.X16));

        // ---- 2. MCU：默认 UART，每设备 2 个槽位 ⇒ 3 个设备 = 6 个 ----
        final var mcu = PeripheralMap.plan(PeripheralMap.Capacity.mcu(32), devices, Map.of());
        check("MCU 全部落在 UART", mcu.stream().allMatch(b -> b.carrier() == PeripheralMap.Carrier.UART));
        check("MCU 端口按顺序 0/1/2",
                mcu.get(0).channel() == 0 && mcu.get(1).channel() == 1 && mcu.get(2).channel() == 2);
        check("3 个 UART 设备 = 6 个组件槽位", PeripheralMap.totalSlots(mcu) == 6);
        check("默认载体 = UART",
                PeripheralMap.defaultCarrier(PeripheralMap.Capacity.mcu(32)) == PeripheralMap.Carrier.UART);

        // ---- 3. 槽位不够 ⇒ 报错，且写明**差几个** ----
        final var twoOnly = PeripheralMap.plan(PeripheralMap.Capacity.mcu(4),
                devices.subList(0, 2), Map.of());
        check("4 个槽位刚好挂 2 个 UART 设备", PeripheralMap.totalSlots(twoOnly) == 4);
        boolean shortSlots = false;
        String shortMsg = "";
        try {
            PeripheralMap.plan(PeripheralMap.Capacity.mcu(4), devices, Map.of());
        } catch (IllegalStateException e) {
            shortMsg = e.getMessage();
            shortSlots = shortMsg.contains("组件槽位不够");
        }
        check("槽位不足 ⇒ 报错", shortSlots);
        check("报错写明还差 2 个", shortMsg.contains("还差 2 个"));

        // ---- 4. 共享总线：SPI 挂 4 设备 = 4 + 1 + 1 + 1 = 7 个槽位 ----
        final List<PeripheralMap.Device> four = List.of(
                new PeripheralMap.Device("s0", "sensor"), new PeripheralMap.Device("s1", "sensor"),
                new PeripheralMap.Device("s2", "sensor"), new PeripheralMap.Device("s3", "sensor"));
        final Map<String, PeripheralMap.Carrier> allSpi = new HashMap<>();
        for (final PeripheralMap.Device d : four) {
            allSpi.put(d.id(), PeripheralMap.Carrier.SPI);
        }
        final var spi = PeripheralMap.plan(PeripheralMap.Capacity.mcu(32), four, allSpi);
        check("SPI 挂 4 个设备 = 7 个槽位", PeripheralMap.totalSlots(spi) == 7);
        check("SPI 片选按顺序 0/1/2/3", spi.get(3).channel() == 3);

        // ---- 5. I2C：共享 2 个槽位，挂 3 个设备仍是 2 个 ----
        final Map<String, PeripheralMap.Carrier> allI2c = new HashMap<>();
        for (final PeripheralMap.Device d : four.subList(0, 3)) {
            allI2c.put(d.id(), PeripheralMap.Carrier.I2C);
        }
        final var i2c = PeripheralMap.plan(PeripheralMap.Capacity.mcu(32), four.subList(0, 3), allI2c);
        check("I2C 挂 3 个设备只占 2 个槽位（总线共享）", PeripheralMap.totalSlots(i2c) == 2);

        // ---- 6. SOC：默认 mailbox，**不占槽位** ----
        final var socCap = PeripheralMap.Capacity.soc(0, 0, 20);
        final var soc = PeripheralMap.plan(socCap, devices, Map.of());
        check("SOC 默认全部 mailbox", soc.stream().allMatch(b -> b.carrier() == PeripheralMap.Carrier.MAILBOX));
        check("mailbox 不占组件槽位", PeripheralMap.totalSlots(soc) == 0);
        check("默认载体 = MAILBOX", PeripheralMap.defaultCarrier(socCap) == PeripheralMap.Carrier.MAILBOX);

        // ---- 7. 手动覆盖：把硬盘改到 UART ----
        final var mixed = PeripheralMap.plan(PeripheralMap.Capacity.soc(16, 0, 20), devices,
                Map.of("disk-0", PeripheralMap.Carrier.UART));
        final var byId = PeripheralMap.index(mixed);
        check("硬盘被手动改到 UART 0",
                byId.get("disk-0").carrier() == PeripheralMap.Carrier.UART && byId.get("disk-0").channel() == 0);
        check("其余设备仍是 mailbox",
                byId.get("uart-card").carrier() == PeripheralMap.Carrier.MAILBOX);

        // ---- 8. 覆盖未知设备 / 越档载体 ----
        boolean unknown = false;
        try {
            PeripheralMap.plan(socCap, devices, Map.of("no-such-device", PeripheralMap.Carrier.UART));
        } catch (IllegalArgumentException e) {
            unknown = e.getMessage().contains("unknown device");
        }
        check("覆盖未知设备 ⇒ 报错", unknown);

        boolean noMailbox = false;
        try {
            PeripheralMap.plan(PeripheralMap.Capacity.mcu(32), devices,
                    Map.of("disk-0", PeripheralMap.Carrier.MAILBOX));
        } catch (IllegalStateException e) {
            noMailbox = e.getMessage().contains("没有 mailbox");
        }
        check("MCU 指定 mailbox ⇒ 报错", noMailbox);

        // ---- 9. USB：没有控制器 ⇒ 报错；有控制器 ⇒ 2 个共享槽位 ----
        boolean noUsb = false;
        try {
            PeripheralMap.plan(socCap, devices, Map.of("disk-0", PeripheralMap.Carrier.USB));
        } catch (IllegalStateException e) {
            noUsb = e.getMessage().contains("没有 USB 控制器");
        }
        check("没有 USB 控制器 ⇒ 报错", noUsb);

        final var withUsb = PeripheralMap.plan(PeripheralMap.Capacity.soc(16, 2, 20), devices,
                Map.of("disk-0", PeripheralMap.Carrier.USB, "gpio-card", PeripheralMap.Carrier.USB));
        check("两个 USB 设备只占 2 个共享槽位", PeripheralMap.totalSlots(withUsb) == 2);

        // ---- 10. PCIe：MCU 没有 ⇒ 报错；SOC 有 ⇒ 本体 16 槽位、下游设备不再加 ----
        boolean noPcie = false;
        try {
            PeripheralMap.plan(PeripheralMap.Capacity.mcu(32), devices,
                    Map.of("disk-0", PeripheralMap.Carrier.PCIE));
        } catch (IllegalStateException e) {
            noPcie = e.getMessage().contains("没有 PCIe");
        }
        check("MCU 指定 PCIe ⇒ 报错", noPcie);

        final List<PeripheralMap.Device> pcieDevices = List.of(
                new PeripheralMap.Device("root", "pcie-root", PcieFabric.LinkWidth.X1),
                new PeripheralMap.Device("gpu", "gpu", PcieFabric.LinkWidth.X16),
                new PeripheralMap.Device("net", "net", PcieFabric.LinkWidth.X8));
        final Map<String, PeripheralMap.Carrier> allPcie = new HashMap<>();
        for (final PeripheralMap.Device d : pcieDevices) {
            allPcie.put(d.id(), PeripheralMap.Carrier.PCIE);
        }
        final var pcie = PeripheralMap.plan(PeripheralMap.Capacity.soc(64, 0, 20), pcieDevices, allPcie);
        check("PCIe 槽位按各自宽度累加：x1(4) + x16(34) + x8(18) = 56",
                PeripheralMap.totalSlots(pcie) == 56);

        System.out.println("[PERI] " + passed + "/" + (passed + failed) + " checks passed");
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
