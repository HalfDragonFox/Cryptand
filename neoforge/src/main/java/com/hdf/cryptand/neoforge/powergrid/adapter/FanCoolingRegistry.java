/**
 * ===== 风扇/鼓风机冷却注册表（2026-08-20 用户要求） =====
 *
 * 统一管理风机冷却：
 *   1. 【多方块整体冷却】：风扇吹到变压器/线圈等多方块设备的任意子方块 →
 *      映射到主方块 → 整个设备（主方块温度模型）整体降温。
 *   2. 【多面曲线叠加】：一个方块多个面被多个风扇/多方向吹到 → 冷却效率
 *      按曲线叠加（第 1 个 1 倍，第 2 个 0.7 倍，第 3 个 0.49 倍……按现实
 *      冷却效率不会成倍叠加）。
 *
 * 生命周期（主线程，每 tick）：
 *   - CryptandTopologyManager.tick 开头 beginTick()（清空上轮贡献）
 *   - FanCoolingMixin（风扇 tick）把每个吹到的方块贡献 add(pos, strength)
 *   - PhasorWriteback.processPost 末尾 apply()：曲线合并 → 应用到温度模型
 *     （DeviceThermalStore / TransformerHeatStore setCoolingMultiplier），
 *     并重置上轮冷却但本轮未吹到的方块（防残留）。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FanCoolingRegistry {

    /** 多面叠加衰减系数：第 k 个面冷却贡献 × decay^(k-1)（默认 0.7） */
    private static final double DECAY = 0.7;

    /** 本轮冷却贡献（主方块 pos → 各方向冷却强度） */
    private static final Map<BlockPos, List<Float>> CONTRIB = new HashMap<>();

    /** 上轮应用过冷却的方块（用于重置未再被吹的） */
    private static final Set<BlockPos> LAST_AFFECTED = new HashSet<>();

    private FanCoolingRegistry() {}

    /** 每 tick 开头：清空本轮贡献（主线程调用） */
    public static void beginTick() {
        CONTRIB.clear();
    }

    /** 风扇吹到某方块：加入一个冷却方向贡献（strength = 该方向冷却强度）。
     *  调用方负责多方块映射（PART → 主方块 pos）。 */
    public static void add(BlockPos pos, float strength) {
        if (pos == null || strength <= 0) return;
        CONTRIB.computeIfAbsent(pos, k -> new ArrayList<>()).add(strength);
    }

    /** 曲线合并：第 k 个面 × decay^(k-1)（按强度从大到小），返回冷却倍率（≥1） */
    public static double multiplier(BlockPos pos) {
        List<Float> list = CONTRIB.get(pos);
        if (list == null || list.isEmpty()) return 1.0;
        // 强度从大到小（最强冷却面优先满倍率）
        list.sort((a, b) -> Float.compare(b, a));
        double total = 0;
        int i = 0;
        for (float s : list) {
            total += s * Math.pow(DECAY, i++);
        }
        return Math.max(1.0, 1.0 + total);
    }

    /**
     * 每 tick 末尾：汇总所有风扇贡献 → 曲线合并 → 应用到温度模型（Cryptand
     *  DeviceThermalStore + TransformerHeatStore）；上轮冷却过但本轮未吹到的
     *  方块重置为 1.0（防残留）。原版 ThermalBehaviour 由本方法反射设置
     *  totalCoolingFactorMultiplier（统一曲线，替代原版 addCoolingMultiplier 成倍累加）。
     */
    public static void apply(Level level) {
        try {
            Set<BlockPos> current = new HashSet<>(CONTRIB.keySet());
            Set<BlockPos> all = new HashSet<>(current);
            all.addAll(LAST_AFFECTED);
            for (BlockPos p : all) {
                double m = current.contains(p) ? multiplier(p) : 1.0;
                // Cryptand 设备温度模型
                if (DeviceThermalStore.contains(p)) {
                    DeviceThermalStore.thermalFor(p).setCoolingMultiplier(m);
                }
                // Cryptand 变压器温度模型
                com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel tf =
                        TransformerHeatStore.getThermal(p);
                if (tf != null) tf.setCoolingMultiplier(m);
                // 原版 ThermalBehaviour（反射设置 totalCoolingFactorMultiplier，
                // 统一曲线叠加——原版 addCoolingMultiplier 是成倍累加，不符用户要求）
                if (level != null && level.getBlockEntity(p) != null) {
                    try {
                        Object tbh = reflectThermalBehaviour(level.getBlockEntity(p));
                        if (tbh != null) {
                            java.lang.reflect.Field cf =
                                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour.class
                                            .getDeclaredField("totalCoolingFactorMultiplier");
                            cf.setAccessible(true);
                            cf.setFloat(tbh, (float) m);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            LAST_AFFECTED.clear();
            LAST_AFFECTED.addAll(current);
            // 清空本轮贡献（供下一轮 BE tick 的风扇重新加入；时序上 apply 在
            // preTick 末尾、风扇 BE tick 在 preTick 之后 → 冷却延迟 1 tick 可接受）
            CONTRIB.clear();
        } catch (Throwable ignored) {
        }
    }

    /** 反射读 BE 的 thermalBehaviour 字段（PowerGrid/Create） */
    private static Object reflectThermalBehaviour(Object be) {
        Class<?> c = be.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("thermalBehaviour");
                f.setAccessible(true);
                return f.get(be);
            } catch (java.lang.NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
