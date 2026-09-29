/**
 * ===== 隐藏原版导线实体渲染（2026-08-13 CEE 方式渲染） =====
 *
 * 当 Cryptand 转换+渲染启用（PowerGridWireConverter.isEnabled()）时，
 * 原版 PowerGrid 导线实体（BlockWireEntity 由 BlockWireRenderer 渲染、
 * HangingWireEntity 由 HangingWireRenderer 渲染）【不渲染】——
 * 视觉改由 CryptandWireVisual（Flywheel 实例化二次曲线）替代。
 * <p>
 * 注意：
 *   - 只隐藏【导线实体渲染】。实体本身仍存在（拓扑由转换类从
 *     transmissionLines 读取，不依赖实体渲染）；实体碰撞/交互/掉落原样。
 *   - CordEntity（powercord 电源线）不在此列——转换类只转换
 *     TransmissionLine（BlockWireEntity/HangingWireEntity 宿主），
 *     电源线保持原版渲染。
 *   - 门控与转换类一致：转换关闭 → 原版渲染恢复（完全原版）。
 * <p>
 * 为什么用 Renderer mixin 而非实体 mixin：实体的 render 逻辑完全在
 * Renderer 里（EntityRenderer.render 由 EntityRenderDispatcher 调用），
 * 注入 renderer 的 render HEAD + cancellable 是最小侵入的跳过方式。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.render;

import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import org.patryk3211.powergrid.electricity.wire.BlockWireRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 隐藏 BlockWireEntity（原版普通导线）渲染。 */
@Mixin(value = BlockWireRenderer.class, remap = false)
public abstract class VanillaWireRenderMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cryptand$hideBlockWireRender(org.patryk3211.powergrid.electricity.wire.BlockWireEntity entity,
                                              float yaw, float tickDelta,
                                              com.mojang.blaze3d.vertex.PoseStack matrices,
                                              net.minecraft.client.renderer.MultiBufferSource vertexConsumers,
                                              int light, CallbackInfo ci) {
        if (PowerGridWireConverter.isEnabled()) {
            ci.cancel();
        }
    }
}
