/**
 * ===== 屏蔽原版导线放置预览（2026-08-23；2026-09-12 改为【局部重定向】） =====
 *
 * 自管模式下原版 WirePreviewImpl.render 用 getExactPosition（IElectric）取端点
 * ——CEE 端子返回 null/中心 → 预览线连到方块中心（错误显示）。自管模式直接
 * 屏蔽原版【放置预览线】（由 WirePlacementPreviewRenderer 用统一识别层精确渲染替代）。
 *
 * ⚠⚠ 2026-09-12 修复"万用表表笔线不显示"：
 * 原实现对整个 render 方法在 HEAD 处 ci.cancel()——但该方法内【同时】调用
 *   WirePreview.render(...)               → 放置预览线（要屏蔽的）
 *   MultimeterItemRenderer.render(...)    → 万用表/温度计/电阻表表笔线（唯一渲染入口）
 *   buffer.draw()
 * → 取消整个方法 = 表笔线一起消失（原版 MultimeterItemRenderer 是探针线的
 *   唯一渲染方，见 ai_memory/repo/wire-selfloop-and-probe-rendering.md）。
 * 现改为 @Redirect 【只】把 WirePreview.render(...) 替换为 no-op，
 * 保留 MultimeterItemRenderer.render(...) 与 buffer.draw() 原样执行——
 * 既屏蔽了连到方块中心的错误预览线，又恢复原版表笔线渲染。
 *
 * 判据：PowerGridWireConverter.isEnabled()（= 交错电网支持 × 电路仿真核心）为
 * false（未接管，完全原版）时不改动，保留原版预览线。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.render;

import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.wire.WirePreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "org.patryk3211.powergrid.electricity.wire.forge.WirePreviewImpl", remap = false)
public abstract class WirePreviewHideMixin {

    /**
     * 只屏蔽【放置预览线】调用（WirePreview.render）；
     * 同方法内的 MultimeterItemRenderer.render（表笔线）保持原样。
     * 调用点签名（PowerGrid 1.21.1 字节码实测）：
     * invokestatic WirePreview.render(SuperRenderTypeBuffer, PoseStack, ClientLevel, LocalPlayer, Vec3)V
     */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lorg/patryk3211/powergrid/electricity/wire/WirePreview;"
                            + "render(Lnet/createmod/catnip/render/SuperRenderTypeBuffer;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/multiplayer/ClientLevel;"
                            + "Lnet/minecraft/client/player/LocalPlayer;"
                            + "Lnet/minecraft/world/phys/Vec3;)V"),
            remap = false)
    private static void cryptand$hideWirePreview(SuperRenderTypeBuffer buffer, PoseStack matrixStack,
                                                 ClientLevel world, LocalPlayer player, Vec3 cameraPos) {
        boolean takeOver = false;
        try {
            takeOver = PowerGridWireConverter.isEnabled();
        } catch (Throwable ignored) {
        }
        if (!takeOver) {
            // 未接管（完全原版）→ 保留原版放置预览线
            WirePreview.render(buffer, matrixStack, world, player, cameraPos);
        }
        // 接管中 → 预览线交给 WirePlacementPreviewRenderer（统一识别层精确端子位置）
    }
}
