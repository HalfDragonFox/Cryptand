package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== 自动转发窗口闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>要证明的三件事：</p>
 * <ol>
 *   <li><b>写内存 = 一次总线传输</b>：store 直接落到 Sink 的 command/data，固件不碰引脚；</li>
 *   <li><b>8080 的 RS 语义</b>：窗口地址 bit0 决定命令/数据（真实里 A0 接 RS）；</li>
 *   <li><b>带宽真的限得住</b>：慢接口连续写会被"总线忙"拒绝，tick 按真实时间恢复信用 ——
 *       否则"写内存"就成了比 PCIe 还快的魔法。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runWindowTest}</p>
 */
public final class AutoForwardWindowSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ---- 1. 8 位 8080 窗口：写内存即转发 ----
        final AutoForwardWindowDevice lcd = new AutoForwardWindowDevice("LCD8080", 1, 1_000_000L);
        final Recorder rec = new Recorder();
        lcd.sink(rec);

        lcd.store(0, 0x2C, Sizes.SIZE_8);          // offset bit0 = 0 ⇒ 命令
        check("写 offset 0 = 命令，且值透传", rec.commands.size() == 1 && rec.commands.get(0) == 0x2C);
        lcd.store(1, 0x12, Sizes.SIZE_8);          // offset bit0 = 1 ⇒ 数据
        check("写 offset 1 = 数据", rec.datas.size() == 1 && rec.datas.get(0) == 0x12);
        check("命令/数据计数分开统计", lcd.commands() == 1 && lcd.dataWords() == 1);

        // ---- 2. 带宽：1 MB/s ⇒ 每字节 1000ns，突发 1ms ⇒ 先写 2 字节后还能写 998 字节 ----
        int wrote = 0;
        boolean busy = false;
        String busyMsg = "";
        try {
            for (int i = 0; i < 5000; i++) {
                lcd.store(1, i & 0xFF, Sizes.SIZE_8);
                wrote++;
            }
        } catch (MemoryAccessException e) {
            busy = true;
            busyMsg = e.getMessage();
        }
        check("慢接口连续写会被总线拒绝（带宽限得住）", busy);
        check("1ms 突发内恰好还能写 998 字节（= 1000 − 已写的 2）", wrote == 998);
        check("拒绝次数可观测", lcd.busyRejects() == 1);
        check("报错说明是带宽原因", busyMsg.contains("总线忙"));

        // ---- 3. tick 按真实经过时间恢复信用 ----
        lcd.tick(1_000_000L);                      // 再过 1ms
        boolean okAfterTick = true;
        try {
            for (int i = 0; i < 1000; i++) {
                lcd.store(1, 0x5A, Sizes.SIZE_8);
            }
        } catch (MemoryAccessException e) {
            okAfterTick = false;
        }
        check("tick(1ms) 后又可写满一个突发", okAfterTick);

        // ---- 4. 快接口不该被限住（带宽差 1000 倍，同样写 1 万次毫无压力）----
        final AutoForwardWindowDevice pcie = new AutoForwardWindowDevice("PCIE-FWD", 2, 1_000_000_000L);
        final Recorder fast = new Recorder();
        pcie.sink(fast);
        boolean fastOk = true;
        try {
            for (int i = 0; i < 10_000; i++) {
                pcie.store(1, i & 0xFFFF, Sizes.SIZE_16);
            }
        } catch (MemoryAccessException e) {
            fastOk = false;
        }
        check("1GB/s 接口写 1 万次不被拒（16 位窗口）", fastOk && fast.datas.size() == 10_000);
        check("16 位窗口只留低 16 位（掩码正确）", pcie.lastValue() == ((10_000 - 1) & 0xFFFF));

        // ---- 5. 没接接收方 ⇒ 明确报错（不允许静默丢弃固件的写）----
        final AutoForwardWindowDevice lonely = new AutoForwardWindowDevice("NO-SINK", 1, 1_000_000L);
        boolean noSink = false;
        try {
            lonely.store(0, 1, Sizes.SIZE_8);
        } catch (IllegalStateException e) {
            noSink = e.getMessage().contains("没有接收方");
        }
        check("未接 Sink ⇒ 明确报错", noSink);

        // ---- 6. 只写窗口：读要明确报错（本阶段不做可读外部总线）----
        boolean readRejected = false;
        try {
            lcd.load(0, Sizes.SIZE_8);
        } catch (MemoryAccessException e) {
            readRejected = e.getMessage().contains("只写窗口");
        }
        check("只写窗口的 load ⇒ 明确报错", readRejected);

        // ---- 7. 越界与宽度校验 ----
        boolean oob = false;
        try {
            lcd.store(99, 1, Sizes.SIZE_8);
        } catch (MemoryAccessException e) {
            oob = true;
        }
        check("窗口越界 ⇒ 报错", oob);

        boolean badWidth = false;
        try {
            lcd.store(0, 1, Sizes.SIZE_32);
        } catch (MemoryAccessException e) {
            badWidth = true;
        }
        check("8 位窗口拒绝 32 位访问", badWidth);

        System.out.println("[WINDOW] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 记录收到的传输（闸门用） */
    private static final class Recorder implements AutoForwardWindowDevice.Sink {
        final java.util.List<Integer> commands = new java.util.ArrayList<>();
        final java.util.List<Integer> datas = new java.util.ArrayList<>();

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
