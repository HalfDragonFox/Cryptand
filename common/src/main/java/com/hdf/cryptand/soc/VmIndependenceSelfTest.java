package com.hdf.cryptand.soc;

import com.hdf.cryptand.soc.nativebridge.NativeRv32;
import com.hdf.cryptand.soc.nativebridge.NativeSandbox;
import com.hdf.cryptand.soc.riscv.Rv32Asm;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ===== 虚拟机独立机器 · 离线回归闸门（2026-09-28 用户定案）=====
 *
 * <p>定案原话：<i>"定时器也是虚拟机构建的和 uart 的这种模块，然后虚拟机天然是一台完全独立时间和运行的机器，
 * 不能被主线程绑定，主线程只允许一些消息操作与交互，周期管理全部由虚拟机完成"</i>；
 * 以及 <i>"宿主卡顿虚拟机不管，直到心跳停止指定时间后暂停"</i>。</p>
 *
 * <p>本闸门把这三条变成可复现断言（不需要 MC、不需要客户端）：</p>
 * <ol>
 *   <li><b>CLINT 在虚拟机里</b>：guest 只靠普通内存访问 mtime/mtimecmp 就能拿到 tick，
 *       宿主**一次都没下发中断位图**，也**没有产生任何 MMIO 事务**（零跨语言）。</li>
 *   <li><b>周期管理由虚拟机自己算</b>：标称 20 万周期/秒时，1.2 秒内只推进约 20 万周期
 *       （跑满即睡），而不是满载几千万。</li>
 *   <li><b>心跳看门狗</b>：心跳断了 250 ms ⇒ 虚拟机自己暂停（IDLE_PAUSED 消息）；
 *       再发一条心跳 ⇒ 自动恢复运行。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew :common:runVmIndependenceTest}（DLL 缺失时 SKIP 正常退出）。</p>
 */
public final class VmIndependenceSelfTest {

    private static final int ROM_BASE = 0x0000_0000;
    private static final int RAM_BASE = 0x2000_0000;
    private static final int CLINT_BASE = 0x1000_1000;
    private static final int RAM_SIZE = 64 * 1024;
    private static final int ROM_SIZE = 512 * 1024;

    /** tick 周期（周期数）：程序把 mtimecmp 设成 mtime + 这个值。 */
    private static final int TICK_CYCLES = 100;

    private static int passed;
    private static int failed;
    private static final StringBuilder log = new StringBuilder();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cryptand VM independence self test（CLINT / 自走周期 / 心跳看门狗）===");
        locateLibrary();
        if (!NativeRv32.available()) {
            System.out.println("[SKIP] native 内核不可用：" + NativeRv32.loadError());
            System.out.println("       （先跑 excode/cryptand-rv32/build.ps1）");
            System.exit(0);
        }
        System.out.println("[OK] native 内核已加载");

        sectionClint();
        sectionSelfPacingAndWatchdog();

