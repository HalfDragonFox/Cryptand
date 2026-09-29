package com.hdf.cryptand.core.concurrent;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事务调度框架离线闸门（纯 Java 零 MC）。
 *
 * <p>用可控的 {@link ManualExecutor} 让「工作线程何时跑」变成确定性的，
 * 从而精确断言「先读后写」的相位隔离，而不是靠 sleep 撞运气。
 */
public final class TxnSelfTest {

    private static int checks;
    private static boolean pass = true;

    private static void check(final String name, final boolean ok) {
        checks++;
        if (!ok) {
            pass = false;
        }
        System.out.println("   " + (ok ? "[OK]  " : "[FAIL]") + " " + name);
    }

    /** 手动执行器：execute 只入队，由测试决定何时真正跑。 */
    static final class ManualExecutor implements Executor {
        private final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(final Runnable command) {
            queue.add(command);
        }

        int queued() {
            return queue.size();
        }

        int runAll() {
            int n = 0;
            for (Runnable r = queue.poll(); r != null; r = queue.poll()) {
                r.run();
                n++;
            }
            return n;
        }
    }

    public static void main(final String[] args) {
        ascendingValidation();
        poolSteadyStateAndOverflow();
        chunkLocksAllOrNothing();
        stagedReadThenWrite();
        sameChunkWriteMutualExclusion();
        mainBudgetSpreadsAcrossTicks();
        mainCycleBlocksNextReads();
        asyncIsNotBudgeted();
        dualBudgetsAreIndependent();
        mainCycleReadsBeforeWrites();

        System.out.println();
        System.out.println(pass
                ? "TxnSelfTest: ALL PASS (" + checks + " checks)"
                : "TxnSelfTest: FAILED (" + checks + " checks)");
        if (!pass) {
            System.exit(1);
        }
    }

    // ==================================================================

    private static void ascendingValidation() {
        System.out.println("1) 申明集合校验（严格升序、非空、不超上限）");
        final TxnScheduler s = newScheduler(new ManualExecutor(), new AtomicInteger(), new AtomicInteger());

        check("非升序被拒", rejects(s, new long[]{2, 1}, 2));
        check("重复键被拒（锁不可重入）", rejects(s, new long[]{1, 1}, 2));
        // 契约变更（水物理接入需要）：keyCount == 0 合法 = 不申明任何资源（不解锁的事务）
        check("空申明被接受（不解锁的事务无需申明）", !rejects(s, new long[0], 0));
        check("长度不足被拒", rejects(s, new long[]{1}, 2));
        check("超出上限被拒", rejects(s, new long[Txn.MAX_KEYS + 1], Txn.MAX_KEYS + 1));
        check("升序且唯一被接受", !rejects(s, new long[]{1, 5, 9}, 3));
    }

    private static boolean rejects(final TxnScheduler s, final long[] keys, final int count) {
        try {
            s.submit(keys, count, TxnKind.READ, TxnExec.ASYNC, 0);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void poolSteadyStateAndOverflow() {
        System.out.println("2) 池：稳态零分配 + 溢出退化");
        final TxnPool pool = new TxnPool(4);
        check("常驻槽构造时即分配", pool.capacity() == 4 && pool.pooledCount() == 0);

        final Set<Txn> first = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 4; i++) {
            first.add(pool.acquire());
        }
        check("全部借出后无溢出", pool.pooledCount() == 4 && pool.overflowCount() == 0);

        final Txn extra = pool.acquire();
        check("超容量 → 溢出分配（不进池）", pool.overflowCount() == 1 && !extra.pooled);

        for (Txn t : first) {
            pool.release(t);
        }
        pool.release(extra);
        check("溢出对象归还即交 GC，池不撑大", pool.overflowCount() == 0 && pool.capacity() == 4);

        final Set<Txn> again = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 4; i++) {
            again.add(pool.acquire());
        }
        check("再次借出的是同一批对象（稳态零分配）", again.equals(first));
        for (Txn t : again) {
            pool.release(t);
        }
        check("全部归还", pool.pooledCount() == 0 && pool.overflowCount() == 0);
    }

