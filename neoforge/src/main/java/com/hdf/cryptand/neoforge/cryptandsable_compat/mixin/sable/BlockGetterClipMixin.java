/**
 * ===== 亚层方块射线命中（客户端，2026-08-31） =====
 *
 * 参考官方 sable 的 clip_overwrite BlockGetterMixin（@Overwrite BlockGetter.clip），
 * 但适配我们的架构：官方把 ray 投影进"亚层 level"查询；我们的亚层方块在
 * 客户端渲染模块（SableClientRenderModule）的内存缓存中（世界坐标 anchor+offset）。
 *
 * MVP 实现：把亚层方块当"世界方块"——原始 clip 计算后，对每个亚层渲染数据里的
 * 方块用 BlockState 的 VoxelShape 与 ray 做 clip，若命中比原始世界命中更近则返回该命中。
 * 效果：手杖/射线能"选中"亚层方块（渲染与语义一致）。
 *
 * 已知限制：物理位姿旋转后，方块世界位置按"初始锚+偏移"估计（静止/小位移场景足够）。
 * 服务端无亚层渲染数据 → clip 行为与原版一致（不影响服务端逻辑）。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableSubLevelRenderData;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockGetter.class, priority = 1400)
public interface BlockGetterClipMixin {

    /**
     * ★ 2026-09-06 由 @Overwrite 改 @Inject(HEAD, cancellable)：@Overwrite 会结构性替换
     *   原版 clip（含官方 sable 自身的 clip 处理）——core 关闭（模式 C）时无法放行原版，
     *   导致与官方 sable 结构无法射线交互。改注入后：
     *   - core 关闭 → 直接 return（不 cancel）→ 原版/官方 clip 完整执行；
     *   - core 启用 → 执行完整自定义（原版 traverse + Cryptand 亚层段）→ setReturnValue。
     */
    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    default void cryptand$clip(ClipContext clipContext,
                               CallbackInfoReturnable<BlockHitResult> cir) {
        // ★ 【运行期 core 门控】核心关闭（模式 C）→ 放行原版 clip（零 CryptandSable 行为）
        if (!ConfigCryptandSable
                .ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        final BlockGetter self = (BlockGetter) this;

        // 1) 原版世界 clip（与 MC 原版 traverseBlocks 等价路径；含官方 sable 经
        //    getBlockState 转接的亚层块——射线可命中官方结构）
        BlockHitResult worldHit = BlockGetter.traverseBlocks(
                clipContext.getFrom(), clipContext.getTo(), clipContext,
                (ctx, pos) -> {
                    BlockState state = self.getBlockState(pos);
                    FluidState fluid = self.getFluidState(pos);
                    VoxelShape shape = ctx.getBlockShape(state, self, pos);
                    BlockHitResult hr = self.clipWithInteractionOverride(
                            ctx.getFrom(), ctx.getTo(), pos, shape, state);
                    VoxelShape fluidShape = ctx.getFluidShape(fluid, self, pos);
                    BlockHitResult hr2 = fluidShape.clip(ctx.getFrom(), ctx.getTo(), pos);
                    if (hr == null) return hr2;
                    if (hr2 == null) return hr;
                    double d = ctx.getFrom().distanceToSqr(hr.getLocation());
                    double e = ctx.getFrom().distanceToSqr(hr2.getLocation());
                    return d <= e ? hr : hr2;
                },
                ctx -> {
                    Vec3 v = ctx.getFrom().subtract(ctx.getTo());
                    return BlockHitResult.miss(ctx.getTo(),
                            Direction.getNearest(v.x, v.y, v.z),
                            BlockPos.containing(ctx.getTo()));
                });

        if (!(this instanceof Level) || MinecraftAccess.clientLevel() == null) {
            cir.setReturnValue(worldHit);
            return;
        }

        // 2) 亚层方块 clip（内存缓存；按物理位姿投射——与渲染同源，物理运动后可选中）
        BlockHitResult best = worldHit;
        double bestDist = best != null && best.getType() == HitResult.Type.BLOCK
                ? best.getLocation().distanceToSqr(clipContext.getFrom())
                : Double.MAX_VALUE;

        var subLevels = SableClientRenderModule.INSTANCE.allSubLevels();
        if (subLevels != null) {
            for (SableSubLevelRenderData data : subLevels) {
                // 亚层最新位姿（与渲染同源；null = 静止在初始位置）
                var ps = SableClientRenderModule.INSTANCE.pose(data.runtimeId());
                // 投射基准：初始结构包围盒中心（客户端 anchor 即 buildRenderPayload 的 anchor）
                BlockPos anchorInit = data.anchor();

                for (SableSubLevelRenderData.RenderBlock rb : data.blocks()) {
                    BlockPos initPos = new BlockPos(
                            data.anchor().getX() + rb.dx(),
                            data.anchor().getY() + rb.dy(),
                            data.anchor().getZ() + rb.dz());
                    // 投射到物理位姿下的世界位置（渲染/射线同源）
                    BlockPos worldPos = com.hdf.cryptand.neoforge.cryptandsable.api.
                            SableSubLevelProjection.projectBlock(ps, anchorInit, initPos);

                    BlockState state = Block.BLOCK_STATE_REGISTRY.byId(rb.stateId());
                    if (state == null || state.isAir()) continue;
                    VoxelShape shape = state.getShape(
                            MinecraftAccess.clientLevel(), worldPos);
                    if (shape.isEmpty()) continue;
                    BlockHitResult hr = shape.clip(clipContext.getFrom(), clipContext.getTo(), worldPos);
                    if (hr != null) {
                        double d = hr.getLocation().distanceToSqr(clipContext.getFrom());
                        if (d < bestDist) {
                            best = hr;
                            bestDist = d;
                        }
                    }
                }
            }
        }

        cir.setReturnValue(best != null ? best : worldHit);
    }

    /** 访问 Minecraft client level（避免客户端类在服务端加载；渲染/射线只在客户端用）。 */
    final class MinecraftAccess {
        private MinecraftAccess() {
        }

        static net.minecraft.client.multiplayer.ClientLevel clientLevel() {
            try {
                return net.minecraft.client.Minecraft.getInstance().level;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
