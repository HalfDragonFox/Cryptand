/**
 * ===== 原版热行为参数记录（2026-09-12 用户："全部接管，全部由自管模型实现，
 * 原版算法全部不使用"） =====
 *
 * 原版 {@code ThermalBehaviour} 对象在自管模式下【不再创建】（静态工厂返回 null，
 * 省掉每设备一个对象 + coolingAir Map + 若干字段）。但设备的发热公式里仍需要它的
 * 两个参数来做等价换算，因此在这些工厂被拦截时把参数记到这里（按方块位置）。
 *
 * 等价换算（把原版 {@code applyTickPower(P)} 换成自管 {@code addHeat(J)}）：
 *   原版：ΔT = (P/20) / thermalMass        （每 tick 注入 P/20 焦耳）
 *   自管：ΔT = J / heatCapacity
 *   ⇒ J = (P/20) × (heatCapacity / thermalMass)
 * 这样即使自管模型的热容不同，逐 tick 温升也与原版完全一致。
 */

package com.hdf.cryptand.neoforge.powergrid.device.thermal;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LegacyThermalParams {

    /** pos → [thermalMass, dissipationFactor] */
    private static final Map<BlockPos, double[]> PARAMS = new ConcurrentHashMap<>();

    /** 默认热容（原版线圈量级；取不到记录时用它，保证热量不会变成 0） */
    public static final double DEFAULT_MASS = 1.5;

    private LegacyThermalParams() {
    }

    /** 工厂拦截时记录（原版 thermalMass / dissipationFactor） */
    public static void record(BlockPos pos, double thermalMass, double dissipationFactor) {
        if (pos == null) return;
        try {
            double m = thermalMass > 0 ? thermalMass : DEFAULT_MASS;
            PARAMS.put(pos, new double[]{m, Math.max(dissipationFactor, 0)});
        } catch (Throwable ignored) {
        }
    }

    /** 原版热容（J/K）；无记录 → {@link #DEFAULT_MASS} */
    public static double thermalMass(BlockPos pos) {
        if (pos == null) return DEFAULT_MASS;
        double[] p = PARAMS.get(pos);
        return (p == null || !(p[0] > 0)) ? DEFAULT_MASS : p[0];
    }

    /** 原版散热系数（每 tick 比例）；无记录 → 0 */
    public static double dissipationFactor(BlockPos pos) {
        if (pos == null) return 0;
        double[] p = PARAMS.get(pos);
        return p == null ? 0 : p[1];
    }

    /** 位置清理（设备移除） */
    public static void remove(BlockPos pos) {
        if (pos != null) PARAMS.remove(pos);
    }

    /** 已记录条目数（诊断） */
    public static int size() {
        return PARAMS.size();
    }
}
