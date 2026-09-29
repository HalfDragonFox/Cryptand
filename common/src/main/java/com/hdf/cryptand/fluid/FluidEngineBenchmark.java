package com.hdf.cryptand.fluid;

/**
 * FluidEngine 阶段计时基准（纯 Java 零 MC）：
 *   ./gradlew :common:runFluidBench
 *
 * <p>目的：把「一步」拆成<b>构造</b>（扫描 / 邻接 / 遍历序）与<b>运行</b>（补源 / 塌落 / 重力 /
 * 压力场 / sweep / 输出），用真实数字给优化项排序 —— 静态推算（{@code fluid-engine-accel-eval}）
 * 只能给出区间，排不出先后。{@link FluidPhase} 默认关闭，只在这里打开。
 *
 * <p><b>JIT 纪律</b>：每个场景先跑 {@link #WARMUP} 步再计时，且计时取 3 轮里最快的一轮 ——
 * 第一版基准的教训：不预热时同一段构造代码在不同 n 上能差 6 倍，读出来的「热点」是
 * JIT 状态而不是算法。
 *
 * <p>两种片形：
 * <ul>
 *   <li><b>平面</b>（与 {@code FluidEngineSelfTest} 的 [bench] 同形）⇒ 与历史数字可直接对比；</li>
 *   <li><b>16³ 盒</b>（4096 片内格 + 六面一圈边界，生产片形；边界取可容纳的空气，
 *       与 {@code FluidRegionAssembly} 的取舍一致 —— 固体边界根本不进片）；</li>
 * </ul>
 * 每种片形测「运动中」与「静止」两个状态，因为两者的成本结构完全不同：
 * 运动的片扫得动、静止的片白跑一整趟。
 *
 * <p>⚠ 本基准沿用 SelfTest 的做法：<b>同一个 body 反复 step</b>（不写回变化集）——
 * 测的是「单步固定成本」，不是收敛步数。写回报表要另建（见报告「未测」一节）。
 */
public final class FluidEngineBenchmark {

    private static final FluidKind WATER = FluidKind.WATER;
    private static final FluidKind AIR = FluidKind.AIR;
    private static final FluidKind SOLID = FluidKind.SOLID;

    /** 计时前的预热步数（JIT 完全编译 + 分支 profile 稳定）。 */
    private static final int WARMUP = 800;
    /** 每轮计时步数。 */
    private static final int ITERS = 200;
    /** 计时轮数（取最快一轮，压掉 GC / 调度抖动）。 */
    private static final int ROUNDS = 3;

    private FluidEngineBenchmark() {
    }

    public static void main(final String[] args) {
        FluidPhase.enabled = true;
        System.out.println("[phase] FluidEngine 单步阶段计时（warmup " + WARMUP + " 步 + " + ROUNDS
                + " 轮 x " + ITERS + " 步，取最快轮；同一 body 反复算，单线程）");
        flat("平面 64  倒 8 单位（运动中）", 8, 8, true);
        flat("平面 64  静止水面（每格 1）", 8, 8, false);
        flat("平面 512 倒 8 单位（运动中）", 16, 32, true);
        flat("平面 512 静止水面（每格 1）", 16, 32, false);
        flat("平面 4096 倒 8 单位（运动中）", 64, 64, true);
        flat("平面 4096 静止水面（每格 1）", 64, 64, false);
        box("16^3 box 4096 顶部整层水（运动中）", true);
        box("16^3 box 4096 底部两层满水（静止）", false);
    }

    // ---------- 片形 ----------

    /** 平面片：y=5 一层（与 SelfTest.benchBody 同形），四周一圈固体边界。 */
    private static void flat(final String label, final int w, final int h, final boolean pour) {
        final ArrayBody body = new ArrayBody(WATER);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                final boolean wet = pour ? (x == 0 && z == 0) : true;
                body.cell(x, 5, z, wet ? WATER : AIR, wet ? (pour ? 8 : 1) : 0);
            }
        }
        for (int x = -1; x <= w; x++) {
            body.border(x, 5, -1, SOLID, 0);
            body.border(x, 5, h, SOLID, 0);
        }
        for (int z = 0; z < h; z++) {
            body.border(-1, 5, z, SOLID, 0);
            body.border(w, 5, z, SOLID, 0);
        }
        run(label, body);
    }

    /**
     * 16³ 盒片：4096 片内格（y=0 地形）+ 六面各 16×16 一圈边界格 = 生产片形。
     *
     * <p>运动中 = 顶部整层满水（整块塌落 + 摊平）；静止 = 底部两层满水（压力场一眼平衡）。
     * 边界取空气（生产里固体面格根本不进片）。
     */
    private static void box(final String label, final boolean pour) {
        final ArrayBody body = new ArrayBody(WATER);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (y == 0) {
                        body.cell(x, y, z, SOLID, 0);
                    } else if (pour) {
                        body.cell(x, y, z, y == 15 ? WATER : AIR, y == 15 ? 8 : 0);
                    } else {
                        body.cell(x, y, z, y <= 2 ? WATER : AIR, y <= 2 ? 8 : 0);
                    }
                }
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                body.border(x, y, -1, AIR, 0);
                body.border(x, y, 16, AIR, 0);
            }
            for (int z = 0; z < 16; z++) {
                body.border(-1, y, z, AIR, 0);
                body.border(16, y, z, AIR, 0);
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                body.border(x, -1, z, AIR, 0);
                body.border(x, 16, z, AIR, 0);
            }
        }
        run(label, body);
    }

    // ---------- 量 ----------

    private static void run(final String label, final ArrayBody body) {
        final FluidDelta d = new FluidDelta();
        for (int i = 0; i < WARMUP; i++) {
            FluidEngine.step(body, d);
        }
        double best = Double.MAX_VALUE;
        long bestMoved = 0;
        String bestReport = "";
        String bestShare = "";
        for (int round = 0; round < ROUNDS; round++) {
            FluidPhase.reset();
            long moved = 0;
            final long t0 = System.nanoTime();
            for (int i = 0; i < ITERS; i++) {
                moved += FluidEngine.step(body, d);
            }
            final long t1 = System.nanoTime();
            final double per = (t1 - t0) / 1000.0 / ITERS;
            if (per < best) {
                best = per;
                bestMoved = moved;
                bestReport = FluidPhase.report();
                bestShare = share();
            }
        }
        System.out.printf("%n[phase] %-32s n=%5d border=%5d  %8.1f us/step  moved/step=%7.2f  delta=%d%n",
                label, body.cellCount(), body.borderCount(), best, bestMoved / (double) ITERS, d.size());
        System.out.print(bestReport);
        System.out.println(bestShare);
    }

    private static String share() {
        long sum = 0L;
        for (int i = 0; i < FluidPhase.COUNT; i++) {
            sum += FluidPhase.nanos(i);
        }
        final StringBuilder sb = new StringBuilder("[phase] 占比: ");
        for (int i = 0; i < FluidPhase.COUNT; i++) {
            if (FluidPhase.nanos(i) == 0L) {
                continue;
            }
            sb.append(FluidPhase.NAMES[i]).append('=')
                    .append(Math.round(FluidPhase.nanos(i) * 1000.0 / sum) / 10.0).append("% ");
        }
        return sb.toString();
    }
}
