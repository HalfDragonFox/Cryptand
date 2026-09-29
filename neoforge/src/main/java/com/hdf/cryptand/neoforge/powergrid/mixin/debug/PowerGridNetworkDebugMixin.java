/**
 * ===== PowerGrid 网络调试 Mixin =====
 *
 * 始终注入；调试输出默认关闭（2026-08-12 已移除 enablePowergridDebug 配置）。
 *
 * 每 1 秒汇总一次所有电力网络的：
 *   - 求解次数/秒（solves/sec）
 *   - 单次求解平均耗时（µs）
 *   - 求解线程名（判断是否在主线程/并行线程）
 *   - 收敛状态、节点数、源数、outerHooks 数、multiTick
 * 以及 WorldNetworks 总体耗时、preTick 调用线程。
 *
 * 纯观测，不修改任何计算逻辑。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.debug;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = org.patryk3211.powergrid.electricity.sim.ElectricalNetwork.class,
       remap = false)
public abstract class PowerGridNetworkDebugMixin {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    // ===== 单网络统计：count / totalNanos =====
    @Unique
    private long cryptand$solveStartNanos;

    @Unique
    private static final Map<Object, long[]> cryptand$netStats = new ConcurrentHashMap<>();

    @Unique
    private static final Map<Object, String> cryptand$netThreads = new ConcurrentHashMap<>();

    @Unique
    private static volatile long cryptand$lastPrintMs = System.currentTimeMillis();

    // ===== WorldNetworks 总体统计（静态） =====
    @Unique
    private static final Map<Object, long[]> cryptand$wnStats = new ConcurrentHashMap<>();

    @Shadow
    public java.util.List<?> nodes;

    @Shadow
    private int sourceCount;

    @Shadow
    private int currentMultiTick;

    @Inject(method = "singleTick", at = @At("HEAD"))
    private void cryptand$diagStart(CallbackInfo ci) {
        if (!cryptand$debugEnabled()) return;
        this.cryptand$solveStartNanos = System.nanoTime();
    }

    @Inject(method = "singleTick", at = @At("RETURN"))
    private void cryptand$diagEnd(CallbackInfo ci) {
        if (!cryptand$debugEnabled()) return;
        long elapsed = System.nanoTime() - this.cryptand$solveStartNanos;
        Object self = this;

        long[] st = cryptand$netStats.computeIfAbsent(self, k -> new long[2]);
        st[0]++;
        st[1] += elapsed;
        cryptand$netThreads.put(self, Thread.currentThread().getName());

        cryptand$maybePrint();
    }

    @Unique
    private static void cryptand$maybePrint() {
        long now = System.currentTimeMillis();
        if (now - cryptand$lastPrintMs < 1000) return;
        cryptand$lastPrintMs = now;

        LOGGER.info("[Cryptand-DIAG] ===== PowerGrid 网络统计 ({}s) =====",
                (now - cryptand$lastPrintMs + 1000) / 1000);

        for (Map.Entry<Object, long[]> e : cryptand$netStats.entrySet()) {
            Object net = e.getKey();
            long[] st = e.getValue();
            long count = st[0], totalNanos = st[1];
            st[0] = 0; st[1] = 0; // 重置统计
            if (count == 0) continue;

            double avgUs = (double) totalNanos / count / 1000.0;
            String thread = cryptand$netThreads.get(net);
            boolean main = thread == null || thread.equals("Server thread");

            int nodeCount = 0, srcCount = -1, mt = -1, hookCount = 0;
            Object conv = null;
            try {
                nodeCount = net.getClass().getField("nodes") != null
                        ? ((java.util.List<?>) cryptand$field(net, "nodes")).size() : -1;
            } catch (Throwable ignored) {}
            try { srcCount = (Integer) cryptand$field(net, "sourceCount"); } catch (Throwable ignored) {}
            try { mt = (Integer) cryptand$field(net, "currentMultiTick"); } catch (Throwable ignored) {}
            try {
                Object hooks = cryptand$field(net, "outerHooks");
                if (hooks instanceof java.util.Set<?> s) hookCount = s.size();
            } catch (Throwable ignored) {}
            try { conv = cryptand$invoke(net, "isConverged"); } catch (Throwable ignored) {}

            LOGGER.info("[Cryptand-DIAG]   net={} solves/sec={} avg={}µs thread={} ({}) "
                            + "nodes={} sources={} hooks={} multiTick={} converged={}",
                    Integer.toHexString(System.identityHashCode(net)),
                    count, String.format("%.1f", avgUs),
                    thread, main ? "主线程" : "其他",
                    nodeCount, srcCount, hookCount, mt, conv);
        }
        cryptand$netThreads.clear();
    }

    @Unique
    private static boolean cryptand$debugEnabled() {
        try {
            return false; // 2026-08-12：移除 enablePowergridDebug 配置，调试默认关闭
        } catch (Throwable t) {
            return false;
        }
    }

    @Unique
    private static Object cryptand$field(Object target, String name) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    @Unique
    private static Object cryptand$invoke(Object target, String name) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) { return null; }
    }
}
