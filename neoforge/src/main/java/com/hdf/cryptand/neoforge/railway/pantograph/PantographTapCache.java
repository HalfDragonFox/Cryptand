package com.hdf.cryptand.neoforge.railway.pantograph;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受电弓滑触头缓存（2026-08-22 "CEE 全自管电路"）。
 * <p>
 * 主线程 → 异步线程 的消息通道（同 DeviceParamCache 模式）：
 *   - 主线程（受电弓 BE.tick 服务端分支）每 tick 计算滑触头所在接触网边 + 参数 t
 *     → 写本缓存（这是"发消息"）
 *   - 异步求解线程（buildContextFromGraph）只读本缓存（零 level 依赖）→ 把接触网
 *     段在 t 处动态拆成两半 + 受电弓 0.01Ω 电阻桥接底座↔触点
 * <p>
 * Thread-safe：ConcurrentHashMap；受电弓 pos → TapRecord（null 或 connecting=false
 * 表示未触达/收弓 → 受电弓开路，不接入电网）。
 */
public final class PantographTapCache {

    /** 受电弓滑触头记录（触点 = 接触网边 holderA-holderB 上参数 t 处；
     *  pantographPos = 该受电弓方块位置，供构建器把触头接到底座端子） */
    public record PantographTap(BlockPos pantographPos, BlockPos holderA,
                                BlockPos holderB, float t, boolean connecting) {
        public static PantographTap none(BlockPos pantographPos) {
            return new PantographTap(pantographPos, BlockPos.ZERO, BlockPos.ZERO, 0f, false);
        }
    }

    private static final ConcurrentHashMap<BlockPos, PantographTap> TAPS =
            new ConcurrentHashMap<>();

    /** 主线程写：受电弓 pos → 触点（传 null / connecting=false = 未触达/收弓） */
    public static void set(BlockPos pantographPos, PantographTap tap) {
        if (pantographPos == null) return;
        if (tap == null || !tap.connecting()) {
            TAPS.remove(pantographPos);
        } else {
            TAPS.put(pantographPos.immutable(), tap);
        }
    }

    /** 后台读：受电弓 pos → 触点（null = 未触达/收弓） */
    public static PantographTap get(BlockPos pantographPos) {
        return pantographPos == null ? null : TAPS.get(pantographPos);
    }

    /** 全部活动触点（后台构建段拆分用） */
    public static Collection<PantographTap> activeTaps() {
        return TAPS.values();
    }

    /** 全部活动触点（键=受电弓方块；后台构建查触点节点映射用） */
    public static java.util.Map<BlockPos, PantographTap> activeEntries() {
        return new java.util.HashMap<>(TAPS);
    }

    /** 是否有任何活动触点 */
    public static boolean anyActive() {
        return !TAPS.isEmpty();
    }

    /** 清除全部（世界卸载/重置） */
    public static void clear() {
        TAPS.clear();
    }

    private PantographTapCache() {
    }
}