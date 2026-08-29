package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备温度模型存储（静态持久：跨网络重建保留温度与冷却状态）。
 * <p>
 * 实际设备（电机/加热器/换向器等）通过【包含】{@link ThermalModel} 做温度模拟：
 * 每求解步用损耗功率推进温度，被鼓风机吹时提高散热系数（散热与发热同时算，
 * 解析解无条件稳定不跳变）。
 * <p>
 * 默认散热 4 W/K、热容 200 J/K（时间常数 τ=C/G=50s，快速趋稳后保持恒定）、
 * 环境 25°C、最高 200°C（2026-08-19 用户要求统一 25°C 室温；魔法数字，更新参数
 * 后重新组合即可重算）。
 */
public final class DeviceThermalStore {

    private static final Map<BlockPos, ThermalModel> THERMAL = new ConcurrentHashMap<>();

    /** 原版直流(DC)设备位置（2026-08-22）：灯/风扇/铃/加热器等只支持 DC，
     *  网络频率超 dcDeviceMaxFrequencyHz → 温度指数型惩罚。 */
    private static final java.util.Set<BlockPos> DC_ONLY = ConcurrentHashMap.newKeySet();

    /** 标记设备为原版直流(DC)设备（组装器 dcOnly() 时由构建方调用；幂等） */
    public static void markDcOnly(BlockPos pos, boolean dcOnly) {
        if (pos == null) return;
        if (dcOnly) DC_ONLY.add(pos); else DC_ONLY.remove(pos);
    }

    /** 该位置是否原版直流(DC)设备（超频温度惩罚用） */
    public static boolean isDcOnly(BlockPos pos) {
        return pos != null && DC_ONLY.contains(pos);
    }

    /** 获取/创建设备温度模型（跨网络重建持久） */
    public static ThermalModel thermalFor(BlockPos pos) {
        return THERMAL.computeIfAbsent(pos, p -> new ThermalModel(4.0, 200.0, 298.15, 473.15));
    }

    /** 高耐温设备温度模型（加热器等【发热设备】：设计工作温度高，maxTemp 400°C
     *  = 673.15K——避免短路瞬态 200~300°C 就被误杀，真正过流到 400°C 才烧毁）。 */
    public static ThermalModel thermalForHighTemp(BlockPos pos) {
        return THERMAL.computeIfAbsent(pos, p -> new ThermalModel(4.0, 200.0, 298.15, 673.15));
    }

    /** 方块被移除/卸载时清理 */
    public static void remove(BlockPos pos) {
        if (pos != null) {
            THERMAL.remove(pos);
            DC_ONLY.remove(pos);
        }
    }

    /** 该位置是否已有温度模型（热扩散只向已有温度模型的元件传导） */
    public static boolean contains(BlockPos pos) {
        return pos != null && THERMAL.containsKey(pos);
    }

    /** 当前温度模型条目数（诊断用：>0 说明有设备温度被推进） */
    public static int size() {
        return THERMAL.size();
    }

    /** 全部位置（副本，进世界一致性检测用） */
    public static java.util.Set<BlockPos> keys() {
        return new java.util.HashSet<>(THERMAL.keySet());
    }

    /** 全量降温（2026-08-23 用户：设备无功率/开路 → 按散热持续降到室温，不重置；
     *  PhasorEngine.coolAllTemperatures 每 tick 调用）。到室温的条目自动清理防泄漏。 */
    public static void coolAll(double dt) {
        if (dt <= 0) return;
        try {
            for (java.util.Map.Entry<BlockPos, ThermalModel> en : THERMAL.entrySet()) {
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

    /** 位置迁移（物理化 remap：DeviceThermalStore key 随坐标迁移） */
    public static void move(BlockPos oldPos, BlockPos newPos) {
        if (oldPos == null || newPos == null || oldPos.equals(newPos)) return;
        try {
            ThermalModel th = THERMAL.remove(oldPos);
            if (th != null) THERMAL.putIfAbsent(newPos, th);
            if (DC_ONLY.remove(oldPos)) DC_ONLY.add(newPos);
        } catch (Throwable ignored) {
        }
    }

    private DeviceThermalStore() {}
}
