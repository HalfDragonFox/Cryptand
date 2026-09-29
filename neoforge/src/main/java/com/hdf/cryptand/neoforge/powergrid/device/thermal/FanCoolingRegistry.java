/**
 * ===== 风扇额外散热注册表（2026-09-12 用户设计：按风扇登记 + 精确撤销 + 多风扇叠加） =====
 *
 * 用户设计原话：
 *   "放下风扇后收集被散热的BE并发送额外散热值，然后每次计算时使用此值，
 *    如果风扇被破坏则减去此风扇附加的额外散热值（方便多个风扇叠加）"
 *
 * 语义：
 *   - 每台风扇（key = 风扇方块位置）持有一份【贡献表】：它吹到的每个位置 → 额外散热贡献 k
 *   - 设备位置上的总贡献 = 所有风扇贡献【线性累加】——多台风扇叠加，一台的增删不影响其他
 *   - 风扇被破坏 → 只撤销【这一台】的贡献（其余风扇原样保留，这是旧实现做不到的）
 *   - 温度计算每次读【累积值】：G_eff = conductance × (coolingMultiplier + k)
 *     （ThermalModel.setExtraCoolingFactor）
 *
 * 线程约定（与核心架构铁律一致：主线程同步、核心计算、核心不碰 Level/BE）：
 *   - 写（setFan / removeFan）：主线程（风扇 tick 指纹变化 / BE 破坏钩子）
 *   - 读（extraFactor）：后台引擎线程（温度推进）与主线程（温度模型创建时）
 *   - 存储为 ConcurrentHashMap；应用时只改 ThermalModel 的 volatile 字段
 *
 * 与旧实现（每 tick 全量重扫 + 曲线合并 + 每 tick 清空 + apply 反射写原版）的区别：
 *   - 采集只在【风扇登记指纹变化】时发生（FanCoolingMixin 指纹比对），不再每 tick 重扫
 *   - 撤销按风扇精确减除，不再"先清空再重建"（旧 apply 已无调用者 = 冷却整条链失效）
 *   - 不再反射写原版 ThermalBehaviour.totalCoolingFactorMultiplier：Cryptand 温度模型
 *     已接管设备温度，原版那条路径的温度不参与引擎计算
 */

