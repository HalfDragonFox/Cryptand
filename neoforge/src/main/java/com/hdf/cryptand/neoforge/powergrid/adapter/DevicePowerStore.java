package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备计算功率存储（2026-08-24）：引擎（后台推进）每轮算出的设备损耗/输出功率
 * → 主线程 {@code processPost} 读 → 经 {@code BeBridge} 消息发送到对应 BE。
 * 线程安全（后台写 / 主线程读）。
 */
public final class DevicePowerStore {

    private static final ConcurrentHashMap<BlockPos, Double> POWER =
            new ConcurrentHashMap<>();

    private DevicePowerStore() {
    }

    /** 后台/主线程写（覆盖最新） */
    public static void put(BlockPos pos, double powerW) {
        if (pos != null && !Double.isNaN(powerW)) {
            POWER.put(pos.immutable(), powerW);
        }
    }

    /** 主线程读（无 → NaN） */
    public static double get(BlockPos pos) {
        Double v = pos == null ? null : POWER.get(pos);
        return v == null ? Double.NaN : v;
    }

    public static boolean contains(BlockPos pos) {
        return pos != null && POWER.containsKey(pos);
    }

    public static void remove(BlockPos pos) {
        if (pos != null) POWER.remove(pos);
    }

    /** 快照（主线程遍历发送用） */
    public static java.util.List<Object[]> snapshot() {
        java.util.List<Object[]> out = new java.util.ArrayList<>(POWER.size());
        for (var e : POWER.entrySet()) {
            out.add(new Object[]{e.getKey(), e.getValue()});
        }
        return out;
    }

    public static void clearAll() {
        POWER.clear();
    }
}
