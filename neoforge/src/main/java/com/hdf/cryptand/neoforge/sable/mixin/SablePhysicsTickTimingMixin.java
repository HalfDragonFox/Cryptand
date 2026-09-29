/**
 * ===== Sable 物理 tick 计时探针 Mixin（2026-08-30 评估用） =====
 *
 * 目的：量化【整个物理循环】在正常 vs 重叠/卡土里时的总耗时：
 *   - 注入 SubLevelPhysicsSystem.tickPipelinePhysics(ServerSubLevelContainer)
 *     的 HEAD / TAIL —— 包住所有子层 × 所有 substeps 的完整物理步。
 *   - 输出 [SableTiming] PhysTick subs=N sublevels=M dt=XX ms
 *
 * ⚠ 直接量化"重叠/卡土里时物理主线程几千 ms"假设：
 *   若物理耗时高 → 卡顿确实来自物理求解；
 *   若物理耗时低 → 卡顿来自渲染或其它（与渲染探针交叉验证）。
 *
 * 默认关闭（enableSablePhysicsTiming）。仅测量，不改变行为。
 */

package com.hdf.cryptand.neoforge.sable.mixin;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem", remap = false)
public abstract class SablePhysicsTickTimingMixin {

    @Inject(method = "tickPipelinePhysics",
            at = @At("HEAD"), remap = false)
    private void cryptand$beginPhysTick(@Coerce Object container, CallbackInfo ci) {
        try {
            if (!isTimingEnabled()) return;
            THREAD_LOCAL.set(System.nanoTime());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "tickPipelinePhysics",
            at = @At("TAIL"), remap = false)
    private void cryptand$endPhysTick(@Coerce Object container, CallbackInfo ci) {
        try {
            if (!isTimingEnabled()) return;
            Long t0 = THREAD_LOCAL.get();
            if (t0 == null) return;
            long totalNs = System.nanoTime() - t0;
            double ms = totalNs / 1_000_000.0;
            THREAD_LOCAL.remove();
            if (ms > 15.0 || isThrottled("PhysTick")) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[SableTiming] PhysTick dt={} ms (>=15ms 记录; 重叠/卡土里应显著升高)",
                        String.format("%.2f", ms));
            }
        } catch (Throwable ignored) {
        }
    }

    private static final ThreadLocal<Long> THREAD_LOCAL = new ThreadLocal<>();
    private static final java.util.Map<String, Long> THROTTLE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 运行时开关（NeoForge 官方 spec 直读；与 RaplierChunkBakeTimingMixin 同模式） */
    private static boolean isTimingEnabled() {
        return ConfigSable.ENABLE_SABLE_PHYSICS_TIMING.get();
    }

    /** 300ms 节流 */
    private static boolean isThrottled(String key) {
        long now = System.nanoTime();
        Long last = THROTTLE.get(key);
        if (last != null && (now - last) < 300_000_000L) return false;
        THROTTLE.put(key, now);
        return true;
    }
}
