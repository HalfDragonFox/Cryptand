package com.hdf.cryptand.core.concurrent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 事务调度器：双缓冲 + 三态周期 + 池化 + <b>双预算</b>。
 *
 * <p>周期（用户 2026-09-28 定案）：
 * <pre>
 * 【异步周期】 PARALLELIZING：派发 ASYNC+READ 到工作线程并行跑，等 pendingReads 归零
 *              EXCLUSIVE   ：派发 ASYNC+WRITE，等 pendingWrites 归零（同区块由 ChunkLocks 互斥）
 * 【主线程周期】MAIN        ：主线程执行 MAIN 事务，随后派发下一轮读
 * </pre>
 * 每 tick 只推进一个阶段 —— 阶段之间<b>绝不重叠</b>，这就是「先读后写」的全部保证：
 * 读的时候没有写在跑，写的时候没有在跑读。所以读不需要拿锁也读不到中间态。
 *
 * <p><b>双预算</b>（两者相互独立）：
 * <ul>
 *   <li>{@link #setMainBudget}：主线程是慢路径，每 tick 至多这么多条，超出的跨 tick 消化。
 *       主线程周期一旦开始且未清空，就<b>不会再派发下一轮读</b>。</li>
 *   <li>{@link #setAsyncBudget}：默认 {@link #UNLIMITED}（<b>屏蔽</b>）—— 异步是快路径，
 *       想快速拿到信息就不该被限；需要时再开启按批派发。</li>
 * </ul>
 *
 * <p>双缓冲：{@link #submit} 往 {@link #fillSlots} 写入（受 {@code submitLock} 保护，
 * 保证槽位写入对 tick 的读取有 happens-before），tick 把它换到 {@link #execSlots}。
 * 执行期间接收侧天然空闲，不必为「正在执行的列表不能被写」再加一层锁。
 * 超出常驻容量的提交走 {@link #overflow} 链（普通分配，执行完由 GC 回收），池不被撑大。
 *
 * <p>线程模型：{@link #submit} 可从任意线程调用；{@link #tick} <b>只能由主线程</b>调用。
 */
public final class TxnScheduler {

    /** 异步预算的「屏蔽」值：不限制，实时全量派发。 */
    public static final int UNLIMITED = 0;

    /** 主线程周期每 tick 的默认上限。 */
    public static final int DEFAULT_MAIN_BUDGET = 64;

    private final TxnPool pool;
    private final ChunkLocks locks;
    private final Executor executor;
    private final Consumer<Txn> readBody;
    private final Consumer<Txn> writeBody;

    // ---- 双缓冲接收侧 ----
    private final Object submitLock = new Object();
    private Txn[] fillSlots;
    private Txn[] execSlots;
    private int fillCursor;
    private int execCount;
    private final ConcurrentLinkedQueue<Txn> overflow = new ConcurrentLinkedQueue<>();
    /** 桶满被丢弃的事务数（背压计数；以前这里抛异常 ⇒ 调度器永久卡死，见 {@link #dropOverflow}）。 */
    private int droppedOverflow;

    // ---- 执行侧三个待办桶（定长，零分配；跨 tick 保留）----
    private final Txn[] asyncReadBuf;
    private final Txn[] asyncWriteBuf;
    private final Txn[] mainReadBuf;
    private final Txn[] mainWriteBuf;
    private int asyncReadCount;
    private int asyncWriteCount;
    private int mainReadCount;
    private int mainWriteCount;

    // ---- 阶段同步 ----
    private final AtomicInteger pendingReads = new AtomicInteger();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicInteger nextOwner = new AtomicInteger(1);
    private final AtomicInteger deferred = new AtomicInteger();

    private volatile Stage stage = Stage.PARALLELIZING;
    private volatile int mainBudget = DEFAULT_MAIN_BUDGET;
    private volatile int asyncBudget = UNLIMITED;

    public TxnScheduler(final TxnPool pool, final Executor executor,
                        final Consumer<Txn> readBody, final Consumer<Txn> writeBody) {
        this.pool = pool;
        this.locks = new ChunkLocks();
        this.executor = executor;
        this.readBody = readBody;
        this.writeBody = writeBody;
        final int cap = pool.capacity();
        this.fillSlots = new Txn[cap];
        this.execSlots = new Txn[cap];
        this.asyncReadBuf = new Txn[cap];
        this.asyncWriteBuf = new Txn[cap];
        this.mainReadBuf = new Txn[cap];
        this.mainWriteBuf = new Txn[cap];
    }

    // ==================================================================
    // 提交（任意线程）
    // ==================================================================

    /**
     * 提交一次单次性申请。
     *
     * @param chunkKeys 申明的区块键，<b>严格升序</b>；{@code keyCount == 0} 表示不申请任何资源
     * @param keyCount  有效长度
     * @param payload   应用者自定义载荷
     */
    public void submit(final long[] chunkKeys, final int keyCount,
                       final TxnKind kind, final TxnExec exec, final int payload) {
        // keyCount == 0 合法：不解锁的事务（例如只读、或主线程独占写）无需申明任何资源
        if (keyCount < 0 || keyCount > Txn.MAX_KEYS) {
            throw new IllegalArgumentException("keyCount must be in [0, " + Txn.MAX_KEYS + "], got " + keyCount);
        }
        if (chunkKeys.length < keyCount) {
            throw new IllegalArgumentException("chunkKeys length " + chunkKeys.length + " < keyCount " + keyCount);
        }
        for (int i = 1; i < keyCount; i++) {
            if (chunkKeys[i - 1] >= chunkKeys[i]) {
                throw new IllegalArgumentException(
                        "chunkKeys must be strictly ascending; index " + i + ": " + chunkKeys[i - 1] + " >= " + chunkKeys[i]);
            }
        }

        final Txn txn = pool.acquire();
        txn.fill(chunkKeys, keyCount, kind, exec, payload);
        if (kind == TxnKind.WRITE) {
            txn.resolveCells(locks);      // 提交时解析锁单元，执行期不再碰锁表
        }

        synchronized (submitLock) {
            if (fillCursor < fillSlots.length) {
                fillSlots[fillCursor++] = txn;
            } else {
                overflow.add(txn);        // 溢出：不进池也不进数组
            }
        }
    }

    // ==================================================================
    // 周期推进（仅主线程）
    // ==================================================================

    /**
     * 推进一个 tick。返回本 tick 主线程实际执行的条数。
     *
     * <p>每 tick 只推进一个阶段；需要等待的阶段（读/写未完成）直接返回 0。
     */
    public int tick() {
        collect();

        // 主线程周期「已经开始且没跑完」→ 本 tick 只继续它，绝不派发下一轮读
        // （否则读会看到「一半落地」的世界，先读后写的相位隔离立刻失效）。
        // 必须带上 stage == MAIN 这个前提，否则一开局就会抢在读前面跑主线程事务，
        // 读永远拿不到派发机会。
        if (stage == Stage.MAIN && mainPending() > 0) {
            final int n = runMainTxns();
            if (mainPending() > 0) {
                return n;                 // 本轮主线程周期跨 tick 继续
            }
            stage = Stage.PARALLELIZING;
            return n;
        }

        // 并行化：先把待办读整批派发出去，然后等它跑完。顺序不能颠倒。
        if (asyncReadCount > 0 && pendingReads.get() == 0) {
            dispatchAsyncReads();
            stage = Stage.PARALLELIZING;
            return 0;
        }
        if (pendingReads.get() > 0) {
            stage = Stage.PARALLELIZING;
            return 0;
        }

        // 独占：派发异步写（同区块由 ChunkLocks 互斥），等它跑完。
        if (asyncWriteCount > 0 && pendingWrites.get() == 0) {
            dispatchAsyncWrites();
            stage = Stage.EXCLUSIVE;
            return 0;
        }
        if (pendingWrites.get() > 0) {
            stage = Stage.EXCLUSIVE;
            return 0;
        }

        // 主线程周期：读写都已静止，此时主线程独占落地最安全。
        stage = Stage.MAIN;
        final int n = runMainTxns();
        if (mainPending() > 0) {
            return n;                     // 超出本 tick 预算，剩下的下一 tick 继续
        }
        stage = Stage.PARALLELIZING;      // 本轮结束，下一轮从并行化重新开始
        return n;
    }

    /** 交换双缓冲并把新提交的按 (exec, kind) 分流进三个待办桶。 */
    private void collect() {
        synchronized (submitLock) {
            execCount = fillCursor;
            fillCursor = 0;
            final Txn[] tmp = execSlots;
            execSlots = fillSlots;
            fillSlots = tmp;
        }
        for (int i = 0; i < execCount; i++) {
            final Txn t = execSlots[i];
            execSlots[i] = null;
            if (t != null) {
                route(t);
            }
        }
        execCount = 0;
        for (Txn t = overflow.poll(); t != null; t = overflow.poll()) {
            route(t);
        }
    }

    /**
     * 桶满时的背压：**丢弃并计数，绝不抛异常**。
     *
     * <p>★ 这里以前抛 {@code IllegalStateException}。后果是致命的：异常从 {@code collect()} 里抛出去，
     * 会把本 tick 的 {@code tick()} 整个打断，而 {@code mainReadCount} 只在 {@code runMainTxns()}
     * 里清零 —— 于是桶永远满、每 tick 都抛，**调度器永久卡死**。
     * （实机症状：日志刷屏 "main-read backlog overflow (64)"，水完全静止。）
     * 丢弃是安全的：应用层按「有没有活要干」重新提交，下一 tick 自然补上。
     */
    private void dropOverflow(final Txn t) {
        droppedOverflow++;
        pool.release(t);
    }

    /** 被背压丢弃的事务总数（探针用）。 */
    public int droppedOverflow() {
        return droppedOverflow;
    }

    private void route(final Txn t) {
        if (t.exec() == TxnExec.MAIN) {
            // 主线程周期也分读/写两桶：读全部落地后写才开始（与异步周期同一条相位规则）
            if (t.kind() == TxnKind.READ) {
                if (mainReadCount >= mainReadBuf.length) {
                    dropOverflow(t);
                    return;
                }
                mainReadBuf[mainReadCount++] = t;
            } else {
                if (mainWriteCount >= mainWriteBuf.length) {
                    dropOverflow(t);
                    return;
                }
                mainWriteBuf[mainWriteCount++] = t;
            }
            return;
        }
        if (t.kind() == TxnKind.READ) {
            if (asyncReadCount >= asyncReadBuf.length) {
                dropOverflow(t);
                return;
            }
            asyncReadBuf[asyncReadCount++] = t;
        } else {
            if (asyncWriteCount >= asyncWriteBuf.length) {
                dropOverflow(t);
                return;
            }
            asyncWriteBuf[asyncWriteCount++] = t;
        }
    }

    // ---- 主线程周期 ----

    /**
     * 执行主线程事务，<b>每 tick 至多 {@link #mainBudget} 条</b>，超出的留给下一 tick。
     *
     * <p>主线程永远是慢路径，一次做完会把 tick 顶爆；限量 + 跨 tick 消化才能保证单 tick 工作量有界。
     */
    private int runMainTxns() {
        final int n = runMainReads();
        if (mainReadCount > 0) {
            return n;                     // 读没跑完绝不开始写（相位规则）
        }
        return n + runMainWrites();
    }

    private int runMainReads() {
        final int limit = Math.min(mainReadCount, mainBudget);
        int n = 0;
        for (int i = 0; i < limit; i++) {
            final Txn t = mainReadBuf[i];
            if (t == null) {
                continue;
            }
            try {
                execute(t, readBody);
            } finally {
                // ★ 无论 body 抛不抛异常，事务都必须归还并让桶指针能推进：
                //   少了它，主线程周期一旦抛一次就「事务泄漏 + 桶永不消账」，静默复现 P0 卡死。
                pool.release(t);
            }
            n++;
        }
        mainReadCount = compact(mainReadBuf, limit, mainReadCount);
        return n;
    }

    private int runMainWrites() {
        final int limit = Math.min(mainWriteCount, mainBudget);
        int n = 0;
        for (int i = 0; i < limit; i++) {
            final Txn t = mainWriteBuf[i];
            if (t == null) {
                continue;
            }
            try {
                execute(t, writeBody);
            } finally {
                pool.release(t);        // ★ 同 runMainReads：异常也不能漏归还
            }
            n++;
        }
        mainWriteCount = compact(mainWriteBuf, limit, mainWriteCount);
        return n;
    }

    /** 同步执行一条事务（主线程）：需要加锁的先全得或全不得，抢不到就本轮放弃。 */
    private void execute(final Txn t, final Consumer<Txn> body) {
        final int cells = t.cellCount();
        if (cells == 0) {
            body.accept(t);
            return;
        }
        final int owner = nextOwner.getAndIncrement();
        if (!ChunkLocks.tryLockAll(t.cells(), cells, owner)) {
            deferred.incrementAndGet();
            return;
        }
        try {
            body.accept(t);
        } finally {
            ChunkLocks.releaseAll(t.cells(), cells);
        }
    }

    // ---- 异步周期 ----

    private void dispatchAsyncReads() {
        final int n = batchSize(asyncReadCount, asyncBudget);
        if (n <= 0) {
            return;
        }
        pendingReads.set(n);
        for (int i = 0; i < n; i++) {
            final Txn t = asyncReadBuf[i];
            executor.execute(() -> {
                try {
                    readBody.accept(t);
                } finally {
                    pendingReads.decrementAndGet();
                    pool.release(t);
                }
            });
        }
        asyncReadCount = compact(asyncReadBuf, n, asyncReadCount);
    }

    private void dispatchAsyncWrites() {
        final int total = asyncWriteCount;
        final int limit = batchSize(total, asyncBudget);

        // 第一趟：预算内把能拿全锁的压缩到数组前段
        // （单次性：拿不到就本轮放弃，不排队、不阻塞）
        int m = 0;
        for (int i = 0; i < limit; i++) {
            final Txn t = asyncWriteBuf[i];
            final int cells = t.cellCount();
            if (cells > 0) {
                final int owner = nextOwner.getAndIncrement();
                if (!ChunkLocks.tryLockAll(t.cells(), cells, owner)) {
                    asyncWriteBuf[i] = null;
                    deferred.incrementAndGet();
                    pool.release(t);
                    continue;
                }
            }
            asyncWriteBuf[m++] = t;
        }

        // 先发布计数再派发（否则工作线程可能先把计数减成负数），派发时顺带清槽。
        pendingWrites.set(m);
        for (int i = 0; i < m; i++) {
            final Txn t = asyncWriteBuf[i];
            asyncWriteBuf[i] = null;
            executor.execute(() -> {
                try {
                    writeBody.accept(t);
                } finally {
                    final int cells = t.cellCount();
                    if (cells > 0) {
                        ChunkLocks.releaseAll(t.cells(), cells);
                    }
                    pendingWrites.decrementAndGet();
                    pool.release(t);
                }
            });
        }

        // 压缩必须放在「已派发的移出之后」：超预算的部分搬到前段，留待下一 tick。
        // 若在派发前压缩，会把 [0, m) 已派发的事务覆盖掉，下一 tick 读到 null 抛 NPE
        // —— 第一版就是这样挂的（tick 4 崩在 dispatchAsyncWrites）。
        final int remaining = total - limit;
        if (remaining > 0) {
            System.arraycopy(asyncWriteBuf, limit, asyncWriteBuf, 0, remaining);   // 重叠安全
        }
        for (int i = remaining; i < total; i++) {
            asyncWriteBuf[i] = null;
        }
        asyncWriteCount = remaining;
    }

    /** 把 [from, count) 段压到数组前段并清尾部引用，返回新数量。 */
    private static int compact(final Txn[] buf, final int from, final int count) {
        final int remaining = count - from;
        if (remaining <= 0) {
            for (int i = 0; i < count; i++) {
                buf[i] = null;
            }
            return 0;
        }
        System.arraycopy(buf, from, buf, 0, remaining);
        for (int i = remaining; i < count; i++) {
            buf[i] = null;
        }
        return remaining;
    }

    /** 本 tick 实际可派发/执行的条数。 */
    private static int batchSize(final int available, final int budget) {
        return budget == UNLIMITED ? available : Math.min(available, budget);
    }

    // ==================================================================
    // 预算（两项相互独立）
    // ==================================================================

    /** 主线程周期每 tick 的条数上限（运行时可变）。 */
    public void setMainBudget(final int budget) {
        if (budget < 1) {
            throw new IllegalArgumentException("mainBudget must be >= 1, got " + budget);
        }
        this.mainBudget = budget;
    }

    public int mainBudget() {
        return mainBudget;
    }

    /**
     * 异步周期每 tick 的条数上限（运行时可变）。
     *
     * @param budget {@link #UNLIMITED} 表示屏蔽限制（默认），或 &gt;= 1 的批大小
     */
    public void setAsyncBudget(final int budget) {
        if (budget != UNLIMITED && budget < 1) {
            throw new IllegalArgumentException("asyncBudget must be UNLIMITED(0) or >= 1, got " + budget);
        }
        this.asyncBudget = budget;
    }

    public int asyncBudget() {
        return asyncBudget;
    }

    // ==================================================================
    // 探针
    // ==================================================================

    public Stage stage() {
        return stage;
    }

    public TxnPool pool() {
        return pool;
    }

    public ChunkLocks locks() {
        return locks;
    }

    /** 未完成的异步读数量。 */
    public int pendingReads() {
        return pendingReads.get();
    }

    /** 未完成的异步写数量。 */
    public int pendingWrites() {
        return pendingWrites.get();
    }

    /** 因抢不到区块锁而被本轮放弃的事务累计数（单次性：放弃即丢弃，由应用者下轮重新申请）。 */
    public int deferredCount() {
        return deferred.get();
    }

    /** 主线程周期尚未消化的条数（读 + 写）。 */
    public int mainBacklog() {
        return mainPending();
    }

    private int mainPending() {
        return mainReadCount + mainWriteCount;
    }

    /** 待办桶里积压的条数（读 + 写 + 主线程），探针用。 */
    public int backlog() {
        return asyncReadCount + asyncWriteCount + mainPending();
    }
}
