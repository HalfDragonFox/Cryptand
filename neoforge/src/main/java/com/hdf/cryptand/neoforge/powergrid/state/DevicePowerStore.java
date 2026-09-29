package com.hdf.cryptand.neoforge.powergrid.state;

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

    /** 待上报位置（2026-09-11 用户：读取改增量、变化即上报）——后台写入且【值有变】才标记；
     *  主线程 drainChanged() 消费后清空。 */
    private static final java.util.Set<BlockPos> DIRTY =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 后台/主线程写（覆盖最新）：值变化才标记上报（同值重复写零开销）。 */
    public static void put(BlockPos pos, double powerW) {
        if (pos == null || Double.isNaN(powerW)) return;
        BlockPos p = pos.immutable();
        Double old = POWER.put(p, powerW);
        if (old == null || old.doubleValue() != powerW) DIRTY.add(p); // ★ 变化即上报
    }

    /** 【主线程消费端】取走【变化过】的条目（无变化 → 空列表 → 调用方零开销）。 */
    public static java.util.List<Object[]> drainChanged() {
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        try {
            java.util.List<BlockPos> keys = new java.util.ArrayList<>(DIRTY);
            DIRTY.removeAll(keys);
            for (BlockPos p : keys) {
                Double v = POWER.get(p);
                if (v != null) out.add(new Object[]{p, v});
            }
        } catch (Throwable ignored) {
        }
        return out;
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
        if (pos != null) {
            POWER.remove(pos);
            DIRTY.remove(pos);
        }
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
