package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.peripheral.SharedBus;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 自动转发窗口 × 共享总线 闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>要证明"模块的信用结算统一切到总线"之后的行为是对的：</p>
 * <ol>
 *   <li>绑总线后 {@code store()} 只**入队**（写不等于到达）；</li>
 *   <li>{@code bus.tick(dt)} 后到达，且**命令/数据分流正确**（8080 的 RS 语义不能丢）；</li>
 *   <li>带宽受限：1 秒只发总线的量，其余留队列；</li>
 *   <li>流控按总线模型：UART 丢并计 dropppedByBus；I2C 背压计 busyByBus 且不丢；</li>
 *   <li>未绑总线时保持原行为（写即转发）——老路径不回归。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runWindowBusTest}</p>
 */
public final class AutoForwardWindowBusSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ---- 1. 绑总线：写窗口只入队 ----
        final Recorder rec = new Recorder();
        final SharedBus bus = new SharedBus("LCD-BUS", 1000, SharedBus.FlowControl.NONE_DROP, 4096);
        final AutoForwardWindowDevice win = new AutoForwardWindowDevice("LCD8080", 1, 1000);
        win.sink(rec);
        win.withBus(bus, "lcd");
        win.store(0, 0x2C, Sizes.SIZE_8);          // 命令（RS=0）
        win.store(1, 0x41, Sizes.SIZE_8);          // 数据（RS=1）
        check("绑总线后写窗口只入队（还没到外设）", rec.commands.isEmpty() && rec.datas.isEmpty());
        check("总线里命令/数据各一个包",
                bus.backlog("lcd-cmd") == 1 && bus.backlog("lcd-data") == 1);

        // ---- 2. tick 后到达，且分流正确 ----
        bus.tick(1_000_000_000L);
        check("tick 后命令到达且值正确", rec.commands.size() == 1 && rec.commands.get(0) == 0x2C);
        check("tick 后数据到达且值正确", rec.datas.size() == 1 && rec.datas.get(0) == 0x41);
        check("窗口统计同步（commands/dataWords）", win.commands() == 1 && win.dataWords() == 1);

        // ---- 3. 带宽受限：1 秒只发 1000 字节 ----
        final Recorder rec2 = new Recorder();
        final SharedBus bus2 = new SharedBus("BUS2", 1000, SharedBus.FlowControl.CREDIT_BASED, 8192);
        final AutoForwardWindowDevice win2 = new AutoForwardWindowDevice("W2", 1, 1000);
        win2.sink(rec2);
        win2.withBus(bus2, "w2");
        for (int i = 0; i < 2000; i++) {
            win2.store(1, i & 0xFF, Sizes.SIZE_8);
        }
        bus2.tick(1_000_000_000L);
        check("1 秒只到 1000 字节（总线速率约束）", rec2.datas.size() == 1000);
        bus2.tick(1_000_000_000L);
        check("再 1 秒补齐剩下的 1000 字节", rec2.datas.size() == 2000);

        // ---- 4. 流控差异（UART 丢 / I2C 背压）----
        final SharedBus uart = new SharedBus("UART", 1000, SharedBus.FlowControl.NONE_DROP, 2);
        final AutoForwardWindowDevice wu = new AutoForwardWindowDevice("WU", 1, 1000);
        wu.sink(new Recorder());
        wu.withBus(uart, "wu");
        for (int i = 0; i < 5; i++) {
            wu.store(1, i, Sizes.SIZE_8);
        }
        check("UART（无流控）队列满 ⇒ droppedByBus=3、无背压",
                wu.droppedByBus() == 3 && wu.busyByBus() == 0);

        final SharedBus i2c = new SharedBus("I2C", 1000, SharedBus.FlowControl.CLOCK_STRETCH, 2);
        final AutoForwardWindowDevice wi = new AutoForwardWindowDevice("WI", 1, 1000);
        wi.sink(new Recorder());
        wi.withBus(i2c, "wi");
        for (int i = 0; i < 5; i++) {
            wi.store(1, i, Sizes.SIZE_8);
        }
        check("I2C（时钟拉伸）队列满 ⇒ busyByBus=3 且不丢",
                wi.busyByBus() == 3 && wi.droppedByBus() == 0);

        // ---- 5. 未绑总线：保持原行为（写即转发）----
        final Recorder rec3 = new Recorder();
        final AutoForwardWindowDevice plain = new AutoForwardWindowDevice("PLAIN", 1, 1000);
        plain.sink(rec3);
        plain.store(0, 0x11, Sizes.SIZE_8);
        // ⚠ 未绑总线的窗口**自己算速率**（1KB/s、1ms 突发 ⇒ 一次只够 1 个字节），
        //   所以要 tick 一下补额度 —— 这正是"绑总线"能统一管带宽的意义（见 withBus 注释）
        plain.tick(1_000_000L);
        plain.store(1, 0x22, Sizes.SIZE_8);
        check("未绑总线的窗口保持写即转发（老路径不回归）",
                rec3.commands.size() == 1 && rec3.commands.get(0) == 0x11
                        && rec3.datas.size() == 1 && rec3.datas.get(0) == 0x22);

        System.out.println("[WINDOW-BUS] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static final class Recorder implements AutoForwardWindowDevice.Sink {
        final List<Integer> commands = new ArrayList<>();
        final List<Integer> datas = new ArrayList<>();

        @Override
        public void command(int value) {
            commands.add(value);
        }

        @Override
        public void data(int value) {
            datas.add(value);
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
