package com.hdf.cryptand.neoforge.cryptandsable_compat;


import dev.ryanhcode.sable.platform.SableSubLevelRenderPlatform;
import dev.ryanhcode.sable.sublevel.render.vanilla.SingleBlockSubLevelWrapper;

/**
 * {@link SableSubLevelRenderPlatform} 的 cryptand 实现（空桥）。
 *
 * <p>经 {@code META-INF/services/dev.ryanhcode.sable.platform.SableSubLevelRenderPlatform}
 * 注册，使 {@code SableSubLevelRenderPlatform.INSTANCE}（Aeronautics 等客户端依赖）能
 * resolve。注意【不能】让服务指向官方 {@code dev.ryanhcode.sable.neoforge.platform.
 * SableSubLevelRenderPlatformImpl}——官方惰性 mod 已加载同包类，指向官方包会造成
 * JPMS split package（cryptand 与 sable 同时导出该包 → ResolutionException）。
 *
 * <p>本桥全部 no-op：CryptandSable 自持客户端渲染（SableClientRenderModule 内存缓存 +
 * 世界空间投射），不经过官方 render platform 的 tesselate 流程；官方惰性 mod 构造已被
 * ASM 清空、不注册渲染管线，因此这里空实现即可保证 INSTANCE 不 NPE、不触发官方逻辑。
 */
public final class CryptandSableSubLevelRenderPlatformBridge
        implements SableSubLevelRenderPlatform {

    public static final CryptandSableSubLevelRenderPlatformBridge INSTANCE =
            new CryptandSableSubLevelRenderPlatformBridge();

    // ServiceLoader 需要 public 无参构造。
    public CryptandSableSubLevelRenderPlatformBridge() {
    }

    @Override
    public void tesselateBlock(
            final SingleBlockSubLevelWrapper blockAndTintGetter,
            final net.minecraft.client.resources.model.BakedModel bakedModel,
            final net.minecraft.world.level.block.state.BlockState blockState,
            final net.minecraft.core.BlockPos pos,
            final com.mojang.blaze3d.vertex.PoseStack poseStack,
            final com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer,
            final net.minecraft.util.RandomSource randomSource,
            final long seed,
            final int packedOverlay,
            final net.minecraft.client.renderer.RenderType renderType) {
        // CryptandSable 自带渲染路径，官方 tesselate 不参与 → no-op。
    }

    @Override
    public java.util.List<net.minecraft.client.renderer.RenderType> getRenderLayers(
            final SingleBlockSubLevelWrapper blockAndTintGetter,
            final net.minecraft.client.resources.model.BakedModel bakedModel,
            final net.minecraft.world.level.block.state.BlockState blockState,
            final net.minecraft.core.BlockPos pos,
            final net.minecraft.util.RandomSource randomSource) {
        return java.util.Collections.emptyList();
    }

    @Override
    public void tryAddFlywheelVisual(final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        // 无需（无亚层 BE 可视化注册）。
    }
}