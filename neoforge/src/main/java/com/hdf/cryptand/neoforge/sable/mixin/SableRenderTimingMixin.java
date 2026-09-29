/**
 * ===== Sable 渲染帧计时探针 Mixin（2026-08-30 评估用） =====
 *
 * 目的：量化渲染侧压力 + 验证帧率低是否来自物理：
 *   - 注入 GameRenderer.renderLevel(DeltaTracker) 的 HEAD / TAIL：
 *       · renderLevel 自身耗时（渲染管线）
 *       · 当前 FPS（Minecraft.getFps()）
 *       · 当前渲染帧与上一帧的间隔（主线程节拍）
 *   - [SableTiming] Render ms=XX fps=XX frameGap=XXms
 *
 * ⚠ 与物理探针（SablePhysicsTickTimingMixin）交叉验证：
 *   渲染耗时长 → 渲染是瓶颈；渲染耗时长低但帧间隔大 → 物理/主线程阻塞。
 *
 * 仅在客户端生效（放 cryptand.sable.mixins.json 的 "client" 数组）。
 * 默认关闭（enableSableRenderTiming）。仅测量，不改变行为。
 */

package com.hdf.cryptand.neoforge.sable.mixin;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SableRenderTimingMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void cryptand$beginRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            if (!isTimingEnabled()) return;
            THREAD_LOCAL.set(System.nanoTime());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void cryptand$endRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            if (!isTimingEnabled()) return;
            Long t0 = THREAD_LOCAL.get();
            if (t0 == null) return;
            long totalNs = System.nanoTime() - t0;
            double ms = totalNs / 1_000_000.0;
            long nowNs = System.nanoTime();
            long gapNs = nowNs - (LAST_FRAME_NS == 0L ? nowNs : LAST_FRAME_NS);
            LAST_FRAME_NS = nowNs;

            int fps = Minecraft.getInstance().getFps();

            // 节流 + 只打印慢帧（>=16.6ms 或帧间隔>=30ms 即卡顿候选）
            boolean slowFrame = ms > 16.6 || (gapNs / 1_000_000.0) > 30.0;
            if (slowFrame || isThrottled("Render")) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[SableTiming] Render ms={} fps={} frameGap={}ms{}",
                        String.format("%.2f", ms), fps,
                        String.format("%.1f", gapNs / 1_000_000.0),
                        slowFrame ? " [SLOW]" : "");
            }
            THREAD_LOCAL.remove();
        } catch (Throwable ignored) {
        }
    }

    private static final ThreadLocal<Long> THREAD_LOCAL = new ThreadLocal<>();
    private static long LAST_FRAME_NS = 0L;
    private static final java.util.Map<String, Long> THROTTLE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 运行时开关（NeoForge 官方 spec 直读；与 RaplierChunkBakeTimingMixin 同模式） */
    private static boolean isTimingEnabled() {
        return ConfigSable.ENABLE_SABLE_RENDER_TIMING.get();
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
