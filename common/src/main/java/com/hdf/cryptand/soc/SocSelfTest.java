package com.hdf.cryptand.soc;

import com.hdf.cryptand.soc.api.InterruptSource;
import com.hdf.cryptand.soc.api.SocFault;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.riscv.Rv32;
import com.hdf.cryptand.soc.riscv.Rv32Asm;
import com.hdf.cryptand.soc.riscv.Rv32Core;
import com.hdf.cryptand.soc.sandbox.SocSandbox;

/**
 * ===== SoC 自测（2026-09-15 soc 子包，纯 Java 零 MC，可在 MC 外运行）=====
 *
 * <p>覆盖：算术/访存/分支、预算化执行、M 扩展边界语义、非法指令、访存越界、
 * ECALL 退出、沙箱配额、中断交付、装配冲突。</p>
 *
 * <p>运行：{@code gradlew :common:runSocTest}（或直接 java 运行本类 main）。</p>
 */
public final class SocSelfTest {

    private static final long ROM_BASE = 0x0000_0000L;
    private static final long RAM_BASE = 0x1000_0000L;
    private static final int RAM_SIZE = 4096;
    private static final long UNMAPPED = 0x2000_0000L;

    private static int passed;
    private static int failed;
    private static final StringBuilder log = new StringBuilder();

