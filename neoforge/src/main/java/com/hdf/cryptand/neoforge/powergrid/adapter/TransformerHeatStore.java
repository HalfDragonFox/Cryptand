/**
 * ===== 变压器相量发热存储 =====
 *
 * 时域求解被彻底禁用后，PowerGrid 原版变压器发热
 * （TransformerBlockEntity.tick(): primaryStray/mutualInductance 的 I²R）
 * 失效（时域电流为 0）→ 副边短路也不温升。
 *
 * 本类由 PhasorEngine 在每次相量求解后写入变压器损耗功率（W，平均功率）：
 *   - 初级铜损 |Vpa1 - Vx|² / Rp（漏感电阻）
 *   - 铁损   |Vx - Vpa2|² / Rcore（励磁电阻）
 * TransformerBlockEntityMixin 每 tick 读取并 applyTickPower → 温升。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TransformerHeatStore {

    /** 变压器状态（真实损耗 + 电流，供温度与声音） */
    private static final class Entry {
        double cuLoss;   // 铜损平均功率（W，EMA 平滑）
        double coreLoss; // 铁损平均功率（W，EMA 平滑）
        double iP;       // 初级电流峰值（A，EMA 平滑）
        double iS;       // 次级电流峰值（A，EMA 平滑）
        double rawIS;    // 次级电流【瞬时原始值】（不平滑，供声音快速停止）
        double freq;     // 频率（Hz）
    }

    /** 变压器方块位置 → 状态 */
    private static final Map<BlockPos, Entry> STATE = new ConcurrentHashMap<>();

    /** 变压器方块位置 → 温度模型（静态持久：跨网络重建保留温度与冷却状态） */
    private static final Map<BlockPos, ThermalModel> THERMAL = new ConcurrentHashMap<>();

    /** 低通滤波系数（每 ~10 tick 更新一次；alpha=0.2 → 时间常数 ~50 tick ≈ 2.5s） */
    private static final double ALPHA = 0.2;

    /**
     * 获取/创建变压器温度模型（静态持久，跨网络重建保留温度与冷却）。
     * 默认散热 4 W/K、热容 200 J/K（时间常数 τ=C/G=50s，~3 分钟稳定，
     * 快速趋稳后保持恒定）、环境 20°C、最高 200°C（魔法数字，更新即重算）。
     * 风扇冷却由调用方检测后 {@link ThermalModel#setCoolingMultiplier} 提高散热系数。
     */
    public static ThermalModel thermalFor(BlockPos pos) {
        return THERMAL.computeIfAbsent(pos, p -> new ThermalModel(4.0, 200.0, 298.15, 473.15));
    }

    /** 只读温度模型（不存在返回 null，不创建）——用于断开时自然冷却推进 */
    public static ThermalModel getThermal(BlockPos pos) {
        return pos == null ? null : THERMAL.get(pos);
    }

    /** 位置迁移（备选：物理化时迁移而非清理；当前 remapBlockPos 用 remove +
     *  组装器重新绑定，本方法保留备用） */
    public static void move(BlockPos oldPos, BlockPos newPos) {
        if (oldPos == null || newPos == null || oldPos.equals(newPos)) return;
        try {
            Entry st = STATE.remove(oldPos);
            if (st != null) STATE.putIfAbsent(newPos, st);
            ThermalModel th = THERMAL.remove(oldPos);
            if (th != null) THERMAL.putIfAbsent(newPos, th);
        } catch (Throwable ignored) {
        }
    }

    /** 全量降温（2026-08-23 用户：变压器空载/断开 → 按散热持续降到室温，不重置） */
    public static void coolAll(double dt) {
        if (dt <= 0) return;
        try {
            for (ThermalModel t : THERMAL.values()) {
                try { t.update(0, dt); } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private TransformerHeatStore() {}

    /** 相量求解后写入（服务端主线程）；EMA 平滑损耗/电流，消除瞬时波动 */
    public static void put(BlockPos pos, double cuLoss, double coreLoss,
                           double iP, double iS, double freq) {
        if (pos == null) return;
        if (cuLoss <= 0 && coreLoss <= 0 && iP <= 0 && iS <= 0) {
            // 真断电 → 移除（无衰减补丁：重建已改为"电路变化驱动"，
            // 功率 0 即真断电，不再瞬态归零）
            STATE.remove(pos);
            return;
        }
        Entry prev = STATE.get(pos);
        Entry ne = new Entry();
        if (prev != null) {
            ne.cuLoss = prev.cuLoss + ALPHA * (cuLoss - prev.cuLoss);
            ne.coreLoss = prev.coreLoss + ALPHA * (coreLoss - prev.coreLoss);
            ne.iP = prev.iP + ALPHA * (iP - prev.iP);
            ne.iS = prev.iS + ALPHA * (iS - prev.iS);
            ne.rawIS = iS; // 瞬时值（声音判定用：次级断开立即静音）
            ne.freq = freq; // 直接用（无频率滞回补丁）
        } else {
            ne.cuLoss = Math.max(cuLoss, 0);
            ne.coreLoss = Math.max(coreLoss, 0);
            ne.iP = Math.max(iP, 0);
            ne.iS = Math.max(iS, 0);
            ne.rawIS = Math.max(iS, 0);
            ne.freq = freq;
        }
        STATE.put(pos, ne);
    }

    /** 铜损平均功率（W）；无记录返回 0 */
    public static double getCuLoss(BlockPos pos) {
        Entry e = pos == null ? null : STATE.get(pos);
        return e == null ? 0 : e.cuLoss;
    }

    /** 铁损平均功率（W）；无记录返回 0 */
    public static double getCoreLoss(BlockPos pos) {
        Entry e = pos == null ? null : STATE.get(pos);
        return e == null ? 0 : e.coreLoss;
    }

    /** 初级电流峰值（A）；无记录返回 0 */
    public static double getCurrent(BlockPos pos) {
        Entry e = pos == null ? null : STATE.get(pos);
        return e == null ? 0 : e.iP;
    }

    /** 次级电流峰值（A）；无记录返回 0 */
    public static double getSecCurrent(BlockPos pos) {
        Entry e = pos == null ? null : STATE.get(pos);
        return e == null ? 0 : e.iS;
    }

    /** 次级电流【瞬时原始值】（A）——声音判定用：次级断开立即静音 */
    public static double getSecCurrentRaw(BlockPos pos) {
        Entry e = pos == null ? null : STATE.get(pos);
        return e == null ? 0 : e.rawIS;
    }

    /** 频率（Hz）；无记录返回 0 */
    public static double getFreq(BlockPos pos) {
        Entry e = pos == null ? null : STATE.get(pos);
        return e == null ? 0 : e.freq;
    }

    /** 方块被移除/卸载时清理（状态 + 温度全清：重新放置从环境温度开始） */
    public static void remove(BlockPos pos) {
        if (pos != null) {
            STATE.remove(pos);
            THERMAL.remove(pos);
        }
    }

    /** 断开/无回路 → 只清【电气状态】（静音/停止发热），温度保留（自然冷却，防突变）。
     *  温度由 resetComputation 以 0 功率推进冷却（保留热惯性，不瞬间归环境）。 */
    public static void resetElectrical(BlockPos pos) {
        if (pos != null) STATE.remove(pos);
    }

    /** 完全重置（状态 + 温度归环境）——仅用于方块真正移除/彻底冷启动 */
    public static void reset(BlockPos pos) {
        if (pos == null) return;
        STATE.remove(pos);
        ThermalModel th = THERMAL.get(pos);
        if (th != null) th.reset();
    }
}
