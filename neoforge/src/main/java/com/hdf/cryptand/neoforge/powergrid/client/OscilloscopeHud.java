/**
 * ===== 示波器 HUD 小屏幕（手持时显示） =====
 *
 * 手持示波器时，在屏幕右下角（物品栏上方）画一个小波形窗口：
 * 合成波形（白色）+ 每频率分量曲线（不同颜色）+ 搭线位置。
 * 数据来自 OscilloscopeStore（服务端求解回发）。
 * 由 OscilloscopeHudMixin（@Mixin Gui）在 HUD 渲染时调用。
 */

package com.hdf.cryptand.neoforge.powergrid.client;

import com.hdf.cryptand.neoforge.powergrid.item.OscilloscopeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class OscilloscopeHud {

    private OscilloscopeHud() {}

    /** 当前主手持有的物品（供 Screen/HUD 显示搭线位置） */
    public static ItemStack handItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        return mc.player.getMainHandItem();
    }

    /** 手持是否示波器 */
    public static boolean isHoldingOscilloscope() {
        return handItem().getItem() instanceof
                OscilloscopeItem;
    }

    /** 搭线位置标签缓存（每 tick 计算一次，避免每帧 NBT 反序列化） */
    private static long lastLabelTick = -1;
    private static String cachedLabel = "未接线";

    /** HUD 渲染（手持示波器时画小窗口；主线程 render 调用） */
    public static void render(GuiGraphics g) {
        try {
            if (!isHoldingOscilloscope()) return;
            // 触发每 tick 采样（HUD 每帧渲染，tick() 内按 gameTime 去重 → 每 tick 一次）
            OscilloscopeStore.tick();
            Minecraft mc = Minecraft.getInstance();
            // 屏幕打开（含 LDLib 示波器大屏）→ 跳过 HUD：避免与大屏图表双重逐帧绘制
            if (mc.screen != null) return;
            int w = g.guiWidth();
            int h = g.guiHeight();
            int left = w - 158;
            int top = h - 118;
            if (left < 2) left = 2;
            if (top < 2) top = 2;
            int bw = 150;
            int bh = 84;

            // 背景 + 边框
            g.fill(left, top, left + bw, top + bh, 0xCC000000);
            g.hLine(left, left + bw, top, 0xFF2A2A2A);
            g.hLine(left, left + bw, top + bh, 0xFF2A2A2A);
            g.vLine(left, top, top + bh, 0xFF2A2A2A);
            g.vLine(left + bw, top, top + bh, 0xFF2A2A2A);

            // 标题 + 搭线位置 + 检测时间（label 按 tick 缓存，避免每帧 NBT 反序列化）
            long gtNow = mc.level.getGameTime();
            if (gtNow != lastLabelTick) {
                lastLabelTick = gtNow;
                cachedLabel = OscilloscopeItem
                        .probeLabel(handItem()).getString();
            }
            String label = cachedLabel;
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    "示波器 " + label, left + 4, top + 3, 0xFFFFFF);
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    String.format("检测 %.2fs", OscilloscopeStore.detectSeconds()),
                    left + bw - 58, top + 3, 0xFF88AAFF);

            List<OscilloscopeStore.Tone> tones = OscilloscopeStore.tones();
            int areaX = left + 4;
            int areaY = top + 14;
            int areaW = bw - 8;
            int areaH = bh - 26;

            if (tones.isEmpty()) {
                g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                        "未检测到波形", areaX + 4, areaY + areaH / 2 - 4, 0xFF888888);
                return;
            }

            // 波形区
            g.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0xFF0A0E12);
            g.hLine(areaX, areaX + areaW, areaY + areaH / 2, 0xFF22303A);

            // 采样窗口（按时间记录：最近检测时间窗口）
            double[][] win = OscilloscopeStore.windowSamples();
            double[] ts = win[0];
            double[] vs = win[1];
            double maxPeak = OscilloscopeStore.compositeRms() * 2.5;
            for (OscilloscopeStore.Tone t : tones) maxPeak = Math.max(maxPeak, t.amp * 1.1);
            if (maxPeak <= 1e-6) maxPeak = 1.0;

            if (ts.length >= 2) {
                double t0 = ts[0];
                double tEnd = ts[ts.length - 1];
                double span = Math.max(tEnd - t0, 1e-9);
                int py = Integer.MIN_VALUE;
                for (int i = 0; i < ts.length; i++) {
                    int x = areaX + (int) Math.round((ts[i] - t0) / span * areaW);
                    int y = areaY + areaH / 2 - (int) Math.round(vs[i] / maxPeak * areaH / 2);
                    // 每列一个竖 quad（替代逐像素 Bresenham fill，绘制调用数降 2~3 个数量级）
                    if (py != Integer.MIN_VALUE) {
                        g.vLine(x, Math.min(py, y), Math.max(py, y), 0xFFFFFFFF);
                    }
                    py = y;
                }
            }
            // 合成 RMS
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    "RMS " + String.format("%.2fV", OscilloscopeStore.compositeRms()),
                    areaX + 2, areaY + areaH - 10, 0xFF88FF88);
        } catch (Throwable ignored) {
        }
    }

}