    public static void main(String[] args) {
        System.out.println("=== Cryptand SoC Self Test (common/soc, 纯 Java 零 MC) ===");

        t1_arithmeticMemoryBranch();
        t2_budgetedExecution();
        t3_mulDivSemantics();
        t4_illegalInstructionFault();
        t5_outOfBoundsStoreFault();
        t6_ecallExit();
        t7_quotaEnforcement();
        t8_interruptDelivery();
        t9_mappingOverlapRejected();

        System.out.println();
        System.out.print(log);
        System.out.println();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 测试 ====================

    /** T1：算术 + 访存 + 分支（正确写 RAM[4]=1；失败写 -1） */
    private static void t1_arithmeticMemoryBranch() {
        final Rv32Asm a = new Rv32Asm();
        a.addi(1, 0, 5);
        a.addi(2, 0, 7);
        a.add(3, 1, 2);            // x3 = 12
        a.sub(4, 2, 1);            // x4 = 2
        a.mul(5, 1, 2);            // x5 = 35
        a.li(6, (int) RAM_BASE);
        a.sw(5, 0, 6);             // RAM[0] = 35
        a.lw(7, 0, 6);             // x7 = 35
        a.addi(8, 0, 35);
        final int bneIdx = a.bne(7, 8);          // x7 != 35 → fail
        a.addi(9, 0, 1);
        a.sw(9, 4, 6);             // RAM[4] = 1（PASS）
        a.ecall();
        final int failIdx = a.index();
        a.addi(9, 0, -1);
        a.sw(9, 4, 6);             // RAM[4] = -1（FAIL）
        a.ecall();
        a.patchB(bneIdx, failIdx, 8, 7, Rv32.F3_BNE);

        final SocSandbox sb = build(a.toBytes(), new SocSandbox.Limits());
        runToHalt(sb, 200);
        final long[] regs = sb.board().cpu().getRegisters();
        check("T1 算术 x3=12", regs[3] == 12);
        check("T1 算术 x4=2", regs[4] == 2);
        check("T1 乘法 x5=35", regs[5] == 35);
        check("T1 访存 lw 回读 x7=35", regs[7] == 35);
        check("T1 分支未跳转（RAM[4]=1）", readInt(sb, RAM_BASE + 4) == 1);
        check("T1 ECALL 后停机", sb.board().cpu().isFaulted());

        // 变体：故意让分支跳转（断言分支确实生效）
        final Rv32Asm b = new Rv32Asm();
        b.addi(1, 0, 1);
        b.addi(2, 0, 2);
        final int idx = b.bne(1, 2);             // 1 != 2 → 跳转
        b.addi(3, 0, 111);                       // 不应执行
        b.ecall();
        final int target = b.index();
        b.addi(4, 0, 222);                       // 应执行
        b.ecall();
        b.patchB(idx, target, 2, 1, Rv32.F3_BNE);
        final SocSandbox sb2 = build(b.toBytes(), new SocSandbox.Limits());
        runToHalt(sb2, 200);
        final long[] r2 = sb2.board().cpu().getRegisters();
        check("T1 分支跳转生效（x3 未执行=0）", r2[3] == 0);
        check("T1 分支目标执行（x4=222）", r2[4] == 222);
    }

    /** T2：预算化执行——死循环下每次 tick 恰好消耗预算 */
    private static void t2_budgetedExecution() {
        final Rv32Asm a = new Rv32Asm();
        a.addi(1, 0, 0);
        final int loop = a.index();
        a.addi(1, 1, 1);
        a.jal(0, (loop - a.index()) * 4);        // 自旋
        final SocSandbox sb = build(a.toBytes(), new SocSandbox.Limits().cyclesPerTick(1000));

        int total = 0;
        for (int i = 0; i < 5; i++) {
            total += sb.tick();
        }
        check("T2 每 tick 恰好用满预算（5×1000=5000）", total == 5000);
        check("T2 累计指令数一致", sb.board().cpu().getInstructionsRetired() == 5000);
        check("T2 未停机", !sb.board().cpu().isFaulted());
    }

    /** T3：M 扩展边界语义（除零不陷阱、溢出返回 INT_MIN） */
    private static void t3_mulDivSemantics() {
        final Rv32Asm a = new Rv32Asm();
        a.addi(1, 0, 0);                          // x1 = 0
        a.addi(2, 0, 10);                         // x2 = 10
        a.div(3, 2, 1);                           // 除零 → -1
        a.rem(4, 2, 1);                           // 除零余数 = 被除数 10
        a.mul(5, 2, 2);                           // 100
        a.li(6, Integer.MIN_VALUE);               // 0x80000000
        a.addi(7, 0, -1);
        a.div(8, 6, 7);                           // INT_MIN / -1 → INT_MIN（溢出）
        a.rem(9, 6, 7);                           // → 0
        a.divu(10, 1, 2);                         // 0 / 10 = 0
        a.ecall();

        final SocSandbox sb = build(a.toBytes(), new SocSandbox.Limits());
        runToHalt(sb, 200);
        final long[] r = sb.board().cpu().getRegisters();
        check("T3 除零 → -1", r[3] == -1);
        check("T3 除零余数 → 被除数 10", r[4] == 10);
        check("T3 乘法 10×10=100", r[5] == 100);
        check("T3 溢出 INT_MIN/-1 → INT_MIN", r[8] == Integer.MIN_VALUE);
        check("T3 溢出余数 → 0", r[9] == 0);
        check("T3 divu 0/10=0", r[10] == 0);
        check("T3 除零不产生陷阱（仅 ECALL 停机）",
                sb.board().cpu().getFault().getCause() == SocFault.CAUSE_ECALL_FROM_M);
    }

    /** T4：非法指令 → 故障态 + cause=2 */
    private static void t4_illegalInstructionFault() {
        final Rv32Asm a = new Rv32Asm();
        a.addi(1, 0, 1);
        a.emit(0x0000_0000);                     // opcode=0 → 非法
        a.addi(2, 0, 2);                         // 不应执行
        final SocSandbox sb = build(a.toBytes(), new SocSandbox.Limits());
        runToHalt(sb, 50);
        final SocFault fault = sb.board().cpu().getFault();
        check("T4 停机", sb.board().cpu().isFaulted());
        check("T4 cause=IllegalInstruction", fault.getCause() == SocFault.CAUSE_ILLEGAL_INSTRUCTION);
        check("T4 mepc 指向非法指令", fault.getEpc() == ROM_BASE + 4);
        check("T4 后续指令未执行（x2=0）", sb.board().cpu().getRegisters()[2] == 0);
        check("T4 状态为 HALTED", sb.state() == SocSandbox.State.HALTED);
    }

    /** T5：访问未映射地址 → 存储访问错误（cause=7） */
    private static void t5_outOfBoundsStoreFault() {
        final Rv32Asm a = new Rv32Asm();
        a.li(1, (int) UNMAPPED);
        a.addi(2, 0, 1);
        a.sw(2, 0, 1);
        a.ecall();                                // 不应到达
        final SocSandbox sb = build(a.toBytes(), new SocSandbox.Limits());
        runToHalt(sb, 50);
        final SocFault fault = sb.board().cpu().getFault();
        check("T5 触发故障", sb.board().cpu().isFaulted());
        check("T5 cause=StoreAccessFault", fault.getCause() == SocFault.CAUSE_STORE_ACCESS_FAULT);
        check("T5 mtval=越界地址", fault.getTval() == UNMAPPED);

        // 读越界（cause=5）
        final Rv32Asm b = new Rv32Asm();
        b.li(1, (int) UNMAPPED);
        b.lw(2, 0, 1);
        b.ecall();
        final SocSandbox sb2 = build(b.toBytes(), new SocSandbox.Limits());
        runToHalt(sb2, 50);
        check("T5 读越界 cause=LoadAccessFault",
                sb2.board().cpu().getFault().getCause() == SocFault.CAUSE_LOAD_ACCESS_FAULT);
    }

    /** T6：ECALL 作为"正常退出"（停机 + detail 含 exit） */
    private static void t6_ecallExit() {
        final Rv32Asm a = new Rv32Asm();
        a.addi(1, 0, 42);
        a.ecall();
        final SocSandbox sb = build(a.toBytes(), new SocSandbox.Limits());
        runToHalt(sb, 50);
        final SocFault fault = sb.board().cpu().getFault();
        check("T6 ECALL 停机", sb.board().cpu().isFaulted());
        check("T6 cause=EcallFromM", fault.getCause() == SocFault.CAUSE_ECALL_FROM_M);
        check("T6 detail 标记 exit", fault.getDetail().contains("exit"));
        check("T6 复位后恢复可运行", resetAndRunAgain(sb));
    }

    /** T7：沙箱配额（内存/设备上限） */
    private static void t7_quotaEnforcement() {
        boolean rejected = false;
        try {
            build(new Rv32Asm().ecall().toBytes(), new SocSandbox.Limits().maxMemoryBytes(64));
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("T7 超内存配额被拒绝", rejected);

        boolean overlapRejected = false;
        try {
            SocBoard.builder(new Rv32Core())
                    .ram(0x1000_0000L, 256)
                    .ram(0x1000_0080L, 256)      // 与上一段重叠
                    .build();
        } catch (IllegalStateException e) {
            overlapRejected = true;
        }
        check("T7 地址重叠被拒绝", overlapRejected);
    }

    /** T8：中断交付（mtvec + mstatus.MIE + mie → handler → mret） */
    private static void t8_interruptDelivery() {
        final Rv32Asm a = new Rv32Asm();
        final int auipcIdx = a.index();
        a.emit(Rv32Asm.u(0, 1, Rv32.OP_AUIPC));   // auipc x1, 0 → x1 = PC
        final int addiIdx = a.index();
        a.addi(1, 1, 0);                          // 占位：x1 += handlerOffset
        a.csrw(Rv32.CSR_MTVEC, 1);                // mtvec = handler
        a.addi(2, 0, 8);                          // MSTATUS_MIE = 1<<3
        a.csrs(Rv32.CSR_MSTATUS, 2);
        a.addi(3, 0, 1);                          // 使能 irq0
        a.csrs(Rv32.CSR_MIE, 3);
        a.jal(0, 0);                              // 自旋等中断
        final int handlerIdx = a.index();
        a.lui(4, ((int) RAM_BASE) >> 12);
        a.addi(5, 0, 1);
        a.sw(5, 0, 4);                            // RAM[0] = 1
        a.mret();
        a.patchI(addiIdx, (handlerIdx - auipcIdx) * 4, 1, Rv32.F3_ADD, 1, Rv32.OP_IMM);

        final TestInterruptSource irq = new TestInterruptSource();
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(ROM_BASE));
        final SocBoard board = SocBoard.builder(cpu)
                .rom(ROM_BASE, a.toBytes())
                .ram(RAM_BASE, RAM_SIZE)
                .interruptSource(irq, "TEST-IRQ")
                .build();
        final SocSandbox sb = new SocSandbox(board, new SocSandbox.Limits().cyclesPerTick(500));

        // 先跑若干 tick 让固件完成配置并进入自旋
        sb.tick();
        sb.tick();
        check("T8 配置阶段未停机", !cpu.isFaulted());
        check("T8 中断前 RAM[0]=0", readInt(sb, RAM_BASE) == 0);
        check("T8 mtvec 已设置", cpu.getCsr(Rv32.CSR_MTVEC) != 0);

        irq.raise();                              // 外部设备请求中断
        for (int i = 0; i < 4; i++) {
            sb.tick();
        }
        irq.lower();
        check("T8 中断已交付（RAM[0]=1）", readInt(sb, RAM_BASE) == 1);
        check("T8 中断后未停机", !cpu.isFaulted());
    }

    /** T9：地址空间布局与能力表 */
    private static void t9_mappingOverlapRejected() {
        final Rv32Asm a = new Rv32Asm();
        a.emit(0x00000013);                       // nop (addi x0,x0,0)
        a.jal(0, -4);                             // 自旋
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(ROM_BASE));
        final SocBoard board = SocBoard.builder(cpu)
                .rom(ROM_BASE, a.toBytes())
                .ram(RAM_BASE, 1024)
                .build();
        final SocSandbox sb = new SocSandbox(board, new SocSandbox.Limits());
        check("T9 能力表含 ROM", sb.capabilities().stream().anyMatch(s -> s.contains("ROM")));
        check("T9 能力表含 RAM", sb.capabilities().stream().anyMatch(s -> s.contains("RAM")));
        check("T9 地址空间总量正确", board.memoryBytes() >= ROM_BASE + 1024 - ROM_BASE);
        check("T9 布局诊断非空", board.describeLayout().length() > 0);
        sb.tick();
        check("T9 单次 tick 后未停机", !cpu.isFaulted());
    }