    private static void chunkLocksAllOrNothing() {
        System.out.println("3) 区块锁：全得或全不得（绝不部分持有）");
        final ChunkLocks locks = new ChunkLocks();
        final AtomicInteger[] pair = {locks.cell(1L), locks.cell(2L)};

        check("两把锁全得", ChunkLocks.tryLockAll(pair, 2, 1));
        check("同 owner 再拿必然失败（不可重入）", !ChunkLocks.tryLockAll(pair, 2, 1));
        ChunkLocks.releaseAll(pair, 2);
        check("释放后他人可全得", ChunkLocks.tryLockAll(pair, 2, 2));
        ChunkLocks.releaseAll(pair, 2);

        final AtomicInteger[] conflict = {locks.cell(10L), locks.cell(11L)};
        conflict[1].set(7);
        check("含已占单元 → 整批拒绝", !ChunkLocks.tryLockAll(conflict, 2, 3));
        check("被拒时已拿到的也释放了（关键：无部分持有 ⇒ 无死锁）", conflict[0].get() == 0);
        conflict[1].set(0);

        check("锁表按区块登记（无条带假冲突）", locks.trackedKeys() == 4);
    }

    private static void stagedReadThenWrite() {
        System.out.println("4) 相位隔离：异步读 → 独占写 → 主线程（先读后写）");
        final ManualExecutor exec = new ManualExecutor();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger mains = new AtomicInteger();
        final TxnPool pool = new TxnPool(16);
        final TxnScheduler s = new TxnScheduler(pool, exec,
                t -> reads.incrementAndGet(),
                t -> {
                    writes.incrementAndGet();
                    if (t.exec() == TxnExec.MAIN) {
                        mains.incrementAndGet();      // 只有主线程周期里跑的才算
                    }
                });

        s.submit(new long[]{1L}, 1, TxnKind.READ, TxnExec.ASYNC, 0);
        s.submit(new long[]{2L}, 1, TxnKind.WRITE, TxnExec.ASYNC, 0);
        s.submit(new long[]{3L}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);
        check("t0: 提交后一条都没跑（等 tick 整理）", reads.get() == 0 && writes.get() == 0 && mains.get() == 0);

        s.tick();
        check("t1: 只派发读，写与主线程都不动", exec.queued() == 1 && writes.get() == 0 && mains.get() == 0);
        check("t1: pendingReads = 1，阶段 = 并行化", s.pendingReads() == 1 && s.stage() == Stage.PARALLELIZING);

        s.tick();
        check("t2: 读未清零 → 整 tick 空转（绝不提前写）", exec.queued() == 1 && writes.get() == 0 && mains.get() == 0);

        exec.runAll();
        check("t2b: 读完成，屏障归零", reads.get() == 1 && s.pendingReads() == 0);

        s.tick();
        check("t3: 阶段 = 独占，异步写已派发", s.stage() == Stage.EXCLUSIVE && exec.queued() == 1);
        check("t3: 异步写未清零 → 主线程周期仍未开始", mains.get() == 0 && s.pendingWrites() == 1);

        exec.runAll();
        check("t3b: 写完成，屏障归零", writes.get() == 1 && s.pendingWrites() == 0);

        s.tick();
        check("t4: 主线程周期执行", mains.get() == 1);
        check("t4: 回到并行化，池与待办清空",
                s.stage() == Stage.PARALLELIZING && s.backlog() == 0 && pool.pooledCount() == 0 && pool.overflowCount() == 0);
    }

    private static void sameChunkWriteMutualExclusion() {
        System.out.println("5) 同区块写互斥 + 单次性（抢不到即放弃，不排队不阻塞）");
        final ManualExecutor exec = new ManualExecutor();
        final AtomicInteger writes = new AtomicInteger();
        final TxnPool pool = new TxnPool(8);
        final TxnScheduler s = new TxnScheduler(pool, exec, t -> {
        }, t -> writes.incrementAndGet());

        s.submit(new long[]{7L}, 1, TxnKind.WRITE, TxnExec.ASYNC, 0);
        s.submit(new long[]{7L}, 1, TxnKind.WRITE, TxnExec.ASYNC, 0);
        s.tick();

        check("同区块同一时刻只派发一条", exec.queued() == 1);
        check("另一条本轮放弃并计数", s.deferredCount() == 1);
        check("放弃的那条已归还池", pool.pooledCount() == 1 && pool.overflowCount() == 0);

        exec.runAll();
        check("被派发的那条执行完成", writes.get() == 1);
        check("执行后锁已释放、事务已归还",
                s.pendingWrites() == 0 && pool.pooledCount() == 0 && s.locks().cell(7L).get() == 0);
    }

