package com.hdf.cryptand.neoforge.railway.train;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列车受电弓滑触头缓存（2026-08-22 "全部实现自管" M3b）。
 * <p>
 * 主线程（PantographMovementBehaviour.tick 服务端分支）低频写列车触点点位：
 *   - trainKey = Train 对象进程内稳定标识（"T@hexIdentityHash"）
 *   - 触点 = 接触网段 holderA-holderB 上参数 t
 *   - 【低频】触点变化超过阈值才更新 → 自管图版本稳定（稳定检测通过才求解）
 * 后台（buildContextFromGraph）读：
 *   - 拆接触网段 → 滑触头节点 → 0.01Ω → 列车母线节点（trainNode）
 *   - 蓄电池支路（DcVoltageSource trainNode↔地）+ EnergyModel 储能状态（跨构建持久，
 *     由 computeEnergyUnified.syncCharge 推进 charge → 蓄电池端电压演化）
 */
public final class TrainTapCache {

    /** 列车母线额定电压（V）——蓄电池恒压近似（CEE accumulator 语义） */
    public static final double BATTERY_VOLTAGE = 400.0;
    /** 蓄电池内阻（Ω） */
    public static final double BATTERY_RESISTANCE = 0.1;
    /** 蓄电池能量容量（J，象征性） */
    public static final double BATTERY_CAPACITY = 1_000_000.0;
    /** 触头接触电阻（Ω，与静态受电弓一致） */
    public static final double CONTACT_RESISTANCE = 0.01;
    /** 仿真步长（s；与 Cryptand sim tick 一致，charge 推进用） */
    public static final double SIM_DT = 0.05;
    /** 触点参数 t 更新阈值（低于则不更新 → 图稳定） */
    public static final double TAP_EPSILON = 0.02;

    /** 列车受电弓触点（active=false → 未触达/收弓 → 移除） */
    public record TrainTap(String trainKey, BlockPos holderA, BlockPos holderB,
                           float t, boolean active) {
        public boolean sameEdge(TrainTap o) {
            return o != null && holderA != null && holderB != null
                    && o.holderA != null && o.holderB != null
                    && ((holderA.equals(o.holderA) && holderB.equals(o.holderB))
                    || (holderA.equals(o.holderB) && holderB.equals(o.holderA)));
        }
    }

    private static final ConcurrentHashMap<String, TrainTap> TAPS = new ConcurrentHashMap<>();
    /** 列车蓄电池储能状态（trainKey → EnergyModel；跨构建持久，卸载 clear） */
    private static final ConcurrentHashMap<String, EnergyModel> BATTERIES =
            new ConcurrentHashMap<>();

    /** 主线程写（低频：调用方保证变化超阈值才调，否则沿用旧值） */
    public static void setTap(TrainTap tap) {
        if (tap == null || tap.trainKey() == null) return;
        if (!tap.active() || tap.holderA() == null || tap.holderB() == null) {
            TAPS.remove(tap.trainKey());
            return;
        }
        TAPS.put(tap.trainKey(), tap);
    }

    /** 当前触点（未触达 null） */
    public static TrainTap getTap(String trainKey) {
        return trainKey == null ? null : TAPS.get(trainKey);
    }

    /** 全部活动列车触点（后台构建段拆分用） */
    public static Collection<TrainTap> activeTaps() {
        return TAPS.values();
    }

    /** 全部活动列车触点（键=列车 key） */
    public static Map<String, TrainTap> activeEntries() {
        return new HashMap<>(TAPS);
    }

    /** 取列车蓄电池状态（create-on-demand，batteryMode 恒压+内阻+容量） */
    public static EnergyModel battery(String trainKey) {
        if (trainKey == null) return null;
        return BATTERIES.computeIfAbsent(trainKey, k -> {
            EnergyModel m = new EnergyModel(1e-3);
            m.batteryMode = true;
            m.batteryVoltage = BATTERY_VOLTAGE;
            m.internalResistance = BATTERY_RESISTANCE;
            m.capacity = BATTERY_CAPACITY;
            m.charge = 0;
            m.syncDyn();
            return m;
        });
    }

    /** 蓄电池状态（无则 null；读展示用） */
    public static EnergyModel batteryIfPresent(String trainKey) {
        return trainKey == null ? null : BATTERIES.get(trainKey);
    }

    public static int activeCount() {
        return TAPS.size();
    }

    /** 清除全部（世界卸载/重置） */
    public static void clear() {
        TAPS.clear();
        BATTERIES.clear();
    }

    private TrainTapCache() {
    }
}