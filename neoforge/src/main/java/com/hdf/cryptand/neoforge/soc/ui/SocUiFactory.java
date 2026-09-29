package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.soc.download.SocDownloadUi;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

/**
 * ===== SoC 面板工厂（无上下文构建，供自动化/测试复用）=====
 *
 * <p>给 UITest 场景与 aiauto 的程序化打开提供"不需要玩家交互就能构建面板"的入口。
 * 只覆盖<b>不依赖容器数据</b>的界面（如工具链下载面板）；芯片机箱/组装台依赖方块实体，
 * 必须走游戏内正常入口。</p>
 */
public final class SocUiFactory {

    private SocUiFactory() {
    }

    /** 工具链下载面板（纯客户端，可无玩家上下文构建） */
    public static ModularUI downloadPanel() {
        final SocDownloadUi ui = new SocDownloadUi();
        final UIElement panel = ui.buildPanel();
        return ModularUI.of(SocUiKit.createUI(panel));
    }

    /**
     * 硬件接线画布（连接器右键机箱打开的那一页，2026-09-29 改成 EDA 画布）。
     *
     * <p>统一入口：页面数据（设备 + 接口点 + 已连线）在连接器物品的 NBT 里
     * （服务端 {@code SocPairingData.collect} 写入），所以这里只需要那次打开时的 holder；
     * 画布本身登记在 {@code hw_config} 名下（{@code SocietyUiInspector.remember}），
     * aiauto 可据此导出布局树/截图（SocUiInspector.remember）。</p>
     */
    public static ModularUI hardwarePanel(com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType.HeldItemUIHolder holder) {
        return HardwareConfigUi.build(holder);
    }
}
