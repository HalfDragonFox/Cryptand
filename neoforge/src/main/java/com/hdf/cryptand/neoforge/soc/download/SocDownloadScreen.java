package com.hdf.cryptand.neoforge.soc.download;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import net.minecraft.network.chat.Component;

/**
 * ===== 工具链下载屏幕（2026-09-15）=====
 *
 * <p>指令 {@code /cryptand soc download} 直接打开本屏幕；面板用 LDLib2 `modern` 样式表。
 * {@link #tick()} 每帧刷新进度（读异步下载器的快照，主线程零阻塞）。</p>
 */
public final class SocDownloadScreen extends ModularUIScreen {

    private final SocDownloadUi content;

    public SocDownloadScreen(SocDownloadUi content) {
        super(buildUi(content), Component.literal("Cryptand 工具链下载"));
        this.content = content;
    }

    private static ModularUI buildUi(SocDownloadUi content) {
        final UIElementHolder holder = new UIElementHolder();
        holder.element = content.buildPanel();
        return ModularUI.of(com.hdf.cryptand.neoforge.soc.ui.SocUiKit.createUI(holder.element));
    }

    @Override
    public void tick() {
        super.tick();
        try {
            content.refresh();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 构建期临时容器（避免 lambda 捕获问题） */
    private static final class UIElementHolder {
        com.lowdragmc.lowdraglib2.gui.ui.UIElement element;
    }
}
