package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ===== 适配层统一诊断出口 =====
 *
 * <p>三大适配类（{@code PhasorNetworkBuilder} / {@code PhasorEngine} /
 * {@code PhasorPipeline}）过去各自维护散落的 {@code xxxDbgLast} 节流字段与
 * 去重集合（20+ 个），既污染职责，又让"节流"语义各处不一。这里把
 * 「节流 / 去重 / 计数」收敛成四个语义明确的入口：
 *
 * <ul>
 *   <li>{@link #pos}  按方块位置节流（每 {@link #POS_WINDOW_MS} 毫秒每位置一条）</li>
 *   <li>{@link #gate} 按命名窗口节流（窗口内首次调用放行）</li>
 *   <li>{@link #once} 按 key 去重（进程内首次放行）</li>
 *   <li>{@link #under} / {@link #bump} 计数上限（如前 N 次）</li>
 * </ul>
 *
 * <p>线程安全：全部基于 {@link ConcurrentHashMap} 与原子计数，调度线程与
 * 主线程可共用；日志失败一律吞掉，绝不让诊断拖垮仿真链路。
 */
public final class AdapterDiag {

    private AdapterDiag() {}

    /** 位置节流窗口：每位置每 5 秒最多一条。 */
    static final long POS_WINDOW_MS = 5000L;

    private static final Map<BlockPos, Long> POS_LAST = new ConcurrentHashMap<>();
    private static final Map<String, Long> GATE_LAST = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();

    /** 按位置节流输出 info 日志（原文 {@code PhasorNetworkBuilder.dbgLog}）。 */
    public static void pos(BlockPos pos, String fmt, Object... args) {
        if (pos == null) {
            log(fmt, args);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = POS_LAST.get(pos);
        if (last != null && now - last < POS_WINDOW_MS) return;
        POS_LAST.put(pos, now);
        log(fmt, args);
    }

    /** 命名时间窗节流门：窗口内首次调用返回 true，其余返回 false。 */
    public static boolean gate(String key, long windowMs) {
        long now = System.currentTimeMillis();
        Long last = GATE_LAST.get(key);
        if (last != null && now - last < windowMs) return false;
        GATE_LAST.put(key, now);
        return true;
    }

    /** 按 key 去重：首次返回 true（用于"只打印一次"的刷屏防护）。 */
    static boolean once(String key) {
        return ONCE.add(key);
    }

    /** 计数上限判定：当前计数小于 {@code max} 时返回 true。 */
    static boolean under(String key, int max) {
        return COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).get() < max;
    }

    /** 计数自增。 */
    static void bump(String key) {
        COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 无条件 info 日志（诊断统一出口）。 */
    static void log(String fmt, Object... args) {
        try {
            CryptandNeoForge.WAF_LOGGER.info(fmt, args);
        } catch (Throwable ignored) {
        }
    }

    /** 无条件 debug 日志（诊断统一出口）。 */
    static void debug(String fmt, Object... args) {
        try {
            CryptandNeoForge.WAF_LOGGER.debug(fmt, args);
        } catch (Throwable ignored) {
        }
    }

    /** 清空全部节流状态（世界卸载时调用，避免跨世界残留）。 */
    static void reset() {
        POS_LAST.clear();
        GATE_LAST.clear();
        COUNTS.clear();
        ONCE.clear();
    }
}
