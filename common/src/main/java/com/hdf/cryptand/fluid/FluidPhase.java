package com.hdf.cryptand.fluid;

import java.util.Arrays;

/**
 * 阶段计时（<b>默认关闭</b>，只给离线基准用）。
 *
 * <p>真机与闸门路径上 {@link #enabled} 恒为 false ⇒ {@link #start()} 只读一个静态布尔后返回 0，
 * {@link #end} 直接返回，热循环里零 nanoTime、零写数组（分支可完美预测）。
 * 只有 {@code FluidEngineBenchmark}（:common:runFluidBench）把它打开。
 *
 * <p>用途：把「一步」拆成构造（扫描 / 邻接 / 排序）与运行（补源 / 塌落 / 重力 / 压力场 /
 * sweep / 输出）两段，用真实数字决定优化次序 —— 静态推算不足以排序。
 */
final class FluidPhase {

    static final int SOURCE = 0;
    static final int COLLAPSE = 1;
    static final int GRAVITY = 2;
    static final int PRESSURE = 3;
    static final int SWEEP_BFS = 4;
    static final int SWEEP_SCAN = 5;
    static final int FLUSH = 6;
    static final int SCAN = 7;
    static final int ADJACENCY = 8;
    static final int ORDER = 9;

    static final int COUNT = 10;

    static final String[] NAMES = {
            "source", "collapse", "gravity", "pressure", "sweep.bfs", "sweep.scan", "flush",
            "build.scan", "build.adjacency", "build.order",
    };

    /** 开启后才有 nanoTime 开销；默认关闭（真机 / 闸门零成本）。 */
    static boolean enabled;

    private static final long[] NANOS = new long[COUNT];
    private static final long[] CALLS = new long[COUNT];

    private FluidPhase() {
    }

    static void reset() {
        Arrays.fill(NANOS, 0L);
        Arrays.fill(CALLS, 0L);
    }

    static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    static void end(final int phase, final long t0) {
        if (enabled && t0 != 0L) {
            NANOS[phase] += System.nanoTime() - t0;
            CALLS[phase]++;
        }
    }

    static long nanos(final int phase) {
        return NANOS[phase];
    }

    static long calls(final int phase) {
        return CALLS[phase];
    }

    /** 一行相位表（总纳秒 / 调用次数 / 每次纳秒），交给基准打印。 */
    static String report() {
        final StringBuilder sb = new StringBuilder();
        long total = 0L;
        for (int i = 0; i < COUNT; i++) {
            total += NANOS[i];
        }
        sb.append(String.format("%-16s %12s %10s %12s%n", "phase", "total us", "calls", "us/call"));
        for (int i = 0; i < COUNT; i++) {
            final long calls = CALLS[i];
            sb.append(String.format("%-16s %12.1f %10d %12.2f%n", NAMES[i], NANOS[i] / 1000.0, calls,
                    calls == 0 ? 0.0 : (double) NANOS[i] / calls / 1000.0));
        }
        sb.append(String.format("%-16s %12.1f%n", "SUM", total / 1000.0));
        return sb.toString();
    }
}
