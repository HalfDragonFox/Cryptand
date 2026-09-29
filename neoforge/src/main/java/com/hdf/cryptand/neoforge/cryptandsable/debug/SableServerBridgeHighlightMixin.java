/**
 * ===== 高亮收集 Mixin ②（2026-09-02，debugHighlight 调试开关） =====
 *
 * 注入 {@code SableServerBridge.tick(long)} 尾部：每 20 tick 从
 * WorldChunkUploader.debugTakeHits() 取命中块，发射红色粒子高亮。
 * 仅 debugHighlight=true 时应用 → 关闭时 tick() 零开销。
 */
package com.hdf.cryptand.neoforge.cryptandsable.debug;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableServerBridge;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableServerBridgeAccess;
import com.hdf.cryptand.neoforge.cryptandsable.server.WorldChunkUploader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SableServerBridge.class, remap = false)
public class SableServerBridgeHighlightMixin {

    @Inject(method = "tick(J)V", at = @At("RETURN"))
    private void cryptand$emitHighlight(final long serverTick, final CallbackInfo ci) {
        try {
            final SableServerBridgeAccess bridge = (SableServerBridgeAccess) (Object) this;
            if (bridge == null) return;
            final CryptandSable bb = bridge.bridgeCore();
            if (bb == null) return;
            final WorldChunkUploader up =
                    bb.worldUploader();
            if (up == null || !up.available()) return;
            // 最近有命中才发（20 tick 一次）
            final long lastHit = up.debugLastHitMillis();
            if (lastHit <= 0 || serverTick % 20 != 0) return;

            final net.minecraft.server.level.ServerLevel level = bridge.bridgeLevel();
            if (level == null) return;

            final long[] hits = up.debugTakeHits();
            int count = 0;
            // ★ 只发玩家附近（<16格）的命中块（否则 20 万块分散，玩家附近无粒子）
            net.minecraft.server.level.ServerPlayer nearest = null;
            for (final net.minecraft.server.level.ServerPlayer p : level.players()) {
                nearest = p;   // 单人测试取第一个即可
                break;
            }
            if (nearest == null) return;
            final double px = nearest.getX(), py = nearest.getY(), pz = nearest.getZ();
            for (long v : hits) {
                if (count++ > 2048) break;
                int bx = (int) (v & 0x3FFFFFF);
                int by = (int) ((v >> 26) & 0x3FFFFFF);
                int bz = (int) ((v >> 52) & 0x3FFFFFF);
                double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
                // 距离玩家 16 格以内才发（20 万块取玩家附近一小圈）
                double dx0 = cx - px, dy0 = cy - py, dz0 = cz - pz;
                if (dx0 * dx0 + dy0 * dy0 + dz0 * dz0 > 16.0 * 16.0) continue;
                level.sendParticles(
                        net.minecraft.core.particles.ParticleTypes.INSTANT_EFFECT,
                        cx + level.random.nextDouble() * 0.8 - 0.4,
                        cy + level.random.nextDouble() * 0.8 - 0.4,
                        cz + level.random.nextDouble() * 0.8 - 0.4,
                        6, 0.06, 0.06, 0.06, 0.01);
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] debug highlight emitted {} blocks (total {}, t={})",
                    Math.min(count, 2048), hits.length, serverTick);
        } catch (final Throwable ignored) {
        }
    }
}
