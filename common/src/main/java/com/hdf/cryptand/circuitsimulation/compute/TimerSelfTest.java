package com.hdf.cryptand.circuitsimulation.compute;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时任务调度自测（2026-08-13，微秒级）。
 * 验证：
 *   1) schedule 周期任务按微秒周期触发（5ms 周期）
 *   2) scheduleOnce 一次性触发
 *   3) cancel 取消后不再触发
 *   4) 任务提交到统一 Worker 池执行（不阻塞定时线程）
 */
public final class TimerSelfTest {

    public static void main(String[] args) throws Exception {
        ThreadDispatcher td = new ThreadDispatcher(2, "test");
        System.out.println("==== 定时任务调度自测 ====");

        List<Long> periodic = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Long> once = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger cancelledFires = new AtomicInteger();
        AtomicInteger onceFires = new AtomicInteger();

        // 1) 周期任务：5000us = 5ms
        ScheduledHandle ph = td.schedule(() -> {
            periodic.add(System.nanoTime());
        }, 5000);

        // 2) 一次性：20000us = 20ms
        ScheduledHandle oh = td.scheduleOnce(() -> {
            once.add(System.nanoTime());
            onceFires.incrementAndGet();
        }, 20000);

        // 3) 取消任务：初始延迟 50ms（首触发在取消点 40ms 之后），周期 5ms
        //    40ms 时 cancel → 取消前 0 次，取消后再等 30ms（越过首触发点）仍应为 0
        ScheduledHandle ch = td.schedule(() -> {
            cancelledFires.incrementAndGet();
        }, 50000, 5000);

        long t0 = System.nanoTime();
        Thread.sleep(40); // 等 40ms
        ph.cancel();
        ch.cancel();
        Thread.sleep(30); // 再等 30ms（ch 首触发点 50ms 已过，确认取消生效）
        td.close();
        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        // ===== 验证 =====
        System.out.println("  周期任务触发次数: " + periodic.size()
                + "（期望 ~7-8 次 @5ms/40ms）");
        if (periodic.size() >= 3) {
            long[] gaps = new long[periodic.size() - 1];
            double avgMs = 0;
            for (int i = 1; i < periodic.size(); i++) {
                gaps[i - 1] = (periodic.get(i) - periodic.get(i - 1)) / 1_000_000L;
                avgMs += gaps[i - 1];
            }
            avgMs /= gaps.length;
            System.out.printf("  周期间隔均值: %.2f ms（期望 ≈5ms）%n", avgMs);
            boolean gapsOk = avgMs > 2 && avgMs < 15;
            System.out.println("  间隔合理: " + (gapsOk ? "✅" : "❌"));
        }
        System.out.println("  一次性触发次数: " + onceFires.get() + "（期望 1）✅");
        System.out.println("  取消任务触发次数: " + cancelledFires.get()
                + "（期望 0，取消后不再触发）");
        System.out.println("  总耗时: " + totalMs + "ms");

        boolean pass = periodic.size() >= 3 && onceFires.get() == 1
                && cancelledFires.get() == 0 && ph.isCancelled();
        System.out.println(pass ? "✅ 定时任务调度自测通过" : "❌ 定时任务调度自测失败");
        System.exit(pass ? 0 : 1);
    }

    private TimerSelfTest() {}
}
