package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.circuitsimulation.solver.Solver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 CPU 引擎：线程池执行 RealMnaSolver / ComplexMnaSolver。
 * 任务的数据来自 NetworkSnapshot（纯数据，不触碰世界），线程安全。
 */
public class LocalComputeEngine implements ComputeEngine {
    private final ExecutorService pool;
    private final int threads;
    private final String name;

    public LocalComputeEngine(int threads, String name) {
        this.threads = Math.max(1, threads);
        this.name = name;
        AtomicInteger idx = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cryptand-engine-" + name + "-" + idx.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Override
    public ComputeEngineType type() { return ComputeEngineType.CPU; }

    @Override
    public ThreadQuality quality() { return threads > 1 ? ThreadQuality.MULTI_CORE : ThreadQuality.SINGLE_CORE; }

    @Override
    public boolean canExecute(ComputeTask task) {
        if (task.minQuality.level > quality().level) return false;
        // engineMask == 0 表示“不限引擎”；否则必须包含 CPU
        return task.engineMask == 0 || ComputeEngineType.CPU.matches(task.engineMask);
    }

    @Override
    public ComputeResult execute(ComputeTask task) {
        if (task.expired()) throw new IllegalStateException("task " + task.id + " expired before start");
        try {
            Future<ComputeResult> f = pool.submit(() -> doSolve(task));
            return f.get(Math.max(1, task.budgetNanos), TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            throw new RuntimeException("local solve failed for task " + task.id, e);
        }
    }

    private ComputeResult doSolve(ComputeTask task) {
        Network net = task.snapshot.toNetwork();
        Solver solver = com.hdf.cryptand.circuitsimulation.solver.Solvers.create(
                task.snapshot.solveMode(), net);
        SolveResult r = solver.solve(net);
        return new ComputeResult(task.id, r.voltages, r.complex, r.converged, r.iterations,
                r.solveNanos, r.mode, "CPU-local(" + name + ")");
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
