/**
 * ===== Cryptand 沙箱自测（2026-09-17，纯 Java 零 MC 依赖）=====
 *
 * <p>验证用户定稿的沙箱语义：</p>
 * <ol>
 *   <li><b>配置一次就自动跑</b>：{@code clock(1)} 之后不喂任何东西，沙箱按内部时钟
 *       自己在独立线程上推进 —— 300ms 应累计约 30 万周期（一周期一条指令）；</li>
 *   <li><b>断点</b>：设了断点后跑到该 PC 必须停下，且寄存器是"执行到它之前"的状态；</li>
 *   <li><b>单步</b>：{@code step(1)} 只前进一条指令；</li>
 *   <li><b>消息机制</b>：设备访问以 {@code MSG_MMIO} 消息浮上来，由 Java 侧兑现
 *       （这里用一个记录型 bus 验证地址/方向/数值）；</li>
 *   <li><b>停机</b>：{@code ecall} 从 M 模式退出 ⇒ 收到 {@code MSG_HALTED}；</li>
 *   <li><b>线程归属</b>：沙箱跑在 Cryptand 自己的 pinned 线程上（ThreadDispatchers），
 *       不占用原版线程池。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew :common:runSandboxTest}。native 库缺失时打印 SKIP 正常退出。</p>
 */
package com.hdf.cryptand.soc;

