package com.hdf.cryptand.math;

/**
 * NativeMath 自测（2026-08-30）。验证统一底层接口的正确性：
 * 与纯 Java 回退两条路径结果一致（native 加载前后均可运行）。
 *
 * 运行方式（tests JVM，需要 DLL 已加载或纯 Java 模式）：
 *   - 纯 Java 模式：直接运行（isLoaded()=false 走回退路径）
 *   - native 模式：先 NativeSparseLoader.load()（neoforge 侧）
 *
 * 覆盖：dgemm/zgemm 矩阵乘法、daxpy/zaxpy、ddot/zdotc、dnrm2、
 *       solveDenseReal/solveDenseComplex、solveSparseComplex（CSC）。
 */
public final class NativeMathSelfTest {

    private NativeMathSelfTest() {}

    /**
     * 入口：./gradlew :common:runNativeMathTest（纯 Java 回退）
     * 或 java -cp ... com.hdf.cryptand.math.NativeMathSelfTest &lt;DLL路径&gt;
     * （传 DLL 路径 → System.load + setLoaded，验证 native 路径）。
     */
    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            try {
                System.load(args[0]);
                NativeMath.setLoaded(true);
                NativeMath.setSingleLoaded(true);
                System.out.println("[NativeMathSelfTest] DLL loaded: " + args[0]);
            } catch (Throwable t) {
                System.out.println("[NativeMathSelfTest] DLL load failed, pure java: " + t);
            }
        }
        boolean ok = run();
        if (!ok) System.exit(1);
    }

    /** 返回 true = 全部通过；false = 有失败（打印差异）。 */
    public static boolean run() {
        boolean ok = true;
        ok &= testGemmReal();
        ok &= testGemmComplex();
        ok &= testAxpy();
        ok &= testDot();
        ok &= testNrm2();
        ok &= testSolveDenseReal();
        ok &= testSolveDenseComplex();
        ok &= testSolveSparse();
        ok &= testJniGateConcurrency();
        System.out.println("[NativeMathSelfTest] " + (ok ? "ALL PASS" : "FAIL")
                + (NativeMath.isLoaded() ? " (native)" : " (pure java)"));
        return ok;
    }

    // ===== JNI 门闸并发语义（2026-08-30 用户：ReadWriteGate 治理 JNI 多线程）=====
    // 默认（jniConcurrent=false）→ ORDERED：多个线程进 native 严格串行（最多 1 并发）；
    // setJniConcurrent(true) → UNORDERED：允许并发（容量内）。
    private static boolean testJniGateConcurrency() {
        final int threads = 8;
        final int iterations = 50;
        java.util.concurrent.atomic.AtomicInteger concurrentMax = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();

        java.util.function.IntSupplier op = () -> {
            int c = running.incrementAndGet();
            concurrentMax.accumulateAndGet(c, Math::max);
            // 模拟 native 计算耗时——用足够窗口让并发可观测（sqrt 太快导致并发窗口 ≈ 0）
            double s = 0;
            for (int i = 0; i < 200_000; i++) s += Math.sqrt(i);
            // 额外忙等 ~1ms 制造并发重叠窗口（UNORDERED 下应观察到并发≥2）
            long start = System.nanoTime();
            while (System.nanoTime() - start < 1_000_000L) { /* busy wait */ }
            running.decrementAndGet();
            return 0;
        };

        // 1) 默认顺序独占：并发度必须 = 1（需先开启门闸——默认配置关闭时直接调用）
        NativeMath.setJniGateEnabled(true);
        NativeMath.setJniConcurrent(false);
        concurrentMax.set(0);
        runJniStress(threads, iterations, op);
        boolean orderedOk = concurrentMax.get() == 1;

        // 2) 乱序并发：并发度 ≥ 2（允许并行）
        NativeMath.setJniConcurrent(true);
        concurrentMax.set(0);
        runJniStress(threads, iterations, op);
        boolean unorderedOk = concurrentMax.get() >= 2;
        NativeMath.setJniConcurrent(false);   // 复位默认
        NativeMath.setJniGateEnabled(false);  // 复位默认（配置关闭）

        System.out.println("   [JniGate] ORDERED max_concurrent="
                + (orderedOk ? 1 : -1)
                + " UNORDERED max_concurrent=" + concurrentMax.get()
                + " -> " + (orderedOk && unorderedOk ? "PASS" : "FAIL"));
        return orderedOk && unorderedOk;
    }

    private static void runJniStress(int threads, int iterations,
                                     java.util.function.IntSupplier op) {
        java.util.List<Thread> ts = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < iterations; i++) {
                    try {
                        NativeMath.jniCallForTest(op);
                    } catch (Throwable ignored) {
                    }
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            try { th.join(); } catch (InterruptedException ignored) { }
        }
    }

    // C = A·B：[[1,2],[3,4]]·[[5,6],[7,8]] = [[19,22],[43,50]]
    private static boolean testGemmReal() {
        double[] a = {1, 2, 3, 4}, b = {5, 6, 7, 8}, c = new double[4];
        if (!NativeMath.dgemm(2, 2, 2, a, b, c, false, false)) return false;
        return near(c[0], 19) && near(c[1], 22) && near(c[2], 43) && near(c[3], 50);
    }

    // zgemm：A=[[1+i,0],[0,2-i]], B=[[1,0],[0,1]] → C=A
    private static boolean testGemmComplex() {
        double[] aRe = {1, 0, 0, 2}, aIm = {1, 0, 0, -1};
        double[] bRe = {1, 0, 0, 1}, bIm = {0, 0, 0, 0};
        double[] cRe = new double[4], cIm = new double[4];
        if (!NativeMath.zgemm(2, 2, 2, aRe, aIm, bRe, bIm, cRe, cIm, false, false)) return false;
        return near(cRe[0], 1) && near(cIm[0], 1) && near(cRe[3], 2) && near(cIm[3], -1)
                && near(cRe[1], 0) && near(cIm[1], 0);
    }

    private static boolean testAxpy() {
        // y = 2·x + y ; x=[1,2,3], y=[10,20,30] → [12,24,36]
        double[] x = {1, 2, 3}, y = {10, 20, 30};
        if (!NativeMath.daxpy(3, 2.0, x, y)) return false;
        boolean r = near(y[0], 12) && near(y[1], 24) && near(y[2], 36);
        // zaxpy: α=1+i, x=[1,0], y=[0,0] → y=[1,1]
        double[] xr = {1}, xi = {0}, yr = {0}, yi = {0};
        NativeMath.zaxpy(1, 1, 1, xr, xi, yr, yi);
        return r && near(yr[0], 1) && near(yi[0], 1);
    }

    private static boolean testDot() {
        double[] x = {1, 2, 3}, y = {4, 5, 6};
        if (!near(NativeMath.ddot(3, x, y), 32)) return false;
        // zdotc: <x,y> where x=[1+i], y=[1] → (1-i)(1)=1-i
        double[] xr = {1}, xi = {1}, yr = {1}, yi = {0};
        double[] r = NativeMath.zdotc(1, xr, xi, yr, yi);
        return near(r[0], 1) && near(r[1], -1);
    }

    private static boolean testNrm2() {
        return near(NativeMath.dnrm2(3, new double[]{3, 4, 0}), 5);
    }

    // 2x2 求解：[2,0;0,4]x=[2;8] → x=[1;2]
    private static boolean testSolveDenseReal() {
        double[] x = NativeMath.solveDenseReal(2, new double[]{2, 0, 0, 4}, new double[]{2, 8});
        if (x == null) return false;
        return near(x[0], 1) && near(x[1], 2);
    }

    // 2x2 复求解：[[1+i, 0],[0, 1]]x=[1+i;2] → x=[1;2]
    private static boolean testSolveDenseComplex() {
        var x = NativeMath.solveDenseComplex(2,
                new double[]{1, 0, 0, 1}, new double[]{1, 0, 0, 0},
                new double[]{1, 2}, new double[]{1, 0});
        if (x == null) return false;
        return near(x[0].re, 1) && near(x[0].im, 0) && near(x[1].re, 2) && near(x[1].im, 0);
    }

    // CSC 稀疏：A=[[2,0],[0,4]]（colPtr=[0,1,2]，rowIdx=[0,1]，re=[2,4]）
    // b=[2;8] → x=[1;2]
    private static boolean testSolveSparse() {
        var x = NativeMath.solveSparseComplex(2, 2,
                new int[]{0, 1, 2}, new int[]{0, 1},
                new double[]{2, 4}, new double[]{0, 0},
                new double[]{2, 8}, new double[]{0, 0});
        if (x == null) return false;
        return near(x[0].re, 1) && near(x[0].im, 0) && near(x[1].re, 2) && near(x[1].im, 0);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }
}
