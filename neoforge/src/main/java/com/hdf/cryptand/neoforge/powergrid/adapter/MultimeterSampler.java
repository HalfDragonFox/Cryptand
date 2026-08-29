/**
 * ===== 万用表 1 秒窗口采样器（客户端） =====
 *
 * 对同一测量目标累积最近 1 秒（20 tick）内的样本，计算：
 *   平均(average) / 最大(max) / 最小(min) / 有效值(rms)。
 *
 * 语义（按用户要求）：
 *   - 统计时间 = 1 秒：窗口恰好收集 20 个 tick 的样本
 *   - 数据更新 = 每 1 秒一次：窗口完成时才生成新结果并更新显示，
 *     窗口进行期间保持上一次结果不变
 *
 * 关键实现点：
 *   - getText 是【每渲染帧】调用（60fps），同一 tick 会被调用多次。
 *     必须按 gameTime 去重（每 tick 只采样一次），否则窗口在 20 次
 *     "调用"后翻转（实际只有约 0.33 秒），统计时间就错了。
 *
 * 测量目标变化（切换接线点/导线/模式）时自动重置窗口。
 * 首个窗口未完成时返回 null（调用方回退到瞬时值显示）。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import java.util.HashMap;
import java.util.Map;

public final class MultimeterSampler {

    /** 采样窗口长度：1 秒 = 20 tick */
    private static final long WINDOW_TICKS = 20L;

    private static final Map<String, Accumulator> ACCUMULATORS = new HashMap<>();
    private static String activeKey;
    private static int dbgSettleCounter;

    private MultimeterSampler() {}

    /**
     * 记录一个样本并返回最近一个完整窗口的统计结果。
     *
     * @param key      测量目标标识（切换目标自动重置）
     * @param gameTime level.getGameTime()（客户端 tick）
     * @param value    本次瞬时测量值
     * @param ac       是否交流（频率 > 0）：相量回写值是恒定 RMS，窗口内样本全相同 →
     *                 若按瞬时统计则 max=min=avg=rms 全相等（无意义）。AC 语义：
     *                 最大/最小 = 峰值 ±√2×RMS（正弦波），平均 = 0（整周期直流分量），
     *                 有效值 = RMS。峰值振幅取窗口内最大样本 RMS（保留负载波动）。
     * @return 最近完整 1 秒窗口的统计；首个窗口尚未完成时为 null
     */
    public static Result sample(String key, long gameTime, double value, boolean ac) {
        if (activeKey == null || !activeKey.equals(key)) {
            ACCUMULATORS.clear();
            activeKey = key;
        }
        Accumulator acc = ACCUMULATORS.computeIfAbsent(key, k -> new Accumulator());
        if (acc.startTick == Long.MIN_VALUE) {
            acc.startTick = gameTime;
        }
        // 同一 tick 多次调用（渲染帧率 > tick 率）只采样一次，保证 1 秒窗口 = 20 个 tick
        if (acc.lastSampleTick == gameTime) {
            return acc.result;
        }
        acc.lastSampleTick = gameTime;

        // 当前 tick 已收集满 1 秒（20 个样本）→ 结算上一窗口，再以当前样本开启新窗口
        if (acc.count > 0 && gameTime - acc.startTick >= WINDOW_TICKS) {
            double rms = Math.sqrt(acc.sumSq / acc.count);
            double avg = acc.sum / acc.count;
            Result settled;
            if (ac) {
                // 交流正弦波：峰值 = √2×RMS（取窗口最大样本的峰值，保留波动）
                double peak = acc.max * Math.sqrt(2.0);
                settled = new Result(0.0, -peak, peak, rms);
            } else {
                settled = new Result(avg, acc.min, acc.max, rms);
            }
            acc.result = settled;
            acc.startTick = gameTime;
            acc.sum = value;
            acc.sumSq = value * value;
            acc.count = 1;
            acc.min = value;
            acc.max = value;
            // 诊断：窗口结算时打印统计（节流）
            if (++dbgSettleCounter % 10 == 1) {
                try {
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[MeterWin] key={} n={} avg={} min={} max={} rms={} lastValue={}",
                            key, acc.count - 1, String.format("%.2f", avg),
                            String.format("%.2f", acc.result.min()),
                            String.format("%.2f", acc.result.max()),
                            String.format("%.2f", acc.result.rms()),
                            String.format("%.2f", value));
                } catch (Throwable ignored) {
                }
            }
            return acc.result;
        }

        acc.sum += value;
        acc.sumSq += value * value;
        acc.count++;
        acc.min = Math.min(acc.min, value);
        acc.max = Math.max(acc.max, value);
        return acc.result;
    }

    /** 1 秒窗口统计结果 */
    public record Result(double average, double min, double max, double rms) {}

    private static final class Accumulator {
        long startTick = Long.MIN_VALUE;
        long lastSampleTick = Long.MIN_VALUE;
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        Result result;
    }
}
