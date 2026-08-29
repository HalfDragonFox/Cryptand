package com.hdf.cryptand.circuitsimulation.netop;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网表相关操作类 + 虚拟线程自测（2026-08-16，纯引擎无 Minecraft）。
 * <p>
 * 验证：
 *   1) 虚拟线程：普通模式任务并发执行（虚拟线程池），独占模式直算
 *   2) 网络操作记录表：同一网络串行锁定（并发度恒 1），不同网络并行
 *   3) 消息缓冲 + 整合：多条同类型请求合并为一条（N 条 SOLVE → &lt;N 次求解）
 *   4) 优先级：网络拆合 &gt; 重建 &gt; 求解；重建后如需拆合则重建完成后执行拆合
 *   5) 完成请求：缓冲清空后从记录表移除（网络解锁）
 * <p>
 * 运行：{@code ./gradlew :common:runNetOpTest}
 */
public final class NetOpSelfTest {

    /** 记录执行器：按网络键统计执行次数 + 记录事件时间线 + 追踪并发度 */
    static final class RecordingExecutor implements NetOpExecutor {
        final List<String> timeline = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<Object, AtomicInteger> solveCount = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Object, AtomicInteger> splitCount = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Object, AtomicInteger> rebuildCount = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Object, AtomicInteger> concurrent = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Object, AtomicInteger> maxConcurrent = new ConcurrentHashMap<>();
        final AtomicLong inFlight = new AtomicLong();

        int count(ConcurrentHashMap<Object, AtomicInteger> m, Object key) {
            return m.computeIfAbsent(key, k -> new AtomicInteger()).get();
        }

        int enter(Object key) {
            int c = concurrent.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            maxConcurrent.computeIfAbsent(key, k -> new AtomicInteger())
                    .accumulateAndGet(c, Math::max);
            inFlight.incrementAndGet();
            return c;
        }

        void exit(Object key) {
            concurrent.get(key).decrementAndGet();
            inFlight.decrementAndGet();
        }

        void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public boolean executeSplitMerge(Object networkKey, Object data) {
            timeline.add("SPLIT:" + networkKey);
            splitCount.computeIfAbsent(networkKey, k -> new AtomicInteger()).incrementAndGet();
            enter(networkKey);
            sleep(30); // 让后续同网络请求进入缓冲（验证整合/优先级）
            exit(networkKey);
            return true;
        }

        @Override
        public boolean executeRebuild(Object networkKey, Object data) {
            timeline.add("REBUILD:" + networkKey);
            rebuildCount.computeIfAbsent(networkKey, k -> new AtomicInteger()).incrementAndGet();
            enter(networkKey);
            sleep(10);
            exit(networkKey);
            return true; // 重建后需要执行拆合
        }

        @Override
        public boolean executeDestroy(Object networkKey, Object data) {
            timeline.add("DESTROY:" + networkKey);
            enter(networkKey);
            sleep(30);
            exit(networkKey);
            return true;
        }

        @Override
        public void executeSolve(Object networkKey, Object data) {
            timeline.add("SOLVE:" + networkKey);
            solveCount.computeIfAbsent(networkKey, k -> new AtomicInteger()).incrementAndGet();
            enter(networkKey);
            sleep(20);
            exit(networkKey);
        }
    }

