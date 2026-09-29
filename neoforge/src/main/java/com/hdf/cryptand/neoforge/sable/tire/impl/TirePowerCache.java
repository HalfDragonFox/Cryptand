/**
 * ===== 轮胎摩擦功率缓存（2026-09-14） =====
 *
 * <p>轮胎拟真 hook 每 substep 把【真实轮胎模型】算出的功率写进来，供
 * {@code WheelStressAccess.computeStress} 使用 —— 即用户拍板的
 * "SU 切真实摩擦功率"。
 *
 * <p>功率定义（物理量，W）：`P = |Fx·v_x| + |Fy·v_y|`（力 × 速度，v 已换算为 m/s）。
 * 应力侧：`SU = P / wattPerSU`（wattPerSU 为标定项，见 ConfigSable）。
 *
 * <p>过期：TTL 内无更新视为失效（回退旧的 v9 应力公式），避免车轮静止/卸载后
 * 残留功率把应力钉死。
 */
package com.hdf.cryptand.neoforge.sable.tire.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TirePowerCache {

    /** 一条功率样本。 */
    public record Sample(double watts, double normalLoad, double mu, long timeMs) {
    }

    private static final Map<Long, Sample> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 2000L;

    private TirePowerCache() {
    }

    public static void put(final long posKey, final double watts, final double normalLoad, final double mu) {
        CACHE.put(posKey, new Sample(
                Double.isFinite(watts) ? Math.max(0.0, watts) : 0.0, normalLoad, mu,
                System.currentTimeMillis()));
    }

    /** 取样本（过期/未命中 → null，并顺手移除过期项）。 */
    public static Sample get(final long posKey) {
        final Sample s = CACHE.get(posKey);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.timeMs() > TTL_MS) {
            CACHE.remove(posKey);
            return null;
        }
        return s;
    }

    public static void clear() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }
}
