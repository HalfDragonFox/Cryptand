/**
 * ===== 匝数屏 cap 材质渲染注入（客户端，2026-08-15）=====
 *
 * 对齐原版 PowerGrid ValueSettingsScreenMixin：注入 Create ValueSettingsScreen
 * 的 renderWindow，对本 mod 的 CryptandWindingScreen 实例额外渲染 cap（不可选
 * 部分）的 brass_cover 材质（renderBarCap / renderBarCapMilestone）。
 *
 * 注入坐标与原版一致（同一 Create 版本验证过）：
 *   - renderBar：renderWindow 内第 3 处 drawString（ordinal=2）后，@Local x/y
 *   - renderMilestone：renderWindow 内 AllGuiTextures.render（ordinal=1）后，
 *     @Local valueBarX/milestoneX/milestone
 */
package com.hdf.cryptand.neoforge.core.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandWindingScreen;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ValueSettingsScreen.class)
public abstract class CryptandValueSettingsScreenMixin {

    @Shadow
    private int maxLabelWidth;
    @Shadow
    private int valueBarWidth;
    @Shadow
    private ValueSettingsBoard board;

    /** 渲染 cap 之后的 bar（brass_cover 材质）——对齐原版 ValueSettingsScreenMixin。
     *  allow=1：注入点随 Create 版本可能变化，失败不崩（仅 cap 不渲染）。 */
    @Inject(method = "renderWindow(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
                     ordinal = 2),
            allow = 1)
    private void cryptand$renderBar(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
                                    CallbackInfo ci,
                                    @Local(name = "x") int x, @Local(name = "y") int y) {
        if ((Object) this instanceof CryptandWindingScreen screen) {
            screen.renderBarCap(graphics, x, y, valueBarWidth, board);
        }
    }
}
