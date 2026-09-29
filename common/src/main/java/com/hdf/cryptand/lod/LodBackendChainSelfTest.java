package com.hdf.cryptand.lod;

import com.hdf.cryptand.soc.board.ScreenSamplingPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== LOD 后端链条离线闸门（common，纯 Java 零 MC）=====
 *
 * <p>用户定案：「lod 后端有<b>自实现兜底</b>」+「配置可以配置<b>后端链条</b>，<b>优先使用第一有效</b>」。</p>
 *
 * <p>不需要 MC、不需要客户端：{@code ./gradlew :common:runLodBackendChainTest}。</p>
 */
public final class LodBackendChainSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private LodBackendChainSelfTest() {
    }

    public static void main(String[] args) {
        // ---- 自实现兜底：构造即登记，默认链就是它 ----
        final LodBackendRegistry r0 = new LodBackendRegistry();
        check("兜底后端构造即登记", r0.get(LodBackendRegistry.BUILTIN) instanceof LodBackendBuiltin);
        check("兜底后端永远可用", r0.get(LodBackendRegistry.BUILTIN).available());
        check("默认链只有兜底", r0.chain().equals(List.of(LodBackendRegistry.BUILTIN)));

        // ---- 链条：兜底永远垫底；空项/重复/空白被清理 ----
        final LodBackendRegistry r1 = new LodBackendRegistry();
        r1.setChainCsv(" flywheel , voxy ,, flywheel ");
        check("链：清理空白/空项/重复",
                r1.chain().equals(List.of("flywheel", "voxy", LodBackendRegistry.BUILTIN)));
        check("链：兜底自动垫底",
                r1.chain().get(r1.chain().size() - 1).equals(LodBackendRegistry.BUILTIN));

        final LodBackendRegistry r2 = new LodBackendRegistry();
        r2.setChainCsv("");
        check("链：空配置只用兜底", r2.chain().equals(List.of(LodBackendRegistry.BUILTIN)));

        // ---- 优先第一有效：链里前面的没登记 ⇒ 跳过 + 记日志，落到后面的 ----
        final List<String> logs = new ArrayList<>();
        final LodBackendRegistry r3 = new LodBackendRegistry();
        r3.setLogger(logs::add);
        r3.setChainCsv("ghost,good");
        r3.register(fake("good", true, LodCapabilities.DOWNSAMPLE_2D, new LodLevel(4, 4, 0, false), null));
        check("链：第一有效（跳过未登记的 ghost）", r3.level2D(request2D(100.0)).stepX() == 4);
        check("链：跳过未登记项要记日志",
                logs.stream().anyMatch(s -> s.contains("未登记") && s.contains("ghost")));

        // ---- 不可用 / 不支持 / 返回 null 逐项跳过，最后落兜底 ----
        final List<String> logs4 = new ArrayList<>();
        final LodBackendRegistry r4 = new LodBackendRegistry();
        r4.setLogger(logs4::add);
        r4.setChainCsv("down,nocap,nullish");
        r4.register(fake("down", false, LodCapabilities.DOWNSAMPLE_2D, new LodLevel(8, 8, 0, false), null));
        r4.register(fake("nocap", true, LodCapabilities.GEOMETRY_3D, null, null));
        r4.register(fake("nullish", true, LodCapabilities.DOWNSAMPLE_2D, null, null));
        r4.level2D(request2D(100.0));
        check("链：不可用被跳过", logs4.stream().anyMatch(s -> s.contains("不可用") && s.contains("down")));
        check("链：不支持该方向被跳过", logs4.stream().anyMatch(s -> s.contains("不支持") && s.contains("nocap")));
        check("链：自称支持却返回 null 被跳过",
                logs4.stream().anyMatch(s -> s.contains("null") && s.contains("nullish")));
        check("链：落内置兜底时汇总日志可见（回退不静默）",
                logs4.stream().anyMatch(s -> s.contains("使用内置兜底")));

        // ---- 3D 同语义 ----
        final LodBackendRegistry r5 = new LodBackendRegistry();
        r5.setChainCsv("ghost3d");
        check("3D 链全无效时落内置距离分段",
                r5.level3D(new Lod3DRequest(240.0, 256.0, 8, true)).stepX() > 1);

        // ---- 步长覆盖：链首外部后端第一有效；都不覆盖 ⇒ 返回 -1（调用方用阶梯表原值） ----
        final List<String> logs6 = new ArrayList<>();
        final LodBackendRegistry r6 = new LodBackendRegistry();
        r6.setLogger(logs6::add);
        r6.setChainCsv("cover,builtin");
        r6.register(new LodBackend() {
            @Override
            public String name() {
                return "cover";
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
            public int overrideStep2D(int stairsStep, int pixelsW, int pixelsH, int blocksW, int blocksH,
                                      double distanceBlocks) {
                return 8;
            }
        });
        check("覆盖：链首外部后端生效（第一有效）", r6.overrideStep2D(2, 64, 64, 8, 8, 20.0) == 8);
        check("覆盖：覆盖也要记日志（可见）",
                logs6.stream().anyMatch(s -> s.contains("后端覆盖") && s.contains("cover")));

        final LodBackendRegistry r7 = new LodBackendRegistry();
        r7.setChainCsv("builtin");
        check("覆盖：默认链不覆盖（-1 ⇒ 走阶梯表）", r7.overrideStep2D(2, 64, 64, 8, 8, 20.0) == -1);

        final LodBackendRegistry r8 = new LodBackendRegistry();
        r8.setChainCsv("ghost");
        check("覆盖：链中未登记 ⇒ 不覆盖（-1）", r8.overrideStep2D(2, 64, 64, 8, 8, 20.0) == -1);

        // ---- 傻瓜门面：默认链下 stepViaChain 恒等于阶梯表步长（零行为变化） ----
        final LodStairs stairs = LodStairs.parse("8:100,16:50,32:25,48:12.5,64:6.25", true);
        Lod.configureBackendChain("builtin");
        for (final double d : new double[] {2.0, 10.0, 20.0, 40.0, 100.0}) {
            final int viaStairs = Lod.stepFor(stairs, d);
            check("门面：默认链下 stepViaChain == 阶梯表步长（距离 " + d + "）",
                    Lod.stepViaChain(viaStairs, 64, 64, 8, 8, d) == viaStairs);
        }
        // 外部后端排到链首 ⇒ 覆盖生效（"优先使用第一有效"）
        Lod.registry().register(new LodBackend() {
            @Override
            public String name() {
                return "cover2";
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
            public int overrideStep2D(int stairsStep, int pixelsW, int pixelsH, int blocksW, int blocksH,
                                      double distanceBlocks) {
                return 4;
            }
        });
        Lod.configureBackendChain("cover2,builtin");
        check("门面：链首外部后端覆盖生效",
                Lod.stepViaChain(Lod.stepFor(stairs, 20.0), 64, 64, 8, 8, 20.0) == 4);
        Lod.configureBackendChain("builtin");   // 复位，避免污染其他用例

        // ---- null 请求明确抛错（不兜底） ----
        boolean threw = false;
        try {
            new LodBackendRegistry().level2D(null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("null 请求明确抛错", threw);

        System.out.println("[lod-chain] " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            for (final String failure : failures) {
                System.out.println("  [FAIL] " + failure);
            }
            System.exit(1);
        }
    }

    private static LodBackend fake(String name, boolean available, int caps, LodLevel level2D, LodLevel level3D) {
        return new LodBackend() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public LodCapabilities capabilities() {
                return LodCapabilities.of(caps);
            }

            @Override
            public LodLevel level2D(Lod2DRequest request) {
                return level2D;
            }

            @Override
            public LodLevel level3D(Lod3DRequest request) {
                return level3D;
            }
        };
    }

    private static Lod2DRequest request2D(double distance) {
        return new Lod2DRequest(64, 64, 8, 8, distance,
                ScreenSamplingPolicy.DEFAULT_PIXELS_PER_RADIAN, 8, 0, false);
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}