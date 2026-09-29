package com.hdf.cryptand.soc.board;

/**
 * ===== 最大显示范围闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>{@code ./gradlew :common:runViewRangeLimitTest}</p>
 */
public final class ViewRangeLimitSelfTest {

    private static int passed;
    private static int failed;

    private ViewRangeLimitSelfTest() {
    }

    public static void main(String[] args) {
        // 无限制
        check("max(-1)：任意距离都放行", ViewRangeLimit.allows(1.0e9, ViewRangeLimit.UNLIMITED));
        check("max(-1)：描述成 max", ViewRangeLimit.describe(ViewRangeLimit.UNLIMITED).startsWith("max"));
        check("负数都算无限制", ViewRangeLimit.isUnlimited(-0.5));

        // 有上限：边界必须钉死（等于上限放行、略超拒绝）
        check("上限 128：距离 128 放行", ViewRangeLimit.allows(128.0, 128.0));
        check("上限 128：距离 128.0001 拒绝", !ViewRangeLimit.allows(128.0001, 128.0));
        check("上限 128：距离 0 放行（贴脸）", ViewRangeLimit.allows(0.0, 128.0));
        check("上限 0：距离 0 放行", ViewRangeLimit.allows(0.0, 0.0));
        check("上限 0：距离 1 拒绝", !ViewRangeLimit.allows(1.0, 0.0));

        // 安全默认：坏配置/坏距离一律拒绝
        check("配置 NaN ⇒ 拒绝（安全默认，不是放行）", !ViewRangeLimit.allows(1.0, Double.NaN));
        check("距离 NaN ⇒ 拒绝", !ViewRangeLimit.allows(Double.NaN, 128.0));
        check("距离负 ⇒ 拒绝", !ViewRangeLimit.allows(-1.0, 128.0));
        check("距离 Infinity 且有限上限 ⇒ 拒绝", !ViewRangeLimit.allows(Double.POSITIVE_INFINITY, 128.0));
        check("距离 Infinity 但无限制 ⇒ 放行", ViewRangeLimit.allows(Double.POSITIVE_INFINITY, ViewRangeLimit.UNLIMITED));

        // 上限描述
        check("描述带单位", ViewRangeLimit.describe(256.0).contains("256"));

        System.out.println("[view-range] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
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
