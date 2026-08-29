/**
 * ===== 隐藏原版悬垂导线实体渲染（2026-08-13 CEE 方式渲染） =====
 *
 * 与 VanillaWireRenderMixin（BlockWireRenderer）配套：隐藏
 * HangingWireEntity（原版悬垂导线，二次曲线那类）的渲染——
 * 当 Cryptand 转换+渲染启用时由 CryptandWireVisual（Flywheel）替代。
 * <p>
 * 门控与转换类一致：PowerGridWireConverter.isEnabled() 为 false 时
 * 保持原版渲染（完全原版）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter;
import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 隐藏 HangingWireEntity（原版悬垂导线）渲染。 */
@Mixin(value = HangingWireRenderer.class, remap = false)
public abstract class VanillaHangingWireRenderMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cryptand$hideHangingWireRender(org.patryk3211.powergrid.electricity.wire.HangingWireEntity entity,
                                                float yaw, float tickDelta,
                                                com.mojang.blaze3d.vertex.PoseStack matrices,
                                                net.minecraft.client.renderer.MultiBufferSource vertexConsumers,
                                                int light, CallbackInfo ci) {
        if (PowerGridWireConverter.isEnabled()) {
            ci.cancel();
        }
    }
}