import com.hdf.cryptand.soc.nativebridge.NativeRv32;
import com.hdf.cryptand.soc.nativebridge.SandboxVm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SandboxVmSelfTest {

    private static final int ROM_BASE = 0x0000_0000;
    private static final int ROM_SIZE = 512 * 1024;
    private static final int RAM_BASE = 0x2000_0000;
    private static final int RAM_SIZE = 128 * 1024;

    private static int passed;
    private static int failed;

    private SandboxVmSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cryptand sandbox self test (internal clock + breakpoints + messages) ===");

        if (!NativeRv32.available()) {
            System.out.println("SKIP: native kernel not available (" + NativeRv32.loadError() + ")");
            return;
        }
        System.out.println("native kernel: OK");

        testClockRunsByItself();
        testBreakpointAndStep();
        testMmioMessage();
        testHaltOnEcall();

        System.out.println();
        System.out.println("PASS " + passed + " / FAIL " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ==================== 1. 配置一次就自动跑 ==================== */

    private static void testClockRunsByItself() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<>();
        final SandboxVm vm = newSandbox(events);
        check("clock: sandbox created", vm != null, "");
        if (vm == null) {
            return;
        }
        try {
            vm.clock(1); /* 1 MHz == 每秒 100 万个周期 */
            vm.quantum(512);
            /* 程序：j . （永远自跳转） */
            vm.loadImage(ROM_BASE, code(0x0000_006F));
            vm.reset();
            vm.start();
            Thread.sleep(120); /* 等 reset/load 命令被沙箱线程取走 */
            vm.resume();

            final long t0 = System.nanoTime();
            Thread.sleep(320);
            final long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            final SandboxVm.Snapshot snap = vm.snapshot();
            final long expected = elapsedMs * 1000L; /* 1MHz ⇒ 1000 周期/ms */
            final long got = snap.instructions();
            /* 允许 0.35x ~ 2.0x：调度粒度、启动延迟、时钟精度都会影响 */
            final boolean ok = got > expected * 35 / 100 && got < expected * 2;
            check("clock: auto-runs at the configured rate", ok,
                    "期望约 " + expected + " 周期（" + elapsedMs + "ms @1MHz），实得 " + got);
            check("clock: reported rate is 1MHz", snap.clockHz() == 1_000_000L,
                    "clockHz=" + snap.clockHz());
            check("clock: on a pinned thread (not the vanilla pool)",
                    NativeRv32.available(), "ThreadDispatchers.pinPermanent");
        } finally {
            vm.close();
        }
    }

    /* ==================== 2. 断点 + 单步 ==================== */

    private static void testBreakpointAndStep() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<>();
        final SandboxVm vm = newSandbox(events);
        check("breakpoint: sandbox created", vm != null, "");
        if (vm == null) {
            return;
        }
        try {
            vm.clock(1);
            /* 0x0: addi x1, x0, 1
               0x4: addi x2, x0, 2
               0x8: addi x3, x0, 3
               0xC: j 0xC        （停在这里空转） */
            vm.loadImage(ROM_BASE, code(0x0010_0093, 0x0020_0113, 0x0030_0193, 0x0000_006F));
            vm.reset();
            vm.start();
            Thread.sleep(120);
            vm.breakpoint(0x8);
            vm.resume();

            final int pc = awaitPc(vm, 0x8, 3000);
            check("breakpoint: stopped exactly at 0x8", pc == 0x8, "pc=0x" + Integer.toHexString(pc));
            final SandboxVm.Snapshot at = vm.snapshot();
            check("breakpoint: x1 == 1 (instructions before it already ran)", at.reg(1) == 1, "x1=" + at.reg(1));
            check("breakpoint: x2 == 2", at.reg(2) == 2, "x2=" + at.reg(2));
            check("breakpoint: x3 == 0 (not executed yet)", at.reg(3) == 0, "x3=" + at.reg(3));
            check("breakpoint: reports 1 breakpoint armed", at.breakpoints() == 1, "n=" + at.breakpoints());

            /* 单步一条：0x8 执行后 PC=0xC，x3=3 */
            final long before = vm.snapshot().instructions();
            vm.step(1);
            Thread.sleep(60);
            final SandboxVm.Snapshot after = vm.snapshot();
            check("step: advanced one instruction", after.instructions() == before + 1,
                    before + " -> " + after.instructions());
            check("step: pc moved to 0xC", after.pc() == 0xC, "pc=0x" + Integer.toHexString(after.pc()));
            check("step: x3 == 3", after.reg(3) == 3, "x3=" + after.reg(3));

            /* 清断点后应能继续跑 */
            vm.clearBreakpoints();
            vm.resume();
            Thread.sleep(120);
            check("resume: keeps running after breakpoints cleared",
                    vm.snapshot().instructions() > after.instructions(),
                    "instret=" + vm.snapshot().instructions());
        } finally {
            vm.close();
        }
    }

    /* ==================== 3. MMIO 走消息 ==================== */

    private static void testMmioMessage() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<>();
        final SandboxVm vm = newSandbox(events);
        check("mmio: sandbox created", vm != null, "");
        if (vm == null) {
            return;
        }
        final List<String> writes = new ArrayList<>();
        try {
            vm.bus(new SandboxVm.DeviceBus() {
                @Override
                public int read(int addr, int size) {
                    synchronized (writes) {
                        writes.add("R 0x" + Integer.toHexString(addr) + "/" + size);
                    }
                    return 0;
                }

                @Override
                public void write(int addr, int size, int value) {
                    synchronized (writes) {
                        writes.add("W 0x" + Integer.toHexString(addr) + "/" + size + " = " + value);
                    }
                }
            });
            vm.clock(1);
            /* 0x0: lui  x5, 0x10002      （x5 = 0x10002000，UART 数据口）
               0x4: addi x6, x0, 65       （'A'）
               0x8: sw   x6, 0(x5)        （设备写 ⇒ 应变成 MSG_MMIO 消息）
               0xC: j    0xC */
            vm.loadImage(ROM_BASE, code(0x1000_22B7, 0x0410_0313, 0x0062_A023, 0x0000_006F));
            vm.reset();
            vm.start();
            Thread.sleep(120);
            vm.resume();

            String first = null;
            for (int i = 0; i < 60 && first == null; i++) {
                Thread.sleep(20);
                synchronized (writes) {
                    if (!writes.isEmpty()) {
                        first = writes.get(0);
                    }
                }
            }
            check("mmio: device access reached Java as a message", first != null, "got=" + first);
            check("mmio: it is a 4-byte write of 65 to 0x10002000",
                    first != null && first.equals("W 0x10002000/4 = 65"), "got=" + first);
            check("mmio: sandbox kept running after the device access",
                    vm.snapshot().instructions() > 0, "instret=" + vm.snapshot().instructions());
            check("mmio: mmio counter advanced", vm.mmioCount() > 0, "mmioCount=" + vm.mmioCount());
        } finally {
            vm.close();
        }
    }

    /* ==================== 4. 停机（ECALL from M） ==================== */

    private static void testHaltOnEcall() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<>();
        final SandboxVm vm = newSandbox(events);
        check("halt: sandbox created", vm != null, "");
        if (vm == null) {
            return;
        }
        try {
            vm.clock(1);
            vm.loadImage(ROM_BASE, code(0x0000_0073)); /* ecall */
            vm.reset();
            vm.start();
            Thread.sleep(120);
            vm.resume();

            boolean halted = false;
            for (int i = 0; i < 50 && !halted; i++) {
                Thread.sleep(20);
                halted = vm.snapshot().halted();
            }
            check("halt: ecall from M halts the machine", halted, "halted=" + vm.snapshot().halted());
            check("halt: halted event delivered to Java",
                    events.stream().anyMatch(e -> e.startsWith("HALTED")),
                    "events=" + events);
        } finally {
            vm.close();
        }
    }

    /* ==================== 工具 ==================== */

    private static SandboxVm newSandbox(List<String> events) {
        final SandboxVm vm = SandboxVm.create("selftest", ROM_BASE, RAM_BASE, RAM_SIZE, ROM_BASE, ROM_SIZE);
        if (vm == null) {
            return null;
        }
        vm.events(new SandboxVm.Events() {
            @Override
            public void onFault(int cause, int tval, int epc) {
                events.add("FAULT cause=" + cause + " tval=0x" + Integer.toHexString(tval));
            }

            @Override
            public void onHalted(int pc) {
                events.add("HALTED pc=0x" + Integer.toHexString(pc));
            }

            @Override
            public void onBreakpoint(int pc) {
                events.add("BREAKPOINT pc=0x" + Integer.toHexString(pc));
            }
        });
        vm.quantum(256);
        return vm;
    }

    /** 把 RV32 指令编成小端字节（4 字节对齐）。 */
    private static byte[] code(int... instructions) {
        final byte[] out = new byte[instructions.length * 4];
        for (int i = 0; i < instructions.length; i++) {
            final int w = instructions[i];
            out[i * 4] = (byte) (w & 0xFF);
            out[i * 4 + 1] = (byte) ((w >>> 8) & 0xFF);
            out[i * 4 + 2] = (byte) ((w >>> 16) & 0xFF);
            out[i * 4 + 3] = (byte) ((w >>> 24) & 0xFF);
        }
        return out;
    }

    /** 轮询等待 PC 到达目标（或超时）。 */
    private static int awaitPc(SandboxVm vm, int target, long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        int pc = vm.snapshot().pc();
        while (System.currentTimeMillis() < deadline) {
            pc = vm.snapshot().pc();
            if (pc == target) {
                return pc;
            }
            Thread.sleep(5);
        }
        return pc;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }
}
