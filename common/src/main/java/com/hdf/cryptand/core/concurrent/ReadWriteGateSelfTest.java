package com.hdf.cryptand.core.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程执行表门闸（ReadWriteGate）自测（2026-08-30 用户：同一资源访问线程执行表）。
 *
 * <p>验证用户语义（最终版：不区分读写，直接乱序/顺序）：
 *   1) 乱序并发放行（多个乱序同时执行，计数正确）；
 *   2) 顺序独占（顺序执行期间乱序阻塞；顺序退出后清场）；
 *   3) "队首乱序 &amp; 已有乱序执行 → 批量放行队列中全部乱序"；
 *   4) 顺序排前只放行该顺序（独占），后续乱序等待清场；
 *   5) 容量上限默认 30 + 运行时 setCapacity 更改；
 *   6) 退出计数递减与总计数一致。
 *
 * 运行：{@code ./gradlew :common:runReadWriteGateTest}
 */
public final class ReadWriteGateSelfTest {

    public static void main(String[] args) throws Exception {
        boolean pass = true;
        System.out.println("==== 线程执行表门闸自测（ReadWriteGate） ====");

        // ===== 1) 乱序并发 =====
        System.out.println("1) 乱序并发放行（多个乱序同时执行）");
        ReadWriteGate gate1 = new ReadWriteGate();
        int count = 5;
        CountDownLatch in1 = new CountDownLatch(count);
        CountDownLatch hold1 = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(() -> {
                try {
                    gate1.enter(ReadWriteGate.AccessMode.UNORDERED);
                    int c = running.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    in1.countDown();
                    hold1.await();
                    running.decrementAndGet();
                    gate1.exit(ReadWriteGate.AccessMode.UNORDERED);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
        }
        in1.await();
        boolean c1 = maxConcurrent.get() == count && gate1.totalCount() == count;
        System.out.println("   并发=" + maxConcurrent.get() + "/" + count
                + " 总计数=" + gate1.totalCount() + " " + mark(c1));
        hold1.countDown();
        Thread.sleep(300);
        boolean c1b = gate1.totalCount() == 0;
        System.out.println("   退出后总计数=" + gate1.totalCount() + " " + mark(c1b));
        pass &= c1 && c1b;

        // ===== 2) 顺序独占（顺序执行期间乱序阻塞）=====
        System.out.println("2) 顺序独占（顺序执行期间乱序等待清场）");
        ReadWriteGate gate2 = new ReadWriteGate();
        CountDownLatch orderIn = new CountDownLatch(1);
        CountDownLatch hold2 = new CountDownLatch(1);
        Thread w = new Thread(() -> {
            try {
                gate2.enter(ReadWriteGate.AccessMode.ORDERED);
                orderIn.countDown();
                hold2.await();
                gate2.exit(ReadWriteGate.AccessMode.ORDERED);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        w.start();
        orderIn.await();
        AtomicInteger grantedU = new AtomicInteger();
        Thread r = new Thread(() -> {
            try {
                gate2.enter(ReadWriteGate.AccessMode.UNORDERED);
                grantedU.incrementAndGet();
                gate2.exit(ReadWriteGate.AccessMode.UNORDERED);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        r.start();
        Thread.sleep(200);
        boolean c2a = grantedU.get() == 0;                      // 顺序执行中乱序未放行
        System.out.println("   顺序执行中乱序放行=" + grantedU.get() + " " + mark(c2a));
        hold2.countDown();
        Thread.sleep(300);
        boolean c2b = gate2.totalCount() == 0;
        System.out.println("   顺序退出后总计数=" + gate2.totalCount() + " " + mark(c2b));
        pass &= c2a && c2b;

        // ===== 3) 队首乱序 + 已有乱序 → 批量放行全部乱序 =====
        System.out.println("3) 队首乱序&已有乱序执行 → 批量放行队列中全部乱序");
        ReadWriteGate gate3 = new ReadWriteGate();
        CountDownLatch firstIn = new CountDownLatch(1);
        CountDownLatch hold3 = new CountDownLatch(1);
        AtomicInteger granted3 = new AtomicInteger();
        Thread f = new Thread(() -> {
            try {
                gate3.enter(ReadWriteGate.AccessMode.UNORDERED);
                firstIn.countDown();
                hold3.await();
                gate3.exit(ReadWriteGate.AccessMode.UNORDERED);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        f.start();
        firstIn.await();                                        // 乱序1已在执行
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    gate3.enter(ReadWriteGate.AccessMode.UNORDERED);
                    granted3.incrementAndGet();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        }
        Thread.sleep(200);
        boolean c3 = granted3.get() == 3 && gate3.unorderedCount() == 4;
        System.out.println("   批放行=" + granted3.get() + " 乱序计数=" + gate3.unorderedCount()
                + " " + mark(c3));
        hold3.countDown();
        Thread.sleep(300);
        pass &= c3;

        // ===== 4) 容量上限（默认）+ setCapacity 运行时更改 =====
        System.out.println("4) 容量上限 + 运行时 setCapacity 更改");
        ReadWriteGate gate4 = new ReadWriteGate(2);             // 初始容量 2
        CountDownLatch allIn = new CountDownLatch(2);
        CountDownLatch hold4 = new CountDownLatch(1);
        AtomicInteger overflow = new AtomicInteger();
        for (int i = 0; i < 4; i++) {                           // 4 个乱序，最多 2 并发
            new Thread(() -> {
                try {
                    gate4.enter(ReadWriteGate.AccessMode.UNORDERED);
                    allIn.countDown();
                    hold4.await();
                    gate4.exit(ReadWriteGate.AccessMode.UNORDERED);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                overflow.incrementAndGet();
            }).start();
        }
        Thread.sleep(300);
        boolean c4a = gate4.unorderedCount() == 2 && gate4.totalCount() == 2;
        System.out.println("   容量2 乱序=" + gate4.unorderedCount() + " 总=" + gate4.totalCount()
                + " " + mark(c4a));
        // 运行时放大容量 → 排队乱序应立即放行
        gate4.setCapacity(4);
        Thread.sleep(200);
        boolean c4b = gate4.unorderedCount() == 4;
        System.out.println("   setCapacity(4) 后乱序=" + gate4.unorderedCount() + " " + mark(c4b));
        hold4.countDown();
        Thread.sleep(400);
        boolean c4c = gate4.totalCount() == 0 && overflow.get() >= 4;
        System.out.println("   全部退出总=" + gate4.totalCount() + " 执行=" + overflow.get()
                + " " + mark(c4c));
        pass &= c4a && c4b && c4c;

        // ===== 5) 默认容量 30 =====
        System.out.println("5) 默认容量 30");
        ReadWriteGate gate5 = new ReadWriteGate();
        boolean c5 = gate5.capacity() == ReadWriteGate.DEFAULT_MAX_THREADS
                && ReadWriteGate.DEFAULT_MAX_THREADS == 30;
        System.out.println("   capacity=" + gate5.capacity() + " 默认="
                + ReadWriteGate.DEFAULT_MAX_THREADS + " " + mark(c5));
        pass &= c5;

        System.out.println();
        System.out.println(pass ? "==== 全部通过 ✅ ====" : "==== 存在失败 ❌ ====");
        System.exit(pass ? 0 : 1);
    }

    /** 控制台编码兼容标记（✅/❌ 在某些 PowerShell 显示为 ?） */
    private static String mark(boolean ok) {
        return ok ? "[PASS]" : "[FAIL]";
    }
}
