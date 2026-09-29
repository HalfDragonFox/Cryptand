/**
 * ===== 原版 Sable 兼容：物理化转接（2026-09-01） =====
 *
 * 【架构】官方 sable 作为惰性 mod 加载（类进 ModuleLayer），但其 @Mod 构造被 ASM
 * 清空（官方初始化不执行）→ 官方 ServerSubLevelContainer.physicsSystem() 为 null →
 * simulated/aeronautics 调用官方 SubLevelAssemblyHelper.assembleBlocks 物理化时 NPE
 * （ServerSubLevel.<init> 需要 physicsSystem.getNextRuntimeID()）→ 无法物理化。
 *
 * 【本 mixin】拦截官方静态方法 assembleBlocks（simulated/aeronautics 的物理装入口）
 * 转接到 CryptandSable 核心 {@code CryptandSubLevelApi.physicalize}：
 *   - 真实执行：创建物理化亚层、登记容器、分配 runtimeId、导入核心物理体、搬移方块
 *   - official 链路（allocateNewSubLevel/ServerSubLevel/<init>/physicsSystem）完全跳过
 *   - 返回值：null（simulated 视为"官方未创建亚层"→ 跳过自身的胶水/plot 后续；
 *     物理化已由 CryptandSable 完成，装配器 getContaining 由
 *     PhysicsAssemblerCompatMixin 转接 → 感知到已组装）。
 *
 * 【转接点】官方 static 方法签名：
 *   SubLevelAssemblyHelper.assembleBlocks(ServerLevel, BlockPos, Iterable<BlockPos>,
 *                                          BoundingBox3ic) → ServerSubLevel
 * 兼容 simulated（SimAssemblyHelper.assembleFromSingleBlock）与 aeronautics 装配调用。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandBounds3i;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandServerSubLevel;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public abstract class SubLevelAssemblyHelperCompatMixin {

    @Unique
    private static volatile boolean cryptandsable$assembling = false;

    /**
     * 接管官方物理化（assembleBlocks）：转接到 CryptandSable 核心，返回 null。
     */
    @SuppressWarnings("unused")
    @Inject(method = "assembleBlocks(Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/core/BlockPos;Ljava/lang/Iterable;" +
            "Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;)" +
            "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;", at = @At("HEAD"), cancellable = true)
    private static void cryptandsable$assembleBlocksRedirect(
            final ServerLevel level, final BlockPos anchor,
            final Iterable<BlockPos> blocks, final BoundingBox3ic bounds,
            final CallbackInfoReturnable<ServerSubLevel> cir) {
        try {
            // ★ 2026-09-06 【运行期 core 门控】mixin apply 早于 config 加载（core=false 仍
            //   可能注入）→ 方法体执行时判 enableCryptandSableCore：关闭（模式 C）→ 不接管
            //   （放行官方 assembleBlocks 原逻辑——官方物理化正常）；启用才转 CryptandSable。
            if (!ConfigCryptandSable
                    .ENABLE_CRYPTAND_SABLE_CORE.get()) {
                return;
            }
            cryptandsable$assembleBlocks(level, anchor, blocks, bounds, cir);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] assembleBlocks compat redirect failed: {}", t.toString());
        }
    }

    @Unique
    private static void cryptandsable$assembleBlocks(
            final ServerLevel level, final BlockPos anchor,
            final Iterable<BlockPos> blocks, final BoundingBox3ic bounds,
            final CallbackInfoReturnable<ServerSubLevel> cir) {
        if (level == null || level.isClientSide() || anchor == null) {
            return; // 非服务端/无锚 → 官方原逻辑（会 NPE，但截住返回 null 保底）
        }

        // 转换 bounds：官方 BoundingBox3ic → CryptandBounds3i
        CryptandBounds3i box = null;
        if (bounds != null) {
            try {
                box = new CryptandBounds3i(
                        bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ());
            } catch (final Throwable ignored) {
                box = null;
            }
        }

        // 转接 CryptandSable 核心物理化（完整登记/搬运/runtimeId/核心体导入）
        final CryptandServerSubLevel sub =
                CryptandSubLevelApi.physicalize(level, anchor, blocks, box);
        if (sub != null) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] assembleBlocks → physicalize ok sub={} anchor={} blocks={}",
                    sub.getUniqueId(), anchor, (blocks != null)
                            ? java.util.Collections.singletonList(new Object()).size() : 0);
        } else {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] assembleBlocks → physicalize returned null (blocks empty/level invalid)");
        }
        // 官方链路不再执行（physicsSystem null 会 NPE）；simulated 视为官方未创建亚层 → 安全跳过
        cir.setReturnValue(null);
        cir.cancel();
    }
}