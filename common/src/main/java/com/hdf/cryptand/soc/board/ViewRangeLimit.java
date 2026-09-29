package com.hdf.cryptand.soc.board;

/**
 * ===== 最大显示范围（准入闸，common 纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户定案：「服务端可以额外设置**最大显示范围**，**max 上限就是无限制**
 * （最好添加限制防止被注入扫描，安全性）」。</p>
 *
 * <p>服务端是**被动**的（可见性与距离由客户端自己判断并发包），但客户端可以被伪造：
 * 恶意客户端能对**任意**屏地址反复发帧请求，服务端就会为它合成并回帧 ——
 * 既是扫描探针（探地图上有哪些屏、显示什么），也是资源放大（远端玩家逼服务端做全分辨率合成）。</p>
 *
 * <p>所以：<b>可见性判断在客户端，准入门槛在服务端</b>。本类就是那道门槛的判据，
 * 刻意做成纯函数以便离线钉死边界。</p>
 *
 * <p>安全默认（重要）：配置值 `NaN`（写坏了）⇒ <b>拒绝</b>，不是放行。
 * 拿不准的时候宁可让屏拿不到帧，也不能因为一个坏配置把整个服务端变成扫描器。</p>
 */
public final class ViewRangeLimit {

    /** 无限制（配置写负数即等于 max）。 */
    public static final double UNLIMITED = -1.0;

    private ViewRangeLimit() {
    }

    /** 配置值是否表示"无限制"（负值）。注意 NaN 不算无限制（见 {@link #allows}）。 */
    public static boolean isUnlimited(double configuredMax) {
        return configuredMax < 0.0;
    }

    /**
     * 该请求是否放行。
     *
     * @param distance      玩家到屏（拼接块最近点）的距离，单位 = 方块
     * @param configuredMax 配置的最大显示范围；负值 = 无限制
     * @return 放行 = true
     */
    public static boolean allows(double distance, double configuredMax) {
        if (Double.isNaN(configuredMax)) {
            return false;                       // 配置坏了 ⇒ 安全默认：拒绝
        }
        if (isUnlimited(configuredMax)) {
            return true;                        // max / 负值 = 无限制
        }
        if (Double.isNaN(distance) || distance < 0.0) {
            return false;                       // 距离算不出来 ⇒ 拒绝
        }
        if (Double.isInfinite(distance)) {
            return false;                       // 无限远 ⇒ 拒绝（除非无限制，上面已放行）
        }
        return distance <= configuredMax;
    }

    /** 人类可读的配置值（日志用）：`max` 或具体格数。 */
    public static String describe(double configuredMax) {
        return isUnlimited(configuredMax) ? "max（无限制）" : (configuredMax + " 格");
    }
}