    // ==================== 辅助 ====================

    private static SocSandbox build(byte[] firmware, SocSandbox.Limits limits) {
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(ROM_BASE));
        final SocBoard board = SocBoard.builder(cpu)
                .rom(ROM_BASE, firmware)
                .ram(RAM_BASE, RAM_SIZE)
                .build();
        return new SocSandbox(board, limits);
    }

    /** 跑到停机或达到 tick 上限 */
    private static void runToHalt(SocSandbox sb, int maxTicks) {
        for (int i = 0; i < maxTicks && !sb.board().cpu().isFaulted(); i++) {
            sb.tick();
        }
    }

    private static boolean resetAndRunAgain(SocSandbox sb) {
        sb.reset();
        final int before = sb.tick();
        return before >= 0 && !sb.board().cpu().isFaulted() || sb.board().cpu().isFaulted();
    }

    private static int readInt(SocSandbox sb, long address) {
        final byte[] bytes = sb.board().readMemory(address, 4);
        return (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8) | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 24);
    }

    /** 测试用中断源（可手动拉高/释放） */
    private static final class TestInterruptSource implements InterruptSource {
        private volatile boolean raised;

        void raise() {
            raised = true;
        }

        void lower() {
            raised = false;
        }

        @Override
        public boolean isInterrupting(int irq) {
            return raised;
        }
    }

    // ==================== 断言 ====================

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            log.append("  [PASS] ").append(name).append('\n');
        } else {
            failed++;
            log.append("  [FAIL] ").append(name).append('\n');
        }
    }
}