        System.out.println(log);
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        System.exit(failed == 0 ? 0 : 1);
    }

    /* ==================== ① CLINT：定时器在虚拟机里 ==================== */

    private static void sectionClint() {
        System.out.println("--- ① CLINT（虚拟机构建的定时器）---");
        final byte[] firmware = buildTickProgram();

        final long h = NativeRv32.create(ROM_BASE, RAM_BASE, RAM_SIZE, ROM_BASE, ROM_SIZE);
        try {
            NativeRv32.loadImage(h, ROM_BASE, firmware);
            NativeRv32.reset(h);
            // ⚠ 全程**不调用** setPendingInterrupts：MTIP 只能来自虚拟机自己的 CLINT
            final int ran = NativeRv32.step(h, 1000);
            check("执行了 1000 条指令（未被定时器打断成停机）", ran == 1000, "ran=" + ran);
            check("定时器访问没有产生任何 MMIO 事务（零跨语言）", !NativeRv32.hasMmio(h), "hasMmio=true");

            final int ticks = readInt(h, RAM_BASE + 4);
            final int expect = 1000 / TICK_CYCLES;
            check("虚拟机自产 tick 次数 ≈ " + expect + "（实测 " + ticks + "）",
                    ticks >= expect - 2 && ticks <= expect + 2, "ticks=" + ticks);

            final int mtime1 = readInt(h, RAM_BASE + 8);
            check("guest 读到的 mtime > 0 且随执行周期推进", mtime1 > 0, "mtime=" + mtime1);

            NativeRv32.step(h, 1000);
            final int mtime2 = readInt(h, RAM_BASE + 8);
            check("再跑 1000 条 ⇒ mtime 又推进约 1000（差 " + (mtime2 - mtime1) + "）",
                    mtime2 - mtime1 >= 900 && mtime2 - mtime1 <= 1100, "delta=" + (mtime2 - mtime1));
        } finally {
            NativeRv32.destroy(h);
        }
    }

    /**
     * tick 程序：x3 = RAM 基址、x4 = CLINT 基址；主循环把 mtime 存进 RAM[8]；
     * 中断处理程序把 RAM[4] 计数 +1 并把 mtimecmp 推后 TICK_CYCLES。
     *
     * <p>两趟装配：{@code li} 会按值展开成 1~2 条指令，所以 handler 的偏移必须由
     * {@link Rv32Asm#index()} 算出来，不能手数下标；第二趟用真实偏移回填 mtvec
     * （一条 {@code addi} 就够，偏移远小于 2KB）。</p>
     */
    private static byte[] buildTickProgram() {
        int handlerOffset = 0;
        for (int pass = 0; pass < 2; pass++) {
            final Rv32Asm asm = new Rv32Asm();
            asm.li(3, RAM_BASE);                      // x3 = RAM
            asm.li(4, CLINT_BASE);                    // x4 = CLINT
            asm.addi(1, 0, handlerOffset);            // x1 = handler（第一趟占位 0）
            asm.csrw(0x305, 1);                       // mtvec = handler
            asm.li(1, 0x80);                          // MTIE = bit7
            asm.csrs(0x304, 1);                       // mie |= MTIE
            asm.csrwi(0x300, 8);                      // mstatus.MIE = 1
            asm.lw(5, 0, 4);                          // mtime_lo
            asm.addi(5, 5, TICK_CYCLES);
            asm.sw(5, 8, 4);                          // mtimecmp_lo = mtime + TICK（写即启用）
            final int loop = asm.index();
            asm.lw(6, 0, 4);                          // loop: mtime
            asm.sw(6, 8, 3);                          // RAM[8] = mtime（宿主可观测）
            asm.jal(0, (loop - asm.index()) * 4);     // 回到 loop
            final int handler = asm.index();
            asm.lw(7, 4, 3);                          // handler: 计数 +1
            asm.addi(7, 7, 1);
            asm.sw(7, 4, 3);                          // RAM[4] = tick 计数
            asm.lw(5, 0, 4);                          // 推后下一次到期
            asm.addi(5, 5, TICK_CYCLES);
            asm.sw(5, 8, 4);
            asm.mret();
            final byte[] bytes = asm.toBytes();
            if (pass == 1) {
                return bytes;
            }
            handlerOffset = handler * 4;
        }
        throw new IllegalStateException("unreachable");
    }

    /* ==================== ②③ 自走周期 + 心跳看门狗（沙箱级） ==================== */

    private static void sectionSelfPacingAndWatchdog() throws Exception {
        System.out.println("--- ② 周期管理由虚拟机自己算 / ③ 心跳看门狗 ---");
        final Rv32Asm asm = new Rv32Asm();
        final int loop = asm.index();
        asm.addi(1, 1, 1);
        asm.jal(0, (loop - asm.index()) * 4);
        final byte[] firmware = asm.toBytes();

        final long h = NativeSandbox.create(ROM_BASE, RAM_BASE, RAM_SIZE, ROM_BASE, ROM_SIZE);
        final Thread runner = new Thread(() -> NativeSandbox.run(h), "vm-independence-runner");
        runner.setDaemon(true);
        try {
            NativeSandbox.loadImage(h, ROM_BASE, firmware);
            NativeSandbox.setClockHz(h, 200_000L);        // 每秒 20 万周期：小配额便于观察节流
            NativeSandbox.setPauseOnSilence(h, 0);        // 先关看门狗，专测配额
            runner.start();
            NativeSandbox.resume(h);

            Thread.sleep(1200);
            final long instret = instretOf(h);
            check("自走配额：1.2 秒只推进约 24 万周期（" + instret + "），不是满载几千万",
                    instret > 120_000 && instret < 400_000, "instret=" + instret);

            // ③ 打开看门狗后停止心跳 ⇒ 等 IDLE_PAUSED；期间**不**发任何消息
            NativeSandbox.setPauseOnSilence(h, 250);      // 心跳断 250ms ⇒ 自暂停
            final int idleMsg = waitForType(h, NativeSandbox.MSG_IDLE_PAUSED, 3000);
            check("心跳断了 250ms ⇒ 虚拟机自己暂停（收到 IDLE_PAUSED 消息）", idleMsg == 1, "msg=" + idleMsg);

            final long before = instretOf(h);
            final long[] hb = new long[12];
            NativeSandbox.heartbeat(h, 0, 0, hb);
            check("心跳把它唤回：out[10](idlePaused) 已清零", hb[10] == 0, "idlePaused=" + hb[10]);
            NativeSandbox.heartbeat(h, 0, 0, hb);         // 持续心跳，别又睡着
            final long after = instretOf(h);
            check("恢复后周期继续推进（" + before + " → " + after + "）", after >= before, "no progress");
        } finally {
            NativeSandbox.stop(h);
            runner.join(2000);
            NativeSandbox.destroy(h);
        }
    }

    /* ==================== 工具 ==================== */

    private static long instretOf(long handle) {
        final long[] snap = new long[44];
        NativeSandbox.snapshot(handle, snap);
        return snap[33];
    }

    private static int waitForType(long handle, int type, int timeoutMs) {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final long[] msg = new long[5];
        while (System.nanoTime() < deadline) {
            final int remaining = (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
            if (NativeSandbox.waitMessage(handle, msg, Math.min(remaining, 200)) == 1 && msg[0] == type) {
                return 1;
            }
        }
        return 0;
    }

    private static int readInt(long handle, int addr) {
        final byte[] b = new byte[4];
        NativeRv32.readMemory(handle, addr, b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            log.append("  [PASS] ").append(what).append('\n');
        } else {
            failed++;
            log.append("  [FAIL] ").append(what).append("  ← ").append(detail).append('\n');
        }
    }

    /** 定位 DLL：显式系统属性 → 仓库 build/native → 游戏引擎目录。 */
    private static void locateLibrary() {
        if (System.getProperty("cryptand.rv32.lib") != null) {
            return;
        }
        final String env = System.getenv("CRYPTAND_RV32_DLL");
        if (env != null && Files.isRegularFile(Path.of(env))) {
            System.setProperty("cryptand.rv32.lib", Path.of(env).toAbsolutePath().toString());
            return;
        }
        final Path[] candidates = {
                Path.of("build", "native", "cryptand_rv32.dll"),
                Path.of("..", "build", "native", "cryptand_rv32.dll"),
                Path.of("neoforge", "run", "config", "cryptand", "engines", "cryptand_rv32.dll"),
                Path.of("..", "neoforge", "run", "config", "cryptand", "engines", "cryptand_rv32.dll"),
        };
        for (final Path p : candidates) {
            if (Files.isRegularFile(p)) {
                System.setProperty("cryptand.rv32.lib", p.toAbsolutePath().normalize().toString());
                return;
            }
        }
    }
}
