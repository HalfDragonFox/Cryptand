/**
 * ===== 高亮/扫描方块采集 Mixin（2026-09-03） =====
 *
 * 注入 {@code WorldChunkUploader}：
 *  - @Redirect getCollisionShape(...) → 命中（实心）方块记录 debugHitBlocks（粒子）
 *    与 SableDebugScanStore（黄色扫描方块框）
 *  - @Inject uploadAroundBounds/uploadAroundAnchor/uploadAroundBox HEAD →
 *    SableDebugScanStore.clearAll()：每轮扫描前清空黄框 → 全量重扫填充。
 *    ★ 2026-09-03 修复“黄框不随结构移动”：结构移动 → 新查询 → 新扫描轮次
 *    清空重扫 → 黄框 = 最近一轮扫描结果（跟随结构位置动态更新，不留旧位残留）。
 *
 * ⚠ 2026-09-03 全量扫描已是产品机制（uploadSection 去掉增量去重，无条件全量遍历），
 *   因此本 mixin 不再需要 forceRescan @Redirect（那两处 target 调用已从原代码移除）。
 *   本 mixin 仅承担 debug 采集/清空，仅 debugHighlight/debugScopeBox 时应用 → 关闭时零开销。
 */
package com.hdf.cryptand.neoforge.cryptandsable.debug;

import com.hdf.cryptand.neoforge.cryptandsable.server.SableDebugScanStore;
import com.hdf.cryptand.neoforge.cryptandsable.server.WorldChunkUploader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldChunkUploader.class, remap = false)
public class WorldChunkUploaderHighlightMixin {

    /** 三个扫描入口头部：清空黄框（本轮全量重扫后覆盖）。 */
    @Inject(method = "uploadAroundBounds", at = @At("HEAD"), remap = false)
    private void cryptand$preScanBounds(final CallbackInfo ci) {
        SableDebugScanStore.clearAll();
    }

    @Inject(method = "uploadAroundAnchor", at = @At("HEAD"), remap = false)
    private void cryptand$preScanAnchor(final CallbackInfo ci) {
        SableDebugScanStore.clearAll();
    }

    @Inject(method = "uploadAroundBox", at = @At("HEAD"), remap = false)
    private void cryptand$preScanBox(final CallbackInfo ci) {
        SableDebugScanStore.clearAll();
    }

    /**
     * @Redirect getCollisionShape(...)：命中（实心非空）方块 → 记录世界坐标。
     */
    @Redirect(method = "uploadSection",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private net.minecraft.world.phys.shapes.VoxelShape cryptand$markHit(
            final net.minecraft.world.level.block.state.BlockState state,
            final net.minecraft.world.level.BlockGetter level,
            final BlockPos pos) {
        final net.minecraft.world.phys.shapes.VoxelShape shape =
                state.getCollisionShape(level, pos);
        if (!shape.isEmpty() && level instanceof ServerLevel sl) {
            final WorldChunkUploader u = (WorldChunkUploader) (Object) this;
            u.cryptand$debugMarkHit(pos.getX(), pos.getY(), pos.getZ());
            // ★ 2026-09-03 debugScopeBox：命中方块同时进共享渲染缓存（黄色小框）。
            SableDebugScanStore.add(
                    pos.getX(), pos.getY(), pos.getZ());
        }
        return shape;
    }
}
