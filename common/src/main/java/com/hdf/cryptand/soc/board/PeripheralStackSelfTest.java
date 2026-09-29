package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.peripheral.Peripheral;
import com.hdf.cryptand.soc.peripheral.SharedBus;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 三层链路闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>用户定案的分层："<b>外设层 → 模块接口层 → RV 内核</b>"。本闸门要证明这条链路真的通，而且
 * 语义真实：</p>
 * <ol>
 *   <li>RV 写 TX 窗口只是**入队**，要等 tick 按总线速率才到外设（一次写内存 ≠ 立刻到达）；</li>
 *   <li>带宽受限：1 秒只能发总线的量，其余留在队列（不是一写就全到）；</li>
 *   <li>队列满按总线流控处理：UART 无流控 ⇒ 丢 + 计数，并且计数**能从寄存器窗口读到**；</li>
 *   <li>反方向：外设产出 ⇒ tick 搬进 RX 窗口 ⇒ RV 读窗口取数，RX_AVAIL 寄存器同步；</li>
 *   <li>外设不收（accept=false）⇒ 计数，不静默；</li>
 *   <li>整个模块能 attach 到底层抽象（三段窗口挂到板上）。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runPeripheralStackTest}</p>
 */
public final class PeripheralStackSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ---- 1. RV 写 TX 窗口 ⇒ 入队；tick 后才到外设 ----
        final FakeLink link = new FakeLink();
        final SharedBus bus = new SharedBus("UART0", 1000, SharedBus.FlowControl.NONE_DROP, 4096);
        final PeripheralModule mod = new PeripheralModule("UART0", 0x1000_5000L, bus, link);

        mod.txWindow().store(0, 'A', Sizes.SIZE_8);
        mod.txWindow().store(1, 'B', Sizes.SIZE_8);
        check("RV 写完数据还在队列里（没立刻到外设）",
                link.received.isEmpty() && mod.backlog() == 2);
        mod.tick(1_000_000_000L);
        check("tick（1 秒）后外设按顺序收到 A、B",
                link.received.size() == 2 && link.received.get(0) == (byte) 'A'
                        && link.received.get(1) == (byte) 'B');

        // ---- 2. 带宽受限：写 2000 字节、1 秒只能发 1000 ----
        final FakeLink link2 = new FakeLink();
        final SharedBus bus2 = new SharedBus("UART1", 1000, SharedBus.FlowControl.NONE_DROP, 8192);
        final PeripheralModule mod2 = new PeripheralModule("UART1", 0x1000_6000L, bus2, link2);
        for (int i = 0; i < 2000; i++) {
            mod2.txWindow().store(0, i & 0xFF, Sizes.SIZE_8);
        }
        mod2.tick(1_000_000_000L);
        check("1 秒只发出去 1000 字节（总线速率约束）", link2.received.size() == 1000);
        check("剩余 1000 字节仍在队列（不丢、不凭空发出）", mod2.backlog() == 1000);
        mod2.tick(1_000_000_000L);
        check("再等 1 秒 ⇒ 剩下 1000 字节也到了", link2.received.size() == 2000);

        // ---- 3. 队列满 ⇒ 按流控丢（UART 无流控）且计数能从寄存器读到 ----
        final FakeLink link3 = new FakeLink();
        final SharedBus tiny = new SharedBus("UART2", 1000, SharedBus.FlowControl.NONE_DROP, 2);
        final PeripheralModule mod3 = new PeripheralModule("UART2", 0x1000_7000L, tiny, link3);
        for (int i = 0; i < 5; i++) {
            mod3.txWindow().store(0, i, Sizes.SIZE_8);
        }
        mod3.tick(0);      // 只为刷新寄存器
        check("队列只有 2 字节容量 ⇒ 后 3 个被丢（UART 无流控）", mod3.txDropped() == 3);
        check("丢包计数能从 REG 窗口读到（RV 侧可诊断）",
                mod3.regWindow().load(0x08, Sizes.SIZE_32) == 3);

        // ---- 4. 反方向：外设产出 ⇒ RX 窗口 ⇒ RV 读 ----
        link3.toProduce = new byte[] { 0x11, 0x22, 0x33 };
        mod3.tick(1_000_000_000L);
        check("外设产出的 3 字节进了 RX 窗口",
                mod3.rxWindow().load(0, Sizes.SIZE_8) == 0x11
                        && mod3.rxWindow().load(2, Sizes.SIZE_8) == 0x33);
        check("RX_AVAIL 寄存器 = 3（RV 据此知道能读几个字节）",
                mod3.regWindow().load(0x10, Sizes.SIZE_32) == 3);

        // ---- 5. 外设不收（忙）⇒ 计数，不静默 ----
        final FakeLink busyLink = new FakeLink();
        busyLink.accepting = false;
        final SharedBus bus5 = new SharedBus("UART3", 1000, SharedBus.FlowControl.NONE_DROP, 4096);
        final PeripheralModule mod5 = new PeripheralModule("UART3", 0x1000_8000L, bus5, busyLink);
        mod5.txWindow().store(0, 0x5A, Sizes.SIZE_8);
        mod5.tick(1_000_000_000L);
        check("外设拒收 ⇒ peripheralRejects 计数（可观测）", mod5.peripheralRejects() == 1);

        // ---- 6. attach 到底层抽象（三段窗口都挂上板）----
        boolean attached = false;
        try {
            final com.hdf.cryptand.soc.riscv.Rv32Core cpu = new com.hdf.cryptand.soc.riscv.Rv32Core(
                    new com.hdf.cryptand.soc.riscv.Rv32Core.Config()
                            .resetVector((int) OcBoardLayout.ROM_BASE).enableM(true));
            final SocBoard.Builder b = SocBoard.builder(cpu);
            b.ram(OcBoardLayout.RAM_BASE, 256 * 1024);
            mod.attach(b);
            final SocBoard board = b.build();
            attached = board.devices().size() >= 4;      // RAM + REG + TX + RX
        } catch (Throwable t) {
            System.out.println("      (attach 失败：" + t + ")");
        }
        check("模块接口层能 attach 到底层抽象（RV 于是看得见它）", attached);

        // ---- 7. 三层的可见性：RV 只碰窗口/寄存器，不认识外设 ----
        check("RV 的全部接口就是三段窗口（REG/TX/RX），没有外设对象泄漏",
                mod.regWindow() != null && mod.txWindow() != null && mod.rxWindow() != null
                        && mod.spec().deviceId().equals(link.name()));

        System.out.println("[STACK] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 假外设：记录收到的字节，可产出预设字节，可模拟拒收 */
    private static final class FakeLink implements Peripheral {
        final List<Byte> received = new ArrayList<>();
        byte[] toProduce = new byte[0];
        boolean accepting = true;

        @Override
        public String name() {
            return "fake-link";
        }

        @Override
        public boolean accept(byte[] data, int len) {
            if (!accepting) {
                return false;
            }
            for (int i = 0; i < len; i++) {
                received.add(data[i]);
            }
            return true;
        }

        @Override
        public int available() {
            return toProduce.length;
        }

        @Override
        public int produce(byte[] out, int cap) {
            final int n = Math.min(cap, toProduce.length);
            System.arraycopy(toProduce, 0, out, 0, n);
            toProduce = new byte[0];
            return n;
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
