package com.hdf.cryptand.dynamic.task;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * ===== 自动派发器（common 框架工具，2026-09-29）=====
 *
 * <p>用户要求：「外部只需要定义读写文件或者其他操作，然后设置是否多线程以及只读只写等操作，
 * **由后端自动实现**」。本类就是那个"后端自动"：读声明、挑线程、按资源键加锁、回主线程 —— 全自动。</p>
 *
 * <h3>自动规则（外部不需要知道线程模型）</h3>
 * <ol>
 *   <li>{@code mainThread=true} ⇒ 交给注入的主线程执行器（MC 侧 {@code mc.execute} / {@code server.execute}；
 *       common/测试里默认同线程直跑，<b>绝不 sleep 等主线程</b>）。</li>
 *   <li>{@code READ_ONLY} ⇒ 进后台池，**可并行**；同一资源键上与写者互斥（读锁）。</li>
 *   <li>{@code WRITE_ONLY}/{@code READ_WRITE} ⇒ 进后台池，同一资源键上**独占**（写锁）。
 *       {@link TaskSpec#effectiveParallel()} 保证"写"永远不会因为调用方写了 parallel=true 而并行。</li>
 *   <li>{@code resourceKey == null} ⇒ 不参与资源互斥，纯粹丢池子里跑。</li>
 * </ol>
 *
 * <p>⚠ 与既有 `DynamicScheduler` 的分工：那个按**阶段**（DISCOVER/PROBE/...）给策略，
 * 这个按**操作声明**给策略；MC 侧后端把两个执行器（后台池 + 主线程）注入进来即可。</p>
 *
 * <p>⚠ 这里**不做 sleep、不做忙等**：拿不到锁就在池里等（虚拟线程，便宜）。
 * 主线程只跑必须主线程的活，且永远不阻塞在后台任务上。</p>
 */
public final class AutoTaskDispatcher implements TaskDispatcher, AutoCloseable {

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ConcurrentHashMap<String, ReadWriteLock> resourceLocks = new ConcurrentHashMap<>();

    /** 主线程执行器：默认同线程直跑（common/测试安全默认；MC 侧注入真派发器）。 */
    private volatile Consumer<Runnable> mainExecutor = Runnable::run;

    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong mainThreadDispatched = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public AutoTaskDispatcher(ExecutorService executor, boolean ownsExecutor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为 null");
        }
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** 默认：虚拟线程池（每任务一条虚拟线程，阻塞等锁不心疼）。 */
    public static AutoTaskDispatcher virtualThreads() {
        return new AutoTaskDispatcher(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** 注入主线程执行器（MC 侧：`mc::execute` 或 `server::execute`）。 */
    public void setMainExecutor(Consumer<Runnable> executor) {
        if (executor == null) {
            throw new IllegalArgumentException("mainExecutor 不能为 null");
        }
        this.mainExecutor = executor;
    }

    @Override
    public <T> CompletableFuture<T> dispatch(TaskSpec spec, Operation<T> operation) {
        if (spec == null || operation == null) {
            throw new IllegalArgumentException("spec/operation 不能为 null");
        }
        dispatched.incrementAndGet();

        // 1) 必须回主线程：只把结果 future 交回去，不阻塞调用者
        if (spec.mainThread()) {
            mainThreadDispatched.incrementAndGet();
            final CompletableFuture<T> future = new CompletableFuture<>();
            mainExecutor.accept(() -> {
                try {
                    future.complete(operation.run());
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    future.completeExceptionally(t);
                }
            });
            return future;
        }

        // 2) 无资源键：直接进池（只读/写都不需要互斥）
        if (spec.resourceKey() == null) {
            return CompletableFuture.supplyAsync(() -> invoke(operation), executor);
        }

        // 3) 有资源键：按访问模式取读锁或写锁（只读共享、写独占）
        final ReadWriteLock rw = resourceLocks.computeIfAbsent(spec.resourceKey(),
                key -> new ReentrantReadWriteLock());
        final Lock lock = spec.access().shareable() ? rw.readLock() : rw.writeLock();
        return CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                return invoke(operation);
            } finally {
                lock.unlock();
            }
        }, executor);
    }

    private <T> T invoke(Operation<T> operation) {
        try {
            return operation.run();
        } catch (RuntimeException | Error e) {
            failed.incrementAndGet();
            throw e;
        } catch (Exception e) {
            failed.incrementAndGet();
            throw new RuntimeException(e);
        }
    }

    /** 派发统计（观测用；`/cryptand` 或日志可读）。 */
    public String stats() {
        return "任务派发 " + dispatched.get() + "（主线程 " + mainThreadDispatched.get()
                + "，失败 " + failed.get() + "，资源键 " + resourceLocks.size() + "）";
    }

    public long dispatchedCount() {
        return dispatched.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public long mainThreadCount() {
        return mainThreadDispatched.get();
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