    private static void mainBudgetSpreadsAcrossTicks() {
        System.out.println("6) 主线程每 tick 上限：超限分 tick 消化（上限只作用于主线程周期）");
        final ManualExecutor exec = new ManualExecutor();
        final AtomicInteger mains = new AtomicInteger();
        final TxnPool pool = new TxnPool(16);
        final TxnScheduler s = new TxnScheduler(pool, exec, t -> {
        }, t -> {
            if (t.exec() == TxnExec.MAIN) {
                mains.incrementAndGet();
            }
        });
        s.setMainBudget(2);

        for (int i = 0; i < 5; i++) {
            s.submit(new long[]{100L + i}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);
        }

        s.tick();
        check("t1: 只执行 budget = 2 条", mains.get() == 2);
        check("t1: 阶段停在主线程周期（本轮没结束）", s.stage() == Stage.MAIN);
        check("t1: 剩余 3 条待办", s.mainBacklog() == 3);

        s.tick();
        check("t2: 再消化 2 条", mains.get() == 4 && s.mainBacklog() == 1);

        s.tick();
        check("t3: 最后 1 条", mains.get() == 5);
        check("t3: 周期结束，回到并行化", s.stage() == Stage.PARALLELIZING);
        check("t3: 待办与池清空", s.mainBacklog() == 0 && pool.pooledCount() == 0);
    }

    private static void mainCycleBlocksNextReads() {
        System.out.println("7) 主线程周期未清空 → 绝不提前派发下一轮读（先读后写的另一半）");
        final ManualExecutor exec = new ManualExecutor();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger mains = new AtomicInteger();
        final TxnPool pool = new TxnPool(16);
        final TxnScheduler s = new TxnScheduler(pool, exec,
                t -> reads.incrementAndGet(),
                t -> {
                    if (t.exec() == TxnExec.MAIN) {
                        mains.incrementAndGet();
                    }
                });
        s.setMainBudget(1);

        s.submit(new long[]{1L}, 1, TxnKind.READ, TxnExec.ASYNC, 0);
        s.submit(new long[]{2L}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);
        s.submit(new long[]{3L}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);

        s.tick();
        check("t1: 先派发读", s.pendingReads() == 1 && mains.get() == 0);
        exec.runAll();
        check("t1b: 读完成", reads.get() == 1 && s.pendingReads() == 0);

        s.tick();
        check("t2: 主线程周期跑 1 条后停住", mains.get() == 1 && s.stage() == Stage.MAIN);

        s.submit(new long[]{9L}, 1, TxnKind.READ, TxnExec.ASYNC, 0);
        s.tick();
        check("t3: 主线程周期未清空 → 新读仍不派发", exec.queued() == 0 && s.pendingReads() == 0);
        check("t3: 但主线程继续消化", mains.get() == 2 && s.mainBacklog() == 0);

        s.tick();
        check("t4: 周期清空后才派发下一轮读", exec.queued() == 1 && s.pendingReads() == 1);
        exec.runAll();
        check("t4b: 全部结束，池清空", reads.get() == 2 && pool.pooledCount() == 0);
    }

    private static void asyncIsNotBudgeted() {
        System.out.println("8) 异步不受主线程预算约束（要快就走异步）");
        final ManualExecutor exec = new ManualExecutor();
        final TxnPool pool = new TxnPool(16);
        final TxnScheduler s = new TxnScheduler(pool, exec, t -> {
        }, t -> {
        });
        s.setMainBudget(1);

        for (int i = 0; i < 5; i++) {
            s.submit(new long[]{200L + i}, 1, TxnKind.READ, TxnExec.ASYNC, 0);
        }
        s.tick();
        check("budget = 1，但 5 条异步读一次性全派发", exec.queued() == 5 && s.pendingReads() == 5);
        check("异步没有待办积压", s.mainBacklog() == 0);

        exec.runAll();
        check("异步全部完成，池清空", s.pendingReads() == 0 && pool.pooledCount() == 0);
    }

