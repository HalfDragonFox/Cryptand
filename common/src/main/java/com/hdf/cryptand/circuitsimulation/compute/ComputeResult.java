package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/** 计算结果（与任务 id 关联）。 */
public final class ComputeResult {
    public final long taskId;
    public final double[] voltages;
    public final boolean converged;
    public final int iterations;
    public final long solveNanos;
    public final SolveMode mode;
    /** 仅 COMPLEX_AC 模式非空：完整相量（实部/虚部） */
    public final Complex[] phasors;
    /** 实际执行的引擎描述（如 "CPU-local" / "GPU-0" / "cluster-1"） */
    public final String engine;

    public ComputeResult(long taskId, double[] voltages, boolean converged, int iterations,
                         long solveNanos, SolveMode mode, String engine) {
        this(taskId, voltages, null, converged, iterations, solveNanos, mode, engine);
    }

    public ComputeResult(long taskId, double[] voltages, Complex[] phasors, boolean converged,
                         int iterations, long solveNanos, SolveMode mode, String engine) {
        this.taskId = taskId;
        this.voltages = voltages;
        this.phasors = phasors;
        this.converged = converged;
        this.iterations = iterations;
        this.solveNanos = solveNanos;
        this.mode = mode;
        this.engine = engine;
    }

    @Override
    public String toString() {
        return "ComputeResult{task=" + taskId + ", engine=" + engine + ", converged=" + converged
                + ", " + solveNanos / 1000 + "µs}";
    }
}
