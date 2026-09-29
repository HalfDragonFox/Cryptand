package com.hdf.cryptand.lod;

import java.util.List;

/**
 * ===== LOD 阶梯表闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>{@code ./gradlew :common:runLodStairsTest}</p>
 */
public final class LodStairsSelfTest {

    private static int passed;
    private static int failed;

    private LodStairsSelfTest() {
    }

    public static void main(String[] args) {
        final LodStairs stairs = LodStairs.defaults();
        check("默认五档", stairs.size() == 5);
        check("第 1 档 8 格 100%", stairs.stair(0).maxDistanceBlocks() == 8.0 && stairs.stair(0).step() == 1);
        check("第 5 档 64 格（我按规律补的那档）", stairs.stair(4).maxDistanceBlocks() == 64.0);

        // 边界：正好等于阈值算本档；刚超过算下一档
        check("距离 0 ⇒ 100%（step 1）", stairs.stepFor(0.0) == 1);
        check("距离 8（正好阈值）⇒ 仍第 1 档", stairs.stepFor(8.0) == 1);
        // ⚠ 平滑默认开：8.01 格的百分比由**插值**给出（≈99.9%）⇒ 步长仍是 1。
        //    非平滑时才会在 8.01 立刻掉到第 2 档（50%）。这一条就是"是否平滑"的判据。
        check("平滑：8.01 ⇒ 百分比插值仍接近 100%（step 1）", stairs.stepFor(8.01) == 1);
        check("平滑：12 格（8→16 中点）⇒ 75%", Math.abs(stairs.percentFor(12.0) - 75.0) < 1e-6);
        final LodStairs hard = LodStairs.parse(LodStairs.DEFAULT_TABLE, false);
        check("非平滑：8.01 ⇒ 立刻掉到第 2 档（step 2）", hard.stepFor(8.01) == 2);
        check("非平滑：12 格仍是 50%（不插值）", hard.percentFor(12.0) == 50.0);
        check("距离 16 ⇒ 第 2 档", stairs.stepFor(16.0) == 2);
        check("距离 32 ⇒ 25% ⇒ step 4", stairs.stepFor(32.0) == 4);
        check("距离 48 ⇒ 12.5% ⇒ step 8", stairs.stepFor(48.0) == 8);
        check("距离 64 ⇒ 6.25% ⇒ step 16", stairs.stepFor(64.0) == 16);
        check("距离 10000 ⇒ 仍取最后一档（不无限降）", stairs.stepFor(10000.0) == 16);
        check("距离 NaN ⇒ 当 0 处理（第 1 档）", stairs.stepFor(Double.NaN) == 1);
        check("距离负 ⇒ 当 0 处理", stairs.stepFor(-5.0) == 1);

        // 自定义表（用户可按屏给不同阶梯）
        final LodStairs custom = LodStairs.parse("4:100,12:25");
        check("自定义表档数", custom.size() == 2);
        check("自定义：距离 4 ⇒ step 1", custom.stepFor(4.0) == 1);
        check("自定义：距离 12 ⇒ 25% ⇒ step 4", custom.stepFor(12.0) == 4);
        check("自定义：超出 ⇒ 最后一档", custom.stepFor(999.0) == 4);

        // 坏输入：明确抛错（不静默回退默认表）
        check("乱序表 ⇒ 抛错", throwsOn(() -> LodStairs.of(List.of(
                new LodStairs.Stair(16.0, 50.0), new LodStairs.Stair(8.0, 100.0)))));
        check("重复距离 ⇒ 抛错", throwsOn(() -> LodStairs.of(List.of(
                new LodStairs.Stair(8.0, 100.0), new LodStairs.Stair(8.0, 50.0)))));
        check("空表 ⇒ 抛错", throwsOn(() -> LodStairs.of(List.of())));
        check("百分比 0 ⇒ 抛错", throwsOn(() -> new LodStairs.Stair(8.0, 0.0)));
        check("百分比 >100 ⇒ 抛错", throwsOn(() -> new LodStairs.Stair(8.0, 101.0)));
        check("距离 0 ⇒ 抛错", throwsOn(() -> new LodStairs.Stair(0.0, 100.0)));
        check("解析坏格式（无冒号）⇒ 抛错", throwsOn(() -> LodStairs.parse("8-100")));
        check("解析坏格式（非数字）⇒ 抛错", throwsOn(() -> LodStairs.parse("abc:50")));
        check("解析空文本 ⇒ 抛错", throwsOn(() -> LodStairs.parse("  ")));

        // 描述可读（日志用）
        final String desc = stairs.describe();
        check("描述含档位信息（" + desc + "）", desc.contains("8格→100%") && desc.contains("64格→6.25%"));

        System.out.println("[lod-stairs] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
