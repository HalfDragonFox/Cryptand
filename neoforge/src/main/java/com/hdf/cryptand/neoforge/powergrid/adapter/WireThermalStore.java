package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导线段温度模型存储（静态持久：跨网络重建保留温度与冷却状态）。
 * <p>
 * 2026-08-12 用户要求：导线视作【电阻】元件，连续段导线电阻相加、共用同一套
 * 温度模型——这样不用单独收集每根导线；分叉/接线端子等不连续处为不同段，
 * 各自独立温度模型（key = 段路径签名 pathKey）。
 * <p>
 * 2026-08-19 用户要求"25°C 室温下金导线 ≥160A 额定"：散热/热容【按段长度
 * 缩放】（散热与发热都随长度增长 → 稳态温度与长度无关，符合真实导线【每米
 * 额定】——长导线不再因电阻累积而更容易烧）。环境 25°C（298.15K）、最高
 * 200°C（473.15K）。每求解步用段 I²R 平均损耗推进温度，超温即接近烧毁阈值。
 * <p>
 * 效果：金导线 ρ=0.0015Ω/m，160A → 稳态温升 25600×0.0015/2 ≈ 19K → ≈44°C
 * （远低于 200°C 烧毁线）；烧毁电流 ≈ 483A（3 倍额定余量）。
 */
public final class WireThermalStore {

    private static final Map<String, ThermalModel> THERMAL = new ConcurrentHashMap<>();

    /** 每米散热系数（W/(K·m)）——段长 × 本值 = 段散热（长导线散热面大） */
    private static final double CONDUCTANCE_PER_M = 2.0;
    /** 每米热容（J/(K·m)）——段长 × 本值 = 段热容（长导线热惯性大） */
    private static final double HEAT_CAP_PER_M = 5.0;
    /** 环境温度（K）＝ 25°C（2026-08-19 用户要求） */
    private static final double AMBIENT_K = 298.15;
    /** 最高安全温度（K）＝ 200°C（烧毁阈值，与 PhasorEngine.WIRE_BURN_TEMP_C 一致） */
    private static final double MAX_K = 473.15;

    /**
     * 获取/创建导线段温度模型（跨网络重建持久；key 为段路径签名）。
     * @param lengthMeters 段总长度（m；散热/热容按长度缩放）
     */
    public static ThermalModel thermalFor(String key, double lengthMeters) {
        double len = Math.max(lengthMeters, 1.0);
        return THERMAL.computeIfAbsent(key,
                k -> new ThermalModel(CONDUCTANCE_PER_M * len, HEAT_CAP_PER_M * len,
                        AMBIENT_K, MAX_K));
    }

    /** 兼容：无长度（读取/旧路径）→ 按 1m 参数创建（已存在则直接返回原模型） */
    public static ThermalModel thermalFor(String key) {
        return thermalFor(key, 1.0);
    }

    /** 只读查询（2026-08-24：不创建；温度计先查最热段用——避免创建污染 store） */
    public static ThermalModel getThermal(String key) {
        return key == null ? null : THERMAL.get(key);
    }

    /** 段消失（拆线）时清理 */
    public static void remove(String key) {
        if (key != null) THERMAL.remove(key);
    }

    /** 当前温度模型条目数（诊断用：>0 说明有段温度被推进） */
    public static int size() {
        return THERMAL.size();
    }

    /** 段消失清理（2026-08-23 用户：开路不重置——温度按散热持续降到室温）。
     *  只清理【已冷却到室温】的模型（防泄漏）；高温段保留 → {@link #coolAll}
     *  持续降温（剪线/开路 ≠ 温度归零）。 */
    public static void retainOnly(java.util.Set<String> activeKeys) {
        try {
            if (activeKeys == null) return;
            THERMAL.entrySet().removeIf(en ->
                    !activeKeys.contains(en.getKey())
                            && en.getValue().tempCelsius() < 25.5);
        } catch (Throwable ignored) {
        }
    }

    /** 全量降温（2026-08-23 用户：开路/无功率 → 按散热 0 功率推进 → 线性累加
     *  ΔT = (0 − G·(T−Tamb))/C·dt → 指数降到室温【持续计算，不重置】）。
     *  每 tick 调用；到室温的模型自动清理。 */
    public static void coolAll(double dt) {
        if (dt <= 0) return;
        try {
            for (Map.Entry<String, ThermalModel> en : THERMAL.entrySet()) {
                try {
                    ThermalModel t = en.getValue();
                    t.update(0, dt);
                    if (t.tempCelsius() < 25.5) {
                        THERMAL.remove(en.getKey(), t);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private WireThermalStore() {}
}
