package com.hdf.cryptand.dynamic.task;

import com.hdf.cryptand.dynamic.api.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===== 派发框架离线闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>钉住"外部只声明、框架自动做"的四条语义：只读真并行、写真互斥、
 * 主线程操作在调用线程跑（默认同线程）、异常不吞。</p>
 *
 * <p>{@code ./gradlew :common:runTaskDispatcherTest}</p>
 */
public final class AutoTaskDispatcherSelfTest {

    private static int passed;
    private static int failed;

    private AutoTaskDispatcherSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        readOnlyRunsInParallel();
        writeIsMutuallyExclusive();
        mainThreadRunsOnCallerThreadByDefault();
        parallelFlagCannotMakeWritesParallel();
        failuresPropagate();

        System.out.println("[task-dispatcher] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 只读：同一资源键上应当真并行（4 个任务同时卡在 latch 上）。 */
    private static void readOnlyRunsInParallel() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final AutoTaskDispatcher dispatcher = new AutoTaskDispatcher(pool, false);
        try {
            final int n = 4;
            final CountDownLatch allEntered = new CountDownLatch(n);
            final AtomicInteger concurrent = new AtomicInteger();
            final AtomicInteger peak = new AtomicInteger();
            final List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(dispatcher.dispatch(TaskSpec.read("read-" + i, Stage.PROBE, "same-file"), () -> {
                    final int now = concurrent.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    allEntered.countDown();
                    // 等所有只读者都进来（只有真并行才能等到）——绝不 sleep
                    if (!allEntered.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("只读任务没有并行：只有 " + concurrent.get() + " 个进入");
                    }
                    concurrent.decrementAndGet();
                    return now;
                }));
            }
            for (CompletableFuture<Integer> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            check("只读：4 个任务真并行（并发峰值 " + peak.get() + "）", peak.get() >= 3);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 写：同一资源键上必须互斥（执行区间不重叠）。 */
    private static void writeIsMutuallyExclusive() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final AutoTaskDispatcher dispatcher = new AutoTaskDispatcher(pool, false);
        try {
            final AtomicInteger inside = new AtomicInteger();
            final AtomicInteger maxInside = new AtomicInteger();
            final AtomicLong overlapping = new AtomicLong();
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(dispatcher.dispatch(TaskSpec.write("write-" + i, Stage.EXTRACT, "same-file"), () -> {
                    if (inside.incrementAndGet() > 1) {
                        overlapping.incrementAndGet();
                    }
                    maxInside.accumulateAndGet(inside.get(), Math::max);
                    // 用 latch 模拟"写一小会儿"：绝不 sleep，只等一个立刻放行的信号
                    final CountDownLatch spin = new CountDownLatch(1);
                    spin.countDown();
                    spin.await(50, TimeUnit.MILLISECONDS);
                    inside.decrementAndGet();
                    return null;
                }));
            }
            for (CompletableFuture<Void> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            check("写：同一资源键上从不重叠（重叠次数 " + overlapping.get() + "）", overlapping.get() == 0L);
            check("写：同时最多 1 个在临界区（实得 " + maxInside.get() + "）", maxInside.get() == 1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 主线程操作：默认同线程直跑（common/测试的安全默认）。 */
    private static void mainThreadRunsOnCallerThreadByDefault() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final AutoTaskDispatcher dispatcher = new AutoTaskDispatcher(pool, false);
        try {
            final Thread caller = Thread.currentThread();
            final CompletableFuture<Void> f = dispatcher.mainThread(Stage.ATTACH, "attach-ui",
                    () -> check("主线程：默认在调用线程执行", Thread.currentThread() == caller));
            // mainThread 默认同线程 ⇒ future 已完成
            f.get(2, TimeUnit.SECONDS);
            check("主线程：派发计数", dispatcher.mainThreadCount() == 1L);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 写即使声明 parallel=true 也不能并行（不能让调用方把自己坑了）。 */
    private static void parallelFlagCannotMakeWritesParallel() {
        final TaskSpec write = new TaskSpec("w", Stage.LOAD, AccessMode.WRITE_ONLY, true, "k", false);
        check("写 + parallel=true ⇒ effectiveParallel=false", !write.effectiveParallel());
        final TaskSpec read = TaskSpec.read("r", Stage.LOAD, "k");
        check("只读 ⇒ effectiveParallel=true", read.effectiveParallel());
        final TaskSpec serialRead = new TaskSpec("r", Stage.LOAD, AccessMode.READ_ONLY, false, "k", false);
        check("只读但声明不并行 ⇒ effectiveParallel=false", !serialRead.effectiveParallel());
    }

    /** 异常必须变成失败的 future，不能被吞。 */
    private static void failuresPropagate() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final AutoTaskDispatcher dispatcher = new AutoTaskDispatcher(pool, false);
        try {
            final CompletableFuture<String> f = dispatcher.dispatch(
                    TaskSpec.read("boom", Stage.PROBE, null), () -> {
                        throw new IllegalStateException("故意失败");
                    });
            boolean threw = false;
            try {
                f.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                threw = e.getCause() instanceof IllegalStateException;
            }
            check("异常变成失败的 future（cause 保留）", threw);
            check("失败计数被记录", dispatcher.failedCount() == 1L);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
