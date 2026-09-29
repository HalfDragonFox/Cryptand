/**
 * ===== 线程池负载 HUD 挂载（客户端） =====
 *
 * 在 Gui.render（HUD 渲染）末尾调用 ThreadPoolDebugHud.render——
 * 配置 enableThreadPoolHud 开启时左上角显示每个线程池每线程负载。
 */

package com.hdf.cryptand.neoforge.core.mixin;

import com.hdf.cryptand.neoforge.core.client.ThreadPoolDebugHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class ThreadPoolHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void cryptand$renderThreadPoolHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker,
                                              CallbackInfo ci) {
        try {
            ThreadPoolDebugHud.render(guiGraphics);
        } catch (Throwable ignored) {
        }
    }
}