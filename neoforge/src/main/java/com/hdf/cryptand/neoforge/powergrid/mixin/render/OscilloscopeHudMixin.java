/**
 * ===== 示波器 HUD 挂载（客户端） =====
 *
 * 在 Gui.render（HUD 渲染）末尾调用 OscilloscopeHud.render——
 * 手持示波器时在屏幕角落画小波形屏幕。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.render;

import com.hdf.cryptand.neoforge.powergrid.client.OscilloscopeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class OscilloscopeHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void cryptand$renderOscilloscopeHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker,
                                                CallbackInfo ci) {
        try {
            OscilloscopeHud.render(guiGraphics);
        } catch (Throwable ignored) {
        }
    }
}
