package com.hdf.cryptand.neoforge.powergrid.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电容充电状态持久存储（2026-08-21 修复"DC 电容测量恒 163A"）。
 * <p>
 * 电容 vPrev（充电状态）必须跨【网络重建/测量构建】保留——否则每次构建新
 * Network（solveBlocks 测量路径每次新建；roundFromGraph 缓存 miss 时重建）
 * 电容 vPrev=0 → 永远停在第一拍充电电流（10V 电容短路级 163A）。
 * <p>
 * key = 电容复合模型 compositeKey（"C"+BlockPos，构建时稳定）→ vPrev。
 * 生命周期：
 *   - PhasorNetworkBuilder CAPACITOR 分支构建时恢复（setCapVPrev）
 *   - PhasorEngine solveAll/solveBlocks 求解后保存（saveCapacitorStates）
 */
public final class CapacitorStateStore {

    private static final Map<String, Double> V_PREV = new ConcurrentHashMap<>();

    /** 恢复（无记录 → 0 = 未充电） */
    public static double get(String key) {
        if (key == null) return 0;
        Double v = V_PREV.get(key);
        return v == null ? 0 : v;
    }

    /** 保存 */
    public static void put(String key, double vPrev) {
        if (key == null) return;
        if (!Double.isFinite(vPrev)) return;
        V_PREV.put(key, vPrev);
    }

    /** 方块移除/网络清理时移除（防泄漏） */
    public static void remove(String key) {
        if (key != null) V_PREV.remove(key);
    }

    /** 条目数（诊断） */
    public static int size() {
        return V_PREV.size();
    }

    private CapacitorStateStore() {}
}