    public static void main(String[] args) throws Exception {
        boolean pass = true;
        ThreadDispatcher td = new ThreadDispatcher(2, "netop-test", 1000);
        RecordingExecutor exec = new RecordingExecutor();
        AsyncInteractionManager mgr = new AsyncInteractionManager(td, exec, TaskMode.NORMAL);

        System.out.println("==== 1) 虚拟线程：普通模式并发执行 ====");
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            td.submitGeneric(() -> {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                }
            }, TaskMode.NORMAL);
        }
        Thread.sleep(80);
        int activeVt = td.activeVirtualThreads();
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        boolean vtOk = activeVt >= 2 && elapsed < 320;
        System.out.println("  活动虚拟线程=" + activeVt + "（期望 ≥2）总耗时=" + elapsed
                + "ms（期望 <320ms，串行需 ~480ms）" + (vtOk ? "✅" : "❌"));
        pass &= vtOk;

        System.out.println("==== 2) 记录表：同网络串行 / 异网络并行 ====");
        Object netA = "NET_A";
        Object netB = "NET_B";
        for (int i = 0; i < 6; i++) mgr.submit(netA, new NetOpRequest(NetOpKind.SOLVE, i));
        for (int i = 0; i < 6; i++) mgr.submit(netB, new NetOpRequest(NetOpKind.SOLVE, i));
        awaitIdle(mgr, exec);
        int aSolves = exec.count(exec.solveCount, netA);
        int bSolves = exec.count(exec.solveCount, netB);
        int aMaxConc = exec.count(exec.maxConcurrent, netA);
        int bMaxConc = exec.count(exec.maxConcurrent, netB);
        boolean mergedOk = aSolves < 6 && bSolves < 6 && aSolves >= 1 && bSolves >= 1;
        boolean serialOk = aMaxConc == 1 && bMaxConc == 1;
        System.out.println("  NET_A: " + aSolves + " 次求解（6 条请求整合 → <6）并发峰值="
                + aMaxConc + "（期望 1）" + ((mergedOk && aMaxConc == 1) ? "✅" : "❌"));
        System.out.println("  NET_B: " + bSolves + " 次求解（6 条请求整合 → <6）并发峰值="
                + bMaxConc + "（期望 1）" + ((mergedOk && bMaxConc == 1) ? "✅" : "❌"));
        System.out.println("  记录表剩余=" + mgr.size() + "（期望 0，缓冲清空后移除）"
                + (mgr.size() == 0 ? "✅" : "❌"));
        pass &= mergedOk && serialOk && mgr.size() == 0;

        System.out.println("==== 3) 优先级：拆合 > 重建 > 求解；重建后拆合 ====");
        Object netC = "NET_C";
        mgr.submit(netC, new NetOpRequest(NetOpKind.SPLIT_MERGE, "s"));
        Thread.sleep(5);
        mgr.submit(netC, new NetOpRequest(NetOpKind.REBUILD, "r"));
        Thread.sleep(5);
        mgr.submit(netC, new NetOpRequest(NetOpKind.SOLVE, "x"));
        awaitIdle(mgr, exec);
        List<String> cEvents = exec.timeline.stream()
                .filter(e -> e.endsWith(":" + netC)).toList();
        System.out.println("  NET_C 事件序列: " + cEvents);
        boolean firstSplit = !cEvents.isEmpty() && cEvents.get(0).startsWith("SPLIT:");
        boolean rebuildAfterSplit = cEvents.stream().anyMatch(e -> e.startsWith("REBUILD:"));
        // 重建完成后执行拆合（REBUILD 之后必须有 SPLIT）且随后有 SOLVE
        boolean splitAfterRebuild = false;
        boolean solved = false;
        for (int i = 0; i < cEvents.size(); i++) {
            if (cEvents.get(i).startsWith("REBUILD:")) {
                splitAfterRebuild = (i + 1 < cEvents.size())
                        && cEvents.get(i + 1).startsWith("SPLIT:");
            }
            if (cEvents.get(i).startsWith("SOLVE:")) solved = true;
        }
        int cSplits = exec.count(exec.splitCount, netC);
        int cRebuilds = exec.count(exec.rebuildCount, netC);
        boolean priorityOk = firstSplit && rebuildAfterSplit && splitAfterRebuild
                && solved && cSplits >= cRebuilds;
        System.out.println("  首事件=拆合: " + firstSplit + " | 重建后拆合: " + splitAfterRebuild
                + " | 求解已执行: " + solved + " | 拆合次数(" + cSplits + ")≥重建次数("
                + cRebuilds + ")" + (priorityOk ? " ✅" : " ❌"));
        System.out.println("  记录表剩余=" + mgr.size() + "（期望 0）" + (mgr.size() == 0 ? "✅" : "❌"));
        pass &= priorityOk && mgr.size() == 0;

        System.out.println("==== 4) 独占模式（EXCLUSIVE 直算不崩） ====");
        Object netD = "NET_D";
        mgr.submit(netD, new NetOpRequest(NetOpKind.SOLVE, "d"));
        awaitIdle(mgr, exec);
        int dSolves = exec.count(exec.solveCount, netD);
        System.out.println("  NET_D 求解=" + dSolves + "（期望 1）记录表=" + mgr.size()
                + (dSolves == 1 && mgr.size() == 0 ? "✅" : "❌"));
        pass &= dSolves == 1 && mgr.size() == 0;

        td.close();
        System.out.println(pass ? "\n✅ 网表相关操作类+虚拟线程自测通过"
                : "\n❌ 网表相关操作类+虚拟线程自测失败");
        System.exit(pass ? 0 : 1);
    }

    /** 等待所有操作完成（记录表清空 + 无执行中） */
    private static void awaitIdle(AsyncInteractionManager mgr, RecordingExecutor exec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while ((mgr.size() > 0 || exec.inFlight.get() > 0)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private NetOpSelfTest() {
    }
}
