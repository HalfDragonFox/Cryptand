/**
 * ===== 线程池负载 HUD（2026-08-29 用户：左上角显示每个线程池每线程负载） =====
 *
 * 内核 debug 模式：{@code com.hdf.cryptand.neoforge.core.config.ConfigThreading.ENABLE_THREAD_POOL_HUD} 开启时，在屏幕
 * 左上角逐行绘制【每个线程池】中【每个线程】的负载情况：
 * <ul>
 *   <li>虚拟线程使用量/最大值（vt use/max）；</li>
 *   <li>每秒处理的任务数量（x.x/s，惰性 1s 窗口差分）；</li>
 *   <li>累计处理任务数（proc）与当前状态（idle/busy）。</li>
 * </ul>
 * 数据来自 common 线程分发核心：{@code ThreadDispatchers.pools()} 遍历全部
 * 已创建的池 + {@code ThreadDispatcher.workerSummaryLines()} 每 Worker 一行。
 * 由 {@link ThreadPoolHudMixin}（@Mixin Gui）在 HUD 渲染时调用（左上角）。
 */

package com.hdf.cryptand.neoforge.core.client;

import com.hdf.cryptand.neoforge.threading.config.ConfigThreading;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Collection;

public final class ThreadPoolDebugHud {

    private ThreadPoolDebugHud() {
    }

    /** 距左上角的缩进（识别 DEBUG 面板不挡住原版左上角信息） */
    private static final int X = 4;
    private static final int Y = 4;

    /** HUD 渲染（主线程 render 调用；开关关闭则静默） */
    public static void render(GuiGraphics g) {
        try {
            if (!ConfigThreading.ENABLE_THREAD_POOL_HUD.get()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) return; // 大屏打开时跳过（与示波器 HUD 一致）
            var font = mc.font;
            Collection<ThreadDispatcher> pools = ThreadDispatchers.pools();
            if (pools.isEmpty()) {
                g.drawString(font, "[INC] thread pools: (none)", X, Y, 0xFFAAAAAA, false);
                return;
            }
            int y = Y;
            for (ThreadDispatcher pool : pools) {
                // 线程数显示【实际使用】数量，不是全部核心（2026-08-29 用户）
                g.drawString(font,
                        String.format("[INC] %s  threads=%-3d/%-3d maxVt=%d load=%d bal=%dms",
                                pool.name(), pool.usedThreadCount(), pool.maxThreads(),
                                pool.maxVirtualThreads(), pool.totalLoad(),
                                pool.balanceIntervalMs()),
                        X, y, 0xFFFFFF55, false);
                y += 9;
                for (String line : pool.workerSummaryLines()) {
                    g.drawString(font, line, X + 6, y, 0xFFFFFFFF, false);
                    y += 9;
                }
                y += 2;
            }
        } catch (Throwable ignored) {
        }
    }
}