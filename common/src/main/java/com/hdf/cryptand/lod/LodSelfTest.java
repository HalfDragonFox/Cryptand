package com.hdf.cryptand.lod;

import com.hdf.cryptand.soc.board.ScreenSamplingPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== LOD 核心离线闸门（common，纯 Java 零 MC）=====
 *
 * <p>不需要 MC、不需要客户端：{@code ./gradlew :common:runLodCoreTest}。</p>
 */
public final class LodSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private LodSelfTest() {
    }

    public static void main(String[] args) {
        // ---- 2D：步长是 2 的幂、随距离不降、夹在 [1, maxStep] ----
        final Lod2DRequest near = request2D(20.0, 64, 64, 8);
        final Lod2DRequest far = request2D(2000.0, 64, 64, 8);
        final LodLevel nearLevel = Lod2D.level(near);
        final LodLevel farLevel = Lod2D.level(far);
        check("2D 近处：全分辨率", nearLevel.stepX() == 1 && nearLevel.stepY() == 1);
        check("2D 远处：降采样", farLevel.stepX() > 1);
        check("2D 步长是 2 的幂", isPowerOfTwo(farLevel.stepX()) && isPowerOfTwo(farLevel.stepY()));
        check("2D 单调：更远不更清晰", farLevel.stepX() >= nearLevel.stepX());
        check("2D 夹上限", farLevel.stepX() <= 8 && farLevel.stepY() <= 8);
        final LodLevel capped = Lod2D.level(request2D(10000.0, 512, 512, 4));
        check("2D 极远仍受 maxStep 夹住", capped.stepX() == 4);

        // ---- 3D：距离分段、2 的幂、不超 maxLevel ----
        final LodLevel d3near = Lod3D.level(new Lod3DRequest(16.0, 256.0, 8, true));
        final LodLevel d3far = Lod3D.level(new Lod3DRequest(240.0, 256.0, 8, true));
        check("3D 近处：级别 1", d3near.stepX() == 1);
        check("3D 远处：级别 > 1", d3far.stepX() > 1);
        check("3D 是 2 的幂且不超 maxLevel", isPowerOfTwo(d3far.stepX()) && d3far.stepX() <= 8);

        // ---- 能力位 ----
        final LodCapabilities caps = LodCapabilities.of(LodCapabilities.DOWNSAMPLE_2D)
                .plus(LodCapabilities.COMPRESS_FRAME);
        check("能力位：2D", caps.supports2D());
        check("能力位：不支持 3D", !caps.supports3D());
        check("能力位：压缩帧", caps.has(LodCapabilities.COMPRESS_FRAME));

        // ---- 注册表：不可用后端被跳过且**日志可见** ----
        final List<String> logs = new ArrayList<>();
        final LodBackendRegistry registry = new LodBackendRegistry();
        registry.setLogger(logs::add);
        registry.register(new LodBackend() {
            @Override
            public String name() {
                return "external-3d";
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public LodCapabilities capabilities() {
                return LodCapabilities.of(LodCapabilities.GEOMETRY_3D);
            }
        });
        registry.register(new LodBackend() {
            @Override
            public String name() {
                return "builtin-2d";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public LodCapabilities capabilities() {
                return LodCapabilities.of(LodCapabilities.DOWNSAMPLE_2D);
            }

            @Override
            public LodLevel level2D(Lod2DRequest request) {
                return new LodLevel(2, 2, 30, false);
            }
        });
        // 2026-09-29 起注册表按"配置链 + 第一有效"选后端（内置兜底 builtin 永远在册且垫底）
        registry.setChainCsv("external-3d,builtin-2d");
        check("注册表：可用名单含内置兜底与自定义后端",
                registry.availableNames().containsAll(List.of(LodBackendRegistry.BUILTIN, "builtin-2d")));
        check("注册表：不可用后端被记日志", logs.stream().anyMatch(s -> s.contains("external-3d")));
        check("注册表：2D 走链中第一个有效后端", registry.level2D(far).stepX() == 2);

        // 链中两个后端都不支持 3D ⇒ 落内置兜底（回退必须可见）
        logs.clear();
        final LodLevel fallback3D = registry.level3D(new Lod3DRequest(240.0, 256.0, 8, true));
        check("注册表：3D 落内置兜底", fallback3D.stepX() > 1);
        check("注册表：兜底写日志（回退可见）", logs.stream().anyMatch(s -> s.contains("使用内置兜底")));

        // ---- 档位值语义 ----
        check("LodLevel：NONE 是全分辨率", LodLevel.NONE.isFull());
        check("LodLevel：相等语义",
                new LodLevel(2, 2, 30, true).equals(new LodLevel(2, 2, 30, true)));
        check("LodLevel：step 至少 1", new LodLevel(0, -5, -1, false).stepX() == 1);

        System.out.println("[lod] " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            for (final String failure : failures) {
                System.out.println("  [FAIL] " + failure);
            }
            System.exit(1);
        }
    }

    private static Lod2DRequest request2D(double distance, int pixels, int blocks, int maxStep) {
        return new Lod2DRequest(pixels, pixels, blocks, blocks, distance,
                ScreenSamplingPolicy.DEFAULT_PIXELS_PER_RADIAN, maxStep, 0, false);
    }

    private static boolean isPowerOfTwo(int value) {
        return value >= 1 && (value & (value - 1)) == 0;
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
