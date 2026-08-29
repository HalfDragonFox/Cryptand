/**
 * ===== 屏蔽原版导线放置预览（2026-08-23） =====
 *
 * 自管模式下原版 WirePreviewImpl.render 用 getExactPosition（IElectric）取端点
 * ——CEE 端子返回 null/中心 → 预览线连到方块中心（错误显示）。自管模式直接
 * 屏蔽原版预览（由 WirePlacementPreviewRenderer 用统一识别层精确渲染替代）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandWirePlacement;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.patryk3211.powergrid.electricity.wire.forge.WirePreviewImpl", remap = false)
public abstract class WirePreviewHideMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private static void cryptand$hidePreview(RenderLevelStageEvent event, CallbackInfo ci) {
        try {
            // 自管模式（转换启用）→ 屏蔽原版预览（RenderLevelStageEvent 无 level；
            // isEnabled 即 PowerGrid 接管综合开关）；由 WirePlacementPreviewRenderer 替代
            if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                    .isEnabled())
                ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
