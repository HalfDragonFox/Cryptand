package com.hdf.cryptand.soc;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.riscv.Rv32Asm;
import com.hdf.cryptand.soc.riscv.Rv32Core;
import com.hdf.cryptand.soc.runtime.SocScheduler;
import com.hdf.cryptand.soc.sandbox.SocSandbox;

import java.util.List;

/**
 * ===== SoC 调度器自测（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>验证：多芯片并行调度、非阻塞派发、负载自适应升级（重负载 → 独占常驻线程）、
 * 轻负载保持共享池、配额限制、关停释放。</p>
 *
 * <p>运行：{@code gradlew :common:runSocSchedulerTest}。</p>
 */
public final class SocSchedulerSelfTest {

    private static final long ROM_BASE = 0x0000_0000L;
    private static final long RAM_BASE = 0x1000_0000L;
    private static final int RAM_SIZE = 1024;

    private static int passed;
    private static int failed;
    private static final StringBuilder log = new StringBuilder();

    public static void main(String[] args) {
        System.out.println("=== Cryptand SoC Scheduler Self Test (common/soc/runtime) ===");

        // 自旋固件（持续消耗预算，用于制造稳定负载）
        final Rv32Asm a = new Rv32Asm();
        a.addi(1, 0, 0);
        final int loop = a.index();
        a.addi(1, 1, 1);
        a.jal(0, (loop - a.index()) * 4);
        final byte[] firmware = a.toBytes();

        final SocScheduler.Limits limits = new SocScheduler.Limits()
                .maxSandboxes(3)
                .promoteThresholdNanos(200_000L)     // 200us
                .demoteThresholdNanos(50_000L)       // 50us
                .promoteAfterTicks(5)
                .demoteAfterTicks(120)
                .cooldownTicks(10);

        final SocScheduler scheduler = new SocScheduler(ThreadDispatchers.get(), limits);

        final SocSandbox light1 = chip(firmware, 2_000);
        final SocSandbox light2 = chip(firmware, 2_000);
        final SocSandbox heavy = chip(firmware, 500_000);

        check("S1 注册成功（3 个）",
                scheduler.register("light-1", light1)
                        && scheduler.register("light-2", light2)
                        && scheduler.register("heavy-1", heavy));
        check("S1 超配额被拒（maxSandboxes=3）",
                !scheduler.register("extra-1", chip(firmware, 1_000)));
        check("S1 重复 id 被拒", !scheduler.register("light-1", light1));
        check("S1 芯片数 = 3", scheduler.size() == 3);

        // 驱动 150 tick；每 tick 只派发（主线程零等待），测试里用 awaitIdle 收敛
        final int ticks = 150;
        int idleTimeouts = 0;
        for (int i = 0; i < ticks; i++) {
            scheduler.tickAll();
            if (!scheduler.awaitIdle(5_000)) {
                idleTimeouts++;
            }
        }
        check("S2 无 tick 等待超时（背压可控）", idleTimeouts == 0);

        final List<SocScheduler.Status> statuses = scheduler.statuses();
        final SocScheduler.Status sLight1 = find(statuses, "light-1");
        final SocScheduler.Status sLight2 = find(statuses, "light-2");
        final SocScheduler.Status sHeavy = find(statuses, "heavy-1");

        check("S3 轻芯片1 执行满 tick", sLight1 != null && sLight1.ticks() >= ticks - 2);
        check("S3 轻芯片2 执行满 tick", sLight2 != null && sLight2.ticks() >= ticks - 2);
        check("S3 重芯片 执行满 tick", sHeavy != null && sHeavy.ticks() >= ticks - 2);

        check("S4 重芯片已升级为独占线程", sHeavy != null && sHeavy.dedicated());
        check("S4 轻芯片保持共享池", sLight1 != null && !sLight1.dedicated());
        check("S4 独占线程数 = 1", scheduler.dedicatedCount() == 1);

        check("S5 轻芯片每 tick 耗时应远小于重芯片",
                sLight1 != null && sHeavy != null && sHeavy.lastTickNanos() > sLight1.lastTickNanos() * 5);
        check("S5 无故障（轻1）", sLight1 != null && sLight1.fault().isEmpty());
        check("S5 无故障（重）", sHeavy != null && sHeavy.fault().isEmpty());

        // 实际执行量校验（自旋固件不会停机 ⇒ 每 tick 用满预算）
        check("S6 重芯片累计指令数 ≈ ticks × 预算",
                heavy.cycles() >= (long) (ticks - 3) * 500_000 * 0.9);
        check("S6 轻芯片累计指令数 ≈ ticks × 预算",
                light1.cycles() >= (long) (ticks - 3) * 2_000 * 0.9);

        check("S7 诊断输出含独占标记", scheduler.describe().contains("[独占]"));

        // 注销释放
        check("S8 注销成功", scheduler.unregister("heavy-1"));
        check("S8 注销后独占线程归零", scheduler.dedicatedCount() == 0);
        check("S8 注销后芯片数 = 2", scheduler.size() == 2);

        scheduler.close();
        check("S9 close 后清空", scheduler.size() == 0);
        ThreadDispatchers.close();

        System.out.println();
        System.out.print(log);
        System.out.println();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static SocSandbox chip(byte[] firmware, int cyclesPerTick) {
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(ROM_BASE));
        final SocBoard board = SocBoard.builder(cpu)
                .rom(ROM_BASE, firmware)
                .ram(RAM_BASE, RAM_SIZE)
                .build();
        return new SocSandbox(board, new SocSandbox.Limits().cyclesPerTick(cyclesPerTick));
    }

    private static SocScheduler.Status find(List<SocScheduler.Status> list, String id) {
        for (SocScheduler.Status s : list) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

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
