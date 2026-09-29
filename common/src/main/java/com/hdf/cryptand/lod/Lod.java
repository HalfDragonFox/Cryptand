package com.hdf.cryptand.lod;

/**
 * ===== LOD 傻瓜门面（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户定案：「lod **效果最好核心自己实现**，外部调用使用**傻瓜式使用**」。</p>
 *
 * <p>所以"怎么做 LOD"全部锁在核心（本类 + {@link LodStairs} + {@link Lod2D}）里：</p>
 * <ul>
 *   <li>距离 ⇒ 步长：查阶梯表 ✓ 档位之间按距离**插值**（平滑开时）✓ 取整 ✓</li>
 *   <li>抽稀用什么算法：平滑 ⇒ 块平均（远处糊下去而不是抽成噪点）；非平滑 ⇒ 点采样（锐利）</li>
 *   <li>全分辨率时**零拷贝**：返回 -1 让调用方直接用源缓冲，不做任何多余工作</li>
 * </ul>
 *
 * <p>外部只需要一行：</p>
 * <pre>
 *   int step = Lod.resampleTo(stairs, imageGrid, gridW, gridH, distance, dst);
 *   // step &lt;= 0 ⇒ 不用重采样，直接用源；否则上传 dst（尺寸用 Lod.resampledWidth/Height）
 * </pre>
 */
public final class Lod {

    /** 全局 LOD 后端注册表：内置兜底 {@code builtin} 在构造时登记，链条由配置给出（"第一有效"）。 */
    private static final LodBackendRegistry REGISTRY = new LodBackendRegistry();

    /**
     * 配置链的来源。MC 侧在<b>注册期</b>只注入这个 lambda —— 那时读 NeoForge 配置会抛
     * "Trying to access unbound value"，所以真正的读取推迟到<b>首次使用</b>（配置已绑定）。
     */
    private static java.util.function.Supplier<String> chainSupplier;
    private static boolean chainApplied;

    private Lod() {
    }

    /** 注入配置链来源（注册期调用安全：只存 lambda，不读配置）。 */
    public static void setChainSupplier(java.util.function.Supplier<String> supplier) {
        chainSupplier = supplier;
        chainApplied = false;
    }

    /** 手动配置链条（测试/离线用；写法 "flywheel,voxy,builtin"）。 */
    public static void configureBackendChain(String csv) {
        chainApplied = true;
        REGISTRY.setChainCsv(csv);
    }

    /** 首次使用时才真正应用配置链（此刻配置已绑定）。 */
    private static LodBackendRegistry ready() {
        if (!chainApplied) {
            chainApplied = true;
            if (chainSupplier != null) {
                REGISTRY.setChainCsv(chainSupplier.get());
            }
        }
        return REGISTRY;
    }

    /** 全局后端注册表（外部后端在此 register；内置兜底已在册）。 */
    public static LodBackendRegistry registry() {
        return ready();
    }

    /** 后端选择日志出口（MC 侧接 Logger；不设就丢弃，但回退语义仍然成立）。 */
    public static void setBackendLogger(java.util.function.Consumer<String> logger) {
        REGISTRY.setLogger(logger);
    }

    /**
     * 一行（傻瓜）：阶梯表算出的步长，再交给后端链"允许覆盖"。
     *
     * <p>默认链里没有会覆盖的后端 ⇒ 结果<b>恒等于</b>阶梯表步长（零行为变化、可放心替换现有调用）；
     * 外部后端（Flywheel / voxy / DH 之类）注册并排到链首后即可覆盖档位 —— 这就是"优先使用第一有效"。</p>
     *
     * @return 最终步长（&ge; 1）
     */
    public static int stepViaChain(int stairsStep, int pixelsW, int pixelsH, int blocksW, int blocksH,
                                   double distanceBlocks) {
        final int base = Math.max(1, stairsStep);
        final int override = ready().overrideStep2D(base, pixelsW, pixelsH, blocksW, blocksH, distanceBlocks);
        return override > 0 ? override : base;
    }

    /** 一行：按后端链取 2D 档位（链全无效 ⇒ 内置兜底，且回退日志可见）。 */
    public static LodLevel level2D(Lod2DRequest request) {
        return ready().level2D(request);
    }

    /** 一行：按后端链取 3D 档位（同上）。 */
    public static LodLevel level3D(Lod3DRequest request) {
        return ready().level3D(request);
    }

    /** 一行：距离 ⇒ 该用哪个步长（插值/平滑/取整全由核心决定）。 */
    public static int stepFor(LodStairs stairs, double distanceBlocks) {
        if (stairs == null) {
            throw new IllegalArgumentException("stairs 不能为 null（没有阶梯表就没有 LOD）");
        }
        return stairs.stepFor(distanceBlocks);
    }

    /** 一行：按距离算出目标宽（= 重采样后的宽）。 */
    public static int resampledWidth(LodStairs stairs, int sourceWidth, double distanceBlocks) {
        return Lod2D.downsampledWidth(sourceWidth, Math.max(1, stepFor(stairs, distanceBlocks)));
    }

    /** 一行：按距离算出目标高。 */
    public static int resampledHeight(LodStairs stairs, int sourceHeight, double distanceBlocks) {
        return Lod2D.downsampledHeight(sourceHeight, Math.max(1, stepFor(stairs, distanceBlocks)));
    }

    /** 目标缓冲至少需要多大（调用方按它分配/扩容）。 */
    public static int requiredCapacity(LodStairs stairs, int sourceWidth, int sourceHeight,
                                       double distanceBlocks) {
        return resampledWidth(stairs, sourceWidth, distanceBlocks)
                * resampledHeight(stairs, sourceHeight, distanceBlocks);
    }

    /**
     * 一行：按距离把源图重采样到 dst。
     *
     * @return &lt;= 0 表示**不需要重采样**（当前档位就是全分辨率）——调用方直接用源缓冲，零拷贝；
     *         否则返回使用的步长（&gt; 1）
     */
    public static int resampleTo(LodStairs stairs, int[] src, int srcW, int srcH,
                                 double distanceBlocks, int[] dst) {
        final int step = stepFor(stairs, distanceBlocks);
        if (step <= 1) {
            return -1;                                     // 全分辨率：不碰数据（近处零成本）
        }
        if (stairs.smooth()) {
            Lod2D.downsampleAveraged(src, srcW, srcH, step, step, dst);
        } else {
            Lod2D.downsample(src, srcW, srcH, step, step, dst);
        }
        return step;
    }
}
