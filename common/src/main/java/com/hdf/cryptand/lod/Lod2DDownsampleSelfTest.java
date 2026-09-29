package com.hdf.cryptand.lod;

/**
 * ===== LOD 降采样闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>钉住三件事：step=1 恒等（近处不能有任何变化）、抽样口径是"每块左上角"、
 * 缓冲不够时明确报错（不越界写）。</p>
 *
 * <p>{@code ./gradlew :common:runLodDownsampleTest}</p>
 */
public final class Lod2DDownsampleSelfTest {

    private static int passed;
    private static int failed;

    private Lod2DDownsampleSelfTest() {
    }

    public static void main(String[] args) {
        // 4x4 递增缓冲：src[y][x] = y * 10 + x
        final int w = 4;
        final int h = 4;
        final int[] src = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                src[y * w + x] = y * 10 + x;
            }
        }

        // step = 1：恒等（近处不能变样）
        final int[] one = new int[w * h];
        Lod2D.downsample(src, w, h, 1, 1, one);
        boolean identical = true;
        for (int i = 0; i < src.length; i++) {
            if (src[i] != one[i]) {
                identical = false;
                break;
            }
        }
        check("step=1 ⇒ 逐像素恒等", identical);

        // step = 2：4x4 ⇒ 2x2，取左上角 (0,0)=0,(0,2)=2,(2,0)=20,(2,2)=22
        check("step=2 宽度 4⇒2", Lod2D.downsampledWidth(4, 2) == 2);
        check("step=2 高度 4⇒2", Lod2D.downsampledHeight(4, 2) == 2);
        final int[] two = new int[4];
        Lod2D.downsample(src, w, h, 2, 2, two);
        check("step=2 取左上角（0,2,20,22）", two[0] == 0 && two[1] == 2 && two[2] == 20 && two[3] == 22);

        // 非整除尺寸：5 宽 step=2 ⇒ 3 列（最后一列取 x=4 不越界）
        final int w5 = 5;
        final int[] src5 = new int[w5 * 2];
        for (int i = 0; i < src5.length; i++) {
            src5[i] = i;
        }
        check("5 宽 step=2 ⇒ 3 列（ceil）", Lod2D.downsampledWidth(5, 2) == 3);
        check("2 高 step=2 ⇒ 1 行（ceil）", Lod2D.downsampledHeight(2, 2) == 1);
        final int[] dst5 = new int[3];
        Lod2D.downsample(src5, w5, 2, 2, 2, dst5);
        check("非整除：最后一列取 x=4（不外推越界，实得 " + dst5[0] + "," + dst5[1] + "," + dst5[2] + "）",
            dst5[0] == 0 && dst5[1] == 2 && dst5[2] == 4);

        // 坏输入必须明确报错
        check("目标缓冲太小 ⇒ 抛错", throwsOn(() -> Lod2D.downsample(src, w, h, 2, 2, new int[3])));
        check("源缓冲太小 ⇒ 抛错", throwsOn(() -> Lod2D.downsample(new int[3], w, h, 1, 1, new int[w * h])));
        check("源尺寸非法 ⇒ 抛错", throwsOn(() -> Lod2D.downsample(src, 0, h, 1, 1, new int[1])));
        check("null ⇒ 抛错", throwsOn(() -> Lod2D.downsample(null, w, h, 1, 1, new int[w * h])));

        // 档位与降采样口径一致：step 每轴独立
        check("各轴独立 step", Lod2D.downsampledWidth(8, 4) == 2 && Lod2D.downsampledHeight(8, 2) == 4);

        // ===== 平滑版（块平均）=====
        // 4x4 里每块 (0,0)(1,0)(0,1)(1,1) = 0,1,10,11 ⇒ 平均 5.5 ⇒ 四舍五入 6
        final int[] avg = new int[4];
        Lod2D.downsampleAveraged(src, w, h, 2, 2, avg);
        check("平均采样：左上块 = 6（0,1,10,11 的均值四舍五入）", avg[0] == 6);
        check("平均采样：尺寸与点采样一致（2x2）",
            Lod2D.downsampledWidth(w, 2) == 2 && Lod2D.downsampledHeight(h, 2) == 2);
        // 全同色时两者应当一致（平滑不引入偏差）
        final int[] solid = new int[16];
        java.util.Arrays.fill(solid, 0xFF336699);
        final int[] solidAvg = new int[4];
        Lod2D.downsampleAveraged(solid, w, h, 2, 2, solidAvg);
        check("平均采样：全同色 ⇒ 颜色不变（不漂移）", solidAvg[0] == 0xFF336699);
        check("平均采样：坏输入 ⇒ 抛错", throwsOn(() -> Lod2D.downsampleAveraged(src, w, h, 2, 2, new int[3])));

        System.out.println("[lod-downsample] " + passed + " passed, " + failed + " failed");
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