package com.hdf.cryptand.neoforge.powergrid.device.thermal;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.neoforge.powergrid.engine.AdapterDiag;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerHeatStore;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FanCoolingRegistry {

    /** 风扇位置 → （被吹到的位置 → 该风扇贡献的额外散热 k） */
    private static final Map<BlockPos, Map<BlockPos, Double>> BY_FAN = new ConcurrentHashMap<>();

    /** 位置 → 全风扇累积额外散热 k（温度计算读这个） */
    private static final Map<BlockPos, Double> EXTRA = new ConcurrentHashMap<>();

    /** 风扇位置 → 最后一次 tick 的毫秒时间戳（心跳；2026-09-12 用户：
     *  "只要风扇停止运行就停止额外散热"——区块卸载/卡死/未走 setRemoved 的移除
     *  都会让心跳停摆，看门狗据此自动撤销该风扇的贡献）。 */
    private static final Map<BlockPos, Long> LAST_SEEN = new ConcurrentHashMap<>();

    /** 心跳超时（ms）：超过此时长没 tick → 判为停止运行 */
    public static final long STALE_MS = 2000L;

    /** 看门狗自身节流（ms） */
    private static final long SWEEP_INTERVAL_MS = 1000L;
    private static volatile long SWEEP_LAST;

    /** 累加下限：放弃小于该值的残差（浮点噪声，防条目永不归零） */
    private static final double EPS = 1e-9;

    private FanCoolingRegistry() {
    }

    /**
     * 【风扇登记】用一份新贡献表替换该风扇的旧贡献（幂等，可反复调用）。
     * 先按旧表撤销差值、再叠加新表——因此"放置风扇后收集一次"与
     * "范围/转速变化后重扫"是同一个入口，不需要额外的清除步骤。
     *
     * @param fanPos   风扇方块位置（风扇身份）
     * @param contribs 被吹到的位置 → 额外散热贡献 k（k ≤ 0 的项忽略）
     */
    public static void setFan(BlockPos fanPos, Map<BlockPos, Double> contribs) {
        if (fanPos == null) return;
        Map<BlockPos, Double> next = new HashMap<>();
        if (contribs != null) {
            for (Map.Entry<BlockPos, Double> e : contribs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > EPS) {
                    next.put(e.getKey(), e.getValue());
                }
            }
        }
        Map<BlockPos, Double> prev = BY_FAN.put(fanPos, next);
        if (next.isEmpty() && (prev == null || prev.isEmpty())) return;
        diag("register", fanPos, next);

        Set<BlockPos> touched = new HashSet<>();
        if (prev != null) touched.addAll(prev.keySet());
        touched.addAll(next.keySet());

        for (BlockPos p : touched) {
            double oldV = prev == null ? 0.0 : prev.getOrDefault(p, 0.0);
            double newV = next.getOrDefault(p, 0.0);
            double delta = newV - oldV;
            if (delta > -EPS && delta < EPS) continue;
            addExtra(p, delta);
            applyTo(p);
        }
    }

    /**
     * 【风扇被破坏】只撤销这一台风扇的贡献（其余风扇原样保留）。
     * 由 BlockEntity.setRemoved 钩子（ElectricBlockEntityLifecycleMixin）调用。
     */
    public static void removeFan(BlockPos fanPos) {
        if (fanPos == null) return;
        LAST_SEEN.remove(fanPos);
        Map<BlockPos, Double> prev = BY_FAN.remove(fanPos);
        if (prev == null || prev.isEmpty()) return;
        diag("remove", fanPos, prev);
        for (Map.Entry<BlockPos, Double> e : prev.entrySet()) {
            BlockPos p = e.getKey();
            double v = e.getValue();
            if (v <= EPS) continue;
            addExtra(p, -v);
            applyTo(p);
        }
    }

    /**
     * 【心跳】风扇每次 tick 调用（服务端）。只要风扇还在运行，它的贡献就有效；
     * 心跳停摆 = 风扇停止运行（区块卸载、被移除但没走 setRemoved、卡死等）→
     * {@link #sweepStale()} 会自动撤销它的贡献。
     */
    public static void touch(BlockPos fanPos) {
        if (fanPos == null) return;
        LAST_SEEN.put(fanPos, System.currentTimeMillis());
    }

    /**
     * 【看门狗】主线程定期调用（ServerTickEvent.Post，内部节流 1s）：
     * 心跳超过 {@link #STALE_MS} 没更新的风扇 → 视作停止运行 → 撤销其贡献
     * （其余风扇不受影响）。这覆盖区块卸载等【不触发 setRemoved】的路径。
     */
    public static void sweepStale() {
        long now = System.currentTimeMillis();
        if (now - SWEEP_LAST < SWEEP_INTERVAL_MS) return;
        SWEEP_LAST = now;
        try {
            for (BlockPos fanPos : BY_FAN.keySet()) {
                Long seen = LAST_SEEN.get(fanPos);
                if (seen != null && now - seen <= STALE_MS) continue;
                // 从没打过心跳（刚登记还没 tick）也按超时处理：下一个 tick 会重新登记
                removeFan(fanPos);
                diagStale(fanPos);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 看门狗撤销诊断（节流 2s） */
    private static void diagStale(BlockPos fanPos) {
        try {
            if (!AdapterDiag.gate("fanCool:stale:" + fanPos, 2000)) return;
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[FanCool] stale-remove fan={} (停止运行 >{}ms) fans={} cooled={}",
                    fanPos, STALE_MS, BY_FAN.size(), EXTRA.size());
        } catch (Throwable ignored) {
        }
    }

    /** 累积额外散热贡献 k（0 = 没被吹）。后台温度计算/主线程创建模型时读。 */
    public static double extraFactor(BlockPos pos) {
        if (pos == null) return 0.0;
        Double v = EXTRA.get(pos);
        return v == null ? 0.0 : v;
    }

    /** 已登记的风扇数量（诊断） */
    public static int fanCount() {
        return BY_FAN.size();
    }

    /** 已登记的位置条目数（诊断） */
    public static int cooledPosCount() {
        return EXTRA.size();
    }

    /** 清空全部登记（世界卸载/测试用） */
    public static void clear() {
        BY_FAN.clear();
        EXTRA.clear();
        LAST_SEEN.clear();
    }

    // ================= 内部 =================

    /** 登记/撤销诊断（节流 2s；[FanCool] 行用于游戏内确认冷却是否真的登记上） */
    private static void diag(String what, BlockPos fanPos, Map<BlockPos, Double> contribs) {
        try {
            if (!AdapterDiag.gate("fanCool:" + what + ":" + fanPos, 2000)) return;
            double maxK = 0;
            double sumK = 0;
            for (double v : contribs.values()) {
                maxK = Math.max(maxK, v);
                sumK += v;
            }
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[FanCool] {} fan={} cells={} kMax={} kSum={} fans={} cooled={}",
                    what, fanPos, contribs.size(),
                    String.format("%.2f", maxK), String.format("%.2f", sumK),
                    BY_FAN.size(), EXTRA.size());
        } catch (Throwable ignored) {
        }
    }

    /** EXTRA 累加（结果 ≤ EPS 时移除条目，防浮点残差堆积） */
    private static void addExtra(BlockPos pos, double delta) {
        EXTRA.merge(pos, delta, (a, b) -> {
            double s = a + b;
            return s <= EPS ? null : s;
        });
    }

    /** 把该位置当前的累积值应用到已存在的温度模型（不存在则跳过——创建时会读） */
    private static void applyTo(BlockPos pos) {
        if (pos == null) return;
        double k = extraFactor(pos);
        try {
            ThermalModel dev = DeviceThermalStore.peek(pos);
            if (dev != null) dev.setExtraCoolingFactor(k);
        } catch (Throwable ignored) {
        }
        try {
            ThermalModel tf = TransformerHeatStore.getThermal(pos);
            if (tf != null) tf.setExtraCoolingFactor(k);
        } catch (Throwable ignored) {
        }
    }

}
