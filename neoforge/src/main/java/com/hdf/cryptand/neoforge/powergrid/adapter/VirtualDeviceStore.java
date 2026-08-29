package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟设备存储（2026-08-12 超长线路输电：未加载区块元件的持久参数快照）。
 * <p>
 * 设备 BE 每次成功建模时把 {@link VirtualDevice} 参数快照保存到这里（按
 * BlockPos）；构建时若该位置区块未加载（BE null）→ 查快照 → 用虚拟复合元件
 * 建模 → 未加载区块的元件仍纳入网络，超长线路输电不中断。
 * <p>
 * 静态持久：跨网络重建/区块卸载保留参数；区块加载后正常建模自动覆盖。
 * 方块被破坏时 {@link #remove} 清理。
 */
public final class VirtualDeviceStore {

    private static final Map<BlockPos, VirtualDevice> VIRTUALS = new ConcurrentHashMap<>();

    /** 取虚拟设备快照（无则 null） */
    public static VirtualDevice get(BlockPos pos) {
        if (pos == null) return null;
        return VIRTUALS.get(pos);
    }

    /** 保存/更新快照（设备成功建模时调用） */
    public static void put(BlockPos pos, VirtualDevice d) {
        if (pos == null || d == null) return;
        VIRTUALS.put(pos, d);
    }

    /** 清理（方块移除/卸载） */
    public static void remove(BlockPos pos) {
        if (pos != null) VIRTUALS.remove(pos);
    }

    /** 全部位置（副本，进世界一致性检测用） */
    public static java.util.Set<BlockPos> keys() {
        return new java.util.HashSet<>(VIRTUALS.keySet());
    }

    /** 全量快照（副本，存档缓存用） */
    public static java.util.Map<BlockPos, VirtualDevice> all() {
        return new java.util.HashMap<>(VIRTUALS);
    }

    /** 批量恢复（存档加载/进世界：未加载区块的设备参数立即可用） */
    public static void loadAll(java.util.Map<BlockPos, VirtualDevice> map) {
        if (map == null) return;
        VIRTUALS.putAll(map);
    }

    /** 只保留活跃位置（防泄漏） */
    public static void retainOnly(java.util.Set<BlockPos> active) {
        try {
            if (active == null) return;
            VIRTUALS.keySet().removeIf(p -> !active.contains(p));
        } catch (Throwable ignored) {
        }
    }

    public static int size() { return VIRTUALS.size(); }

    private VirtualDeviceStore() {}
}