    private static void dualBudgetsAreIndependent() {
        System.out.println("9) 双预算相互独立：异步默认屏蔽（实时），开启后按批派发");
        final ManualExecutor live = new ManualExecutor();
        final TxnScheduler a = new TxnScheduler(new TxnPool(16), live, t -> {
        }, t -> {
        });
        check("异步预算默认屏蔽（UNLIMITED）", a.asyncBudget() == TxnScheduler.UNLIMITED);
        for (int i = 0; i < 5; i++) {
            a.submit(new long[]{300L + i}, 1, TxnKind.READ, TxnExec.ASYNC, 0);
        }
        a.tick();
        check("屏蔽时 5 条一次全派发（实时）", live.queued() == 5 && a.pendingReads() == 5);
        live.runAll();

        final ManualExecutor batch = new ManualExecutor();
        final TxnPool pool = new TxnPool(16);
        final TxnScheduler s = new TxnScheduler(pool, batch, t -> {
        }, t -> {
        });
        s.setAsyncBudget(2);
        check("改异步上限不动主线程上限",
                s.asyncBudget() == 2 && s.mainBudget() == TxnScheduler.DEFAULT_MAIN_BUDGET);
        s.setMainBudget(7);
        check("改主线程上限不动异步上限", s.mainBudget() == 7 && s.asyncBudget() == 2);

        for (int i = 0; i < 5; i++) {
            s.submit(new long[]{400L + i}, 1, TxnKind.READ, TxnExec.ASYNC, 0);
        }
        s.tick();
        check("开启后每 tick 只派发 2 条", batch.queued() == 2 && s.pendingReads() == 2);
        check("剩余 3 条留在待办", s.backlog() == 3);
        batch.runAll();

        s.tick();
        check("下一 tick 再派发 2 条", batch.queued() == 2 && s.pendingReads() == 2);
        batch.runAll();

        s.tick();
        check("最后 1 条", batch.queued() == 1 && s.pendingReads() == 1);
        batch.runAll();

        s.tick();
        check("全部清空，池归还", s.backlog() == 0 && pool.pooledCount() == 0);

        boolean rejected = false;
        try {
            s.setAsyncBudget(-1);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("非法异步预算被拒", rejected);
    }

    private static void mainCycleReadsBeforeWrites() {
        System.out.println("10) 主线程周期内同样先读后写（读全部落地后写才开始）");
        final java.util.List<String> order = new java.util.ArrayList<>();
        final TxnPool pool = new TxnPool(16);
        final TxnScheduler s = new TxnScheduler(pool, new ManualExecutor(),
                t -> order.add("R"), t -> order.add("W"));

        s.submit(new long[]{1L}, 1, TxnKind.READ, TxnExec.MAIN, 0);
        s.submit(new long[]{2L}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);
        s.submit(new long[]{3L}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);
        s.tick();
        check("同一批里读先于写", order.equals(java.util.List.of("R", "W", "W")));
        check("池归还", pool.pooledCount() == 0);

        order.clear();
        final TxnPool pool2 = new TxnPool(16);
        final TxnScheduler s2 = new TxnScheduler(pool2, new ManualExecutor(),
                t -> order.add("R"), t -> order.add("W"));
        s2.setMainBudget(1);
        s2.submit(new long[]{11L}, 1, TxnKind.READ, TxnExec.MAIN, 0);
        s2.submit(new long[]{12L}, 1, TxnKind.READ, TxnExec.MAIN, 0);
        s2.submit(new long[]{13L}, 1, TxnKind.READ, TxnExec.MAIN, 0);
        s2.submit(new long[]{14L}, 1, TxnKind.WRITE, TxnExec.MAIN, 0);

        s2.tick();
        check("budget=1：本 tick 只跑 1 条读，且不开始写", order.equals(java.util.List.of("R")));
        s2.tick();
        check("继续读，仍不写", order.equals(java.util.List.of("R", "R")));
        s2.tick();
        check("读全部落地后才轮到写", order.equals(java.util.List.of("R", "R", "R", "W")));
        check("周期结束", s2.stage() == Stage.PARALLELIZING && pool2.pooledCount() == 0);
    }

    private static TxnScheduler newScheduler(final Executor exec,
                                             final AtomicInteger reads, final AtomicInteger writes) {
        return new TxnScheduler(new TxnPool(8), exec, t -> reads.incrementAndGet(), t -> writes.incrementAndGet());
    }
}
