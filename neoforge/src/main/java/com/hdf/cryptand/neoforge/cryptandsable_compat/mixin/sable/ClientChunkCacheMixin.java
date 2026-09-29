/**
 * ===== 客户端 chunk 缓存重定向（2026-09-01 恢复官方方案） =====
 *
 * 官方 sable 渲染亚层的方块来源：亚层方块编入 {@code LevelPlot} 的 chunk 缓冲
 * （global ChunkPos → LevelChunk），渲染时 {@code SectionRenderDispatcher} 经
 * {@code RenderRegionCache} → {@code ClientChunkCache.getChunk} 读方块；
 * 本 mixin 拦截 {@code getChunk}：若 chunk 位于某亚层 plot 范围内，返回该 plot
 * 自己的 chunk（或 emptyChunk）——从而渲染管线无需改动即可适配"缓存读"。
 *
 * 注意：旧版（inBounds 恒 true）导致主世界全透明；现在 {@code SubLevelContainer.inBounds}
 * 基于真实亚层覆盖范围，仅亚层 chunk 位置被拦截。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private LevelChunk emptyChunk;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"), cancellable = true)
    private void sable$getChunk(final int x, final int z, final ChunkStatus status, final boolean create, final CallbackInfoReturnable<LevelChunk> cir) {
        try {
            final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
            if (container == null) return;

            final ChunkPos chunkPos = new ChunkPos(x, z);
            if (!container.inBounds(chunkPos)) return; // 不在亚层范围 → 主世界原路径

            final LevelChunk chunk = container.getChunk(chunkPos);
            if (chunk != null) {
                cir.setReturnValue(chunk);
            } else {
                cir.setReturnValue(this.emptyChunk);
            }
        } catch (final Throwable ignored) {
            // 亚层拦截失败不破坏主世界渲染
        }
    }
}

