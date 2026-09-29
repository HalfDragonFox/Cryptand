/**
 * ===== PlacementOverlay 悬浮文字多行扩展（客户端） =====
 *
 * PowerGrid 的 PlacementOverlay.renderOverlay 把每个 provider 的文本作为
 * 单行渲染（font.draw 不处理 \n）。本 Mixin 在其向 lines 列表添加文本后，
 * 若该行以私有区字符 \uE000 开头（万用表调试 Mixin 的 AC 多行标记），
 * 则按 \n 拆分为多行（逐段保留原样式），实现 AC 参数的两行显示。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.render;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.patryk3211.powergrid.utility.PlacementOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = PlacementOverlay.class, remap = false)
public abstract class PlacementOverlayMixin {

    /** 与 MultimeterItemMixin.MARKER 相同的私有区标记字符 */
    private static final String MARKER = "\uE000";

    @Shadow
    private static List<Component> lines;

    /**
     * 打开任意 GUI（如示波器放大界面）时隐藏原版 PowerGrid 万用表 HUD 叠加
     * （屏幕底部 "位置 电压 频率" 读数）——否则界面打开后 HUD 文字仍残留叠加，
     * 界面显得混乱（2026-08-18 用户反馈“界面不合理”）。
     */
    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private static void cryptand$hideWhenScreenOpen(Gui gui, GuiGraphics graphics, CallbackInfo ci) {
        try {
            if (net.minecraft.client.Minecraft.getInstance().screen != null) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "renderOverlay",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", shift = At.Shift.AFTER))
    private static void cryptand$expandMultimeterLines(Gui gui, GuiGraphics graphics, CallbackInfo ci) {
        if (lines == null || lines.isEmpty()) return;
        Component last = lines.get(lines.size() - 1);
        if (!last.getString().startsWith(MARKER)) return;

        // 按 \n 拆分，逐段保留样式
        List<Component> parts = new ArrayList<>();
        MutableComponent current = Component.empty();
        for (Component sibling : last.getSiblings()) {
            if ("\n".equals(sibling.getString())) {
                parts.add(current);
                current = Component.empty();
            } else {
                current.append(sibling);
            }
        }
        parts.add(current);

        // ⚠ 2026-08-30 修复（用户"文字最开始的□"）：原行以 MARKER() 开头，
        // 拆分后第一个 part 的开头 sibling 仍是 MARKER——私有区字符在字体缺失 →
        // 显示 □。剥离第一个 part 的开头 MARKER（保留后续样式）。
        if (!parts.isEmpty()) {
            List<Component> firstSib = parts.get(0).getSiblings();
            if (!firstSib.isEmpty() && MARKER.equals(firstSib.get(0).getString())) {
                MutableComponent cleaned = Component.empty();
                for (int i = 1; i < firstSib.size(); i++) {
                    cleaned.append(firstSib.get(i));
                }
                parts.set(0, cleaned);
            }
        }
        lines.remove(lines.size() - 1);
        lines.addAll(parts);
    }
}
