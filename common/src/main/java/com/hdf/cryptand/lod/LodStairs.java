package com.hdf.cryptand.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ===== LOD 距离阶梯表（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户定案：「lod 距离不对，可以**配置表**设置 lod 阶梯，然后核心需要设置**截梯**，
 * 比如 8、16 以及**对应渲染百分比**，然后 **oc 部分只需要注册时传入这个参数即可**，
 * 目前五个截梯，分别为 8、16、32、48」。</p>
 *
 * <p>为什么要换掉"连续算"：原来的 {@link Lod2D#level} 调 {@code ScreenSamplingPolicy.stepFor} 把距离
 * 连续映射成步长 —— 用户实测"距离不对"。改成**离散阶梯**后：每档 = (距离上限, 渲染百分比)，
 * 行为可预期、可配置、可按屏给（注册时传入）。</p>
 *
 * <p>渲染百分比 ⇒ 抽稀步长：{@code step = max(1, round(100 / percent))}。
 * 例：100% ⇒ 1（全分辨率）、50% ⇒ 2、25% ⇒ 4、12.5% ⇒ 8、6.25% ⇒ 16。</p>
 *
 * <p>⚠ 距离必须**严格递增**：乱序的表会让"查表"结果取决于实现细节（事故温床），构造期直接抛错。</p>
 */
public final class LodStairs {

    /** 一档：距相机不超过 {@code maxDistanceBlocks} 格时，按 {@code renderPercent}% 渲染。 */
    public record Stair(double maxDistanceBlocks, double renderPercent) {

        public Stair {
            if (!(maxDistanceBlocks > 0.0) || Double.isNaN(maxDistanceBlocks)) {
                throw new IllegalArgumentException("距离上限必须为正：" + maxDistanceBlocks);
            }
            if (!(renderPercent > 0.0) || renderPercent > 100.0 || Double.isNaN(renderPercent)) {
                throw new IllegalArgumentException("渲染百分比必须在 (0,100]：" + renderPercent);
            }
        }

        /** 该档对应的抽稀步长（1 = 全分辨率）。 */
        public int step() {
            return Math.max(1, (int) Math.round(100.0 / renderPercent));
        }
    }

    /** 默认五档（用户给的 8/16/32/48 + 按规律补的 64；改配置即可，不必改代码）。 */
    public static final String DEFAULT_TABLE = "8:100,16:50,32:25,48:12.5,64:6.25";

    private final Stair[] stairs;
    /** 是否平滑（默认开，用户 2026-09-29：「lod 阶梯传入时需要设置是否平滑，平滑默认开」）。 */
    private final boolean smooth;

    private LodStairs(Stair[] stairs, boolean smooth) {
        this.stairs = stairs;
        this.smooth = smooth;
    }

    /** 是否平滑。 */
    public boolean smooth() {
        return smooth;
    }

    /** 构造（校验严格递增；平滑默认开）。 */
    public static LodStairs of(List<Stair> list) {
        return of(list, true);
    }

    /** 构造并显式指定是否平滑。 */
    public static LodStairs of(List<Stair> list, boolean smooth) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("阶梯表不能为空");
        }
        final Stair[] arr = list.toArray(new Stair[0]);
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].maxDistanceBlocks() <= last) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 档距离必须大于前一档："
                        + arr[i].maxDistanceBlocks() + " <= " + last + "（乱序表会让查表结果不可预期）");
            }
            last = arr[i].maxDistanceBlocks();
        }
        return new LodStairs(arr, smooth);
    }

    /**
     * 解析配置文本：{@code "8:100,16:50,32:25"}。
     *
     * <p>坏写法**明确抛错**，不静默回退默认表 —— 否则用户以为改生效了，实际跑的是默认值。</p>
     */
    public static LodStairs parse(String text) {
        return parse(text, true);
    }

    /** 解析配置文本并显式指定是否平滑。 */
    public static LodStairs parse(String text, boolean smooth) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("阶梯表文本不能为空");
        }
        final List<Stair> list = new ArrayList<>();
        for (String part : text.split(",")) {
            final String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            final int colon = piece.indexOf(':');
            if (colon <= 0 || colon == piece.length() - 1) {
                throw new IllegalArgumentException("阶梯项格式应为 距离:百分比，实得：" + piece);
            }
            try {
                final double distance = Double.parseDouble(piece.substring(0, colon).trim());
                final double percent = Double.parseDouble(piece.substring(colon + 1).trim());
                list.add(new Stair(distance, percent));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("阶梯项不是数字：" + piece, e);
            }
        }
        return of(list, smooth);
    }

    /** 默认表。 */
    public static LodStairs defaults() {
        return parse(DEFAULT_TABLE);
    }

    /** 命中档位：{@code distance <= maxDistanceBlocks} 的第一档；超出最后一档 ⇒ 最后一档（不无限降）。 */
    public Stair lookup(double distanceBlocks) {
        final double d = Double.isNaN(distanceBlocks) ? 0.0 : Math.max(0.0, distanceBlocks);
        for (Stair stair : stairs) {
            if (d <= stair.maxDistanceBlocks()) {
                return stair;
            }
        }
        return stairs[stairs.length - 1];
    }

    /**
     * 目标渲染百分比（平滑开 ⇒ 在相邻档之间按距离**线性插值**；平滑关 ⇒ 用档位原值）。
     *
     * <p>插值让"目标分辨率"随距离连续变化，而不是在档位边界上突然跳一档 ——
     * 这是"平滑阶梯"能做到的第一层；第二层（视觉）由 {@link Lod2D#downsampleAveraged} 的块平均承担。</p>
     */
    public double percentFor(double distanceBlocks) {
        final double d = Double.isNaN(distanceBlocks) ? 0.0 : Math.max(0.0, distanceBlocks);
        if (!smooth || stairs.length == 1) {
            return lookup(d).renderPercent();
        }
        if (d <= stairs[0].maxDistanceBlocks()) {
            return stairs[0].renderPercent();                 // 第一档之内不插值（前面没档可插）
        }
        for (int i = 1; i < stairs.length; i++) {
            final Stair prev = stairs[i - 1];
            final Stair cur = stairs[i];
            if (d <= cur.maxDistanceBlocks()) {
                final double span = cur.maxDistanceBlocks() - prev.maxDistanceBlocks();
                if (span <= 0.0) {
                    return cur.renderPercent();
                }
                final double t = (d - prev.maxDistanceBlocks()) / span;
                return prev.renderPercent() + t * (cur.renderPercent() - prev.renderPercent());
            }
        }
        return stairs[stairs.length - 1].renderPercent();      // 超出最后一档：保持最后一档
    }

    /** 命中档位对应的抽稀步长（1 = 全分辨率）；平滑开 ⇒ 由插值后的百分比换算。 */
    public int stepFor(double distanceBlocks) {
        if (!smooth) {
            return lookup(distanceBlocks).step();
        }
        final double percent = percentFor(distanceBlocks);
        if (!(percent > 0.0)) {
            return 1;
        }
        return Math.max(1, (int) Math.round(100.0 / percent));
    }

    /** 档数。 */
    public int size() {
        return stairs.length;
    }

    public Stair stair(int index) {
        return stairs[index];
    }

    /** 人类可读（日志/命令用）。 */
    public String describe() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stairs.length; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            final Stair s = stairs[i];
            sb.append(trim(s.maxDistanceBlocks())).append("格→")
                    .append(trim(s.renderPercent())).append("%(1/").append(s.step()).append(')');
        }
        return sb.toString();
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
