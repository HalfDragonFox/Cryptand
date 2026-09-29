package com.hdf.cryptand.neoforge.pipez.mixin;

import com.hdf.cryptand.neoforge.pipez.PipezTakeover;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pipez 连接变化监听 Mixin（2026-08-29 用户：监听旁边放下箱子后管道被连接到
 * 容器的消息——每当 Pipez 管道邻居方块变化（箱子放置/移除），原版
 * {@code PipeBlock.neighborChanged} 会重新计算 blockstate 连接属性并
 * {@code markPipesDirty}。本 Mixin 在 TAIL 监听：若该管道已被接管 →
 * 立即触发 forceRescan（重新识别接口 + 强制上报增量 diff），
 * 无需等每 tick 轮询发现（解决"有时候放箱子不触发"）。
 */
@Mixin(de.maxhenkel.pipez.blocks.PipeBlock.class)
public abstract class PipezNeighborMixin {

    @Inject(method = "neighborChanged", at = @At("TAIL"))
    private void cryptand$onNeighborChanged(BlockState state, Level world, BlockPos pos,
                                            net.minecraft.world.level.block.Block neighborBlock,
                                            BlockPos neighborPos, boolean isMoving,
                                            CallbackInfo ci) {
        try {
            // ⚠ 2026-08-30 子包开关：enablePipezSupport=false → 联动关闭（不干预）
            if (!com.hdf.cryptand.neoforge.pipez.PipezModule.shouldLoad()) return;
            if (world == null || world.isClientSide()) return;
            if (PipezTakeover.isTakenOver(pos)) {
                PipezTakeover.onConnectionChanged(world, pos);
            }
        } catch (Throwable t) {
            System.out.println("[Cryptand] PipezNeighborMixin err pos=" + pos + ": " + t);
        }
    }
}
