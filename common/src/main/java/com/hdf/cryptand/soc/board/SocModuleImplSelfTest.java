package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.device.AutoForwardWindowDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 具体模块闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>要证明用户点名的两件事真的在<b>模块内部</b>完成，而 RV 侧只做"写窗口"：</p>
 * <ol>
 *   <li><b>I2C 按地址分发</b>：一条总线多从设备，先写地址字节再写数据，只有地址匹配的收到；
 *       地址冲突/未选地址/地址上没设备（NACK）都必须明确报错，不许静默丢；</li>
 *   <li><b>PCIe 转接</b>：上游一次写被转成某个下游目标的一次操作，越界目标要报错；</li>
 *   <li>两个模块都能 <b>attach 到底层抽象</b>（注册成板上的 MMIO 窗口，建板成功）。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runModuleImplTest}</p>
 */
public final class SocModuleImplSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ==================== 1. I2C：按地址分发 ====================
        final I2cDispatchModule i2c = new I2cDispatchModule("I2C0", 0x1000_7000L, 0x1000, SocModules.I2C_BPS);
        final Slave lcd = new Slave(0x3C, "lcd");
        final Slave eeprom = new Slave(0x50, "eeprom");
        i2c.slave(lcd).slave(eeprom);
        final AutoForwardWindowDevice win = i2c.window();

        win.store(0, 0x3C, Sizes.SIZE_8);      // 地址字节（RV 的全部动作就是这一条 store）
        win.store(1, 0xAB, Sizes.SIZE_8);      // 数据字节
        check("只有地址匹配的从设备收到数据",
                lcd.values.size() == 1 && lcd.values.get(0) == 0xAB && eeprom.values.isEmpty());

        win.store(0, 0x50, Sizes.SIZE_8);      // 切到另一个从设备
        win.store(1, 0xCD, Sizes.SIZE_8);
        check("切地址后分发给另一个从设备",
                eeprom.values.size() == 1 && eeprom.values.get(0) == 0xCD && lcd.values.size() == 1);
        check("成功分发计数 = 2", i2c.delivered() == 2);
        check("当前选中地址 = 0x50", i2c.selectedAddress() == 0x50);

        // ---- 地址冲突：装配期就该拦（否则"谁收到"变成随机）----
        boolean conflict = false;
        try {
            i2c.slave(new Slave(0x3C, "another-lcd"));
        } catch (IllegalArgumentException e) {
            conflict = e.getMessage().contains("地址冲突");
        }
        check("I2C 地址冲突 ⇒ 装配报错", conflict);

        // ---- 未选地址就写数据 ----
        final I2cDispatchModule fresh = new I2cDispatchModule("I2C1", 0x1000_D000L, 0x1000, SocModules.I2C_BPS);
        boolean noAddr = false;
        try {
            fresh.window().store(1, 0x11, Sizes.SIZE_8);
        } catch (IllegalStateException e) {
            noAddr = e.getMessage().contains("还没选从设备");
        }
        check("未选从设备就写数据 ⇒ 明确报错", noAddr && fresh.dropped() == 1);

        // ---- 地址上没有设备 = NACK（真实总线语义，不许当成功）----
        boolean nack = false;
        try {
            win.store(0, 0x77, Sizes.SIZE_8);
            win.store(1, 0x22, Sizes.SIZE_8);
        } catch (IllegalStateException e) {
            nack = e.getMessage().contains("NACK");
        }
        check("地址上没有从设备 ⇒ NACK 报错", nack);
        check("丢弃计数可观测（不静默）", i2c.dropped() == 1);

        // ==================== 2. PCIe：上游 → 下游转接 ====================
        final PcieBridgeModule pcie = new PcieBridgeModule("PCIE0", 0x1000_A000L, 0x1000, SocModules.PCIE_BPS);
        final Target gpu = new Target("gpu");
        final Target net = new Target("net");
        pcie.downstream(gpu).downstream(net);
        final AutoForwardWindowDevice up = pcie.window();

        up.store(0, 1, Sizes.SIZE_8);          // 目标寄存器 = 1（等价把 BAR 指向网卡）
        up.store(1, 0x1234, Sizes.SIZE_16);    // 一次上游写
        check("上游写被转接到目标 1（网卡）", net.last == 0x1234 && gpu.last == -1);
        check("转发计数 = 1", pcie.forwarded() == 1);

        up.store(0, 0, Sizes.SIZE_8);          // 切回显卡
        up.store(1, 0x5678, Sizes.SIZE_16);
        check("切目标后转给显卡", gpu.last == 0x5678);

        boolean oob = false;
        try {
            up.store(0, 9, Sizes.SIZE_8);
            up.store(1, 1, Sizes.SIZE_16);
        } catch (IllegalStateException e) {
            oob = e.getMessage().contains("越界");
        }
        check("PCIe 目标越界 ⇒ 明确报错", oob);

        // ---- 2b. 模块接到共享总线：带宽与流控交给总线（写只入队，tick 才生效）----
        final com.hdf.cryptand.soc.peripheral.SharedBus i2cBus =
                new com.hdf.cryptand.soc.peripheral.SharedBus("I2C-BUS", 1000,
                        com.hdf.cryptand.soc.peripheral.SharedBus.FlowControl.CLOCK_STRETCH, 4096);
        final I2cDispatchModule i2cOnBus = new I2cDispatchModule("I2C2", 0x1000_E000L, 0x1000, 1000);
        final Slave onBusLcd = new Slave(0x3C, "lcd-on-bus");
        i2cOnBus.slave(onBusLcd).withBus(i2cBus);
        i2cOnBus.window().store(0, 0x3C, Sizes.SIZE_8);
        i2cOnBus.window().store(1, 0x77, Sizes.SIZE_8);
        check("I2C 绑总线后 store 只入队（从设备还没收到）", onBusLcd.values.isEmpty());
        i2cBus.tick(1_000_000_000L);
        check("bus.tick 后 I2C 地址分发才生效",
                onBusLcd.values.size() == 1 && onBusLcd.values.get(0) == 0x77);

        final com.hdf.cryptand.soc.peripheral.SharedBus pcieBus =
                new com.hdf.cryptand.soc.peripheral.SharedBus("PCIE-BUS", 1000,
                        com.hdf.cryptand.soc.peripheral.SharedBus.FlowControl.CREDIT_BASED, 4096);
        final PcieBridgeModule pcieOnBus = new PcieBridgeModule("PCIE2", 0x1000_F000L, 0x1000, 1000);
        final Target onBusNet = new Target("net-on-bus");
        pcieOnBus.downstream(onBusNet).withBus(pcieBus);
        pcieOnBus.window().store(0, 0, Sizes.SIZE_8);
        pcieOnBus.window().store(1, 0x99, Sizes.SIZE_16);
        check("PCIe 绑总线后 store 只入队（下游还没收到）", onBusNet.last == -1);
        pcieBus.tick(1_000_000_000L);
        check("bus.tick 后 PCIe 转接才生效", onBusNet.last == 0x99);

        // ==================== 3. 集成到底层抽象（真板装配）====================
        boolean attached = false;
        try {
            // 与离线装置/真机同构：Builder 需要一个 CPU 实例（这里只用它建板，不执行任何指令）
            final com.hdf.cryptand.soc.riscv.Rv32Core cpu = new com.hdf.cryptand.soc.riscv.Rv32Core(
                    new com.hdf.cryptand.soc.riscv.Rv32Core.Config()
                            .resetVector((int) OcBoardLayout.ROM_BASE).enableM(true));
            final SocBoard.Builder b = SocBoard.builder(cpu);
            b.ram(OcBoardLayout.RAM_BASE, 256 * 1024);
            i2c.attach(b);
            pcie.attach(b);
            final SocBoard board = b.build();
            attached = board.devices().size() >= 3;      // RAM + 两个模块窗口
        } catch (Throwable t) {
            System.out.println("      (attach 失败：" + t + ")");
        }
        check("两个具体模块都能挂到底层抽象（建板成功）", attached);

        // ---- 4. 结构性：RV 只写窗口 —— 模块不暴露 GPIO/引脚 API ----
        boolean noGpio = true;
        for (final java.lang.reflect.Method m : SocModuleImpl.class.getMethods()) {
            final String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("pin") || n.contains("gpio") || n.contains("toggle")) {
                noGpio = false;
            }
        }
        check("模块接口只暴露 spec/attach/tick/窗口 —— 没有 GPIO/引脚 API", noGpio);

        System.out.println("[MOD-IMPL] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 记录收到数据的 I2C 从设备 */
    private static final class Slave implements I2cDispatchModule.I2cSlave {
        private final int addr;
        private final String name;
        final List<Integer> values = new ArrayList<>();

        Slave(int addr, String name) {
            this.addr = addr;
            this.name = name;
        }

        @Override
        public int address7() {
            return addr;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void write(int value) {
            values.add(value);
        }
    }

    /** 记录收到数据的 PCIe 下游目标 */
    private static final class Target implements PcieBridgeModule.Downstream {
        private final String name;
        int last = -1;

        Target(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void write(int value) {
            last = value;
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
