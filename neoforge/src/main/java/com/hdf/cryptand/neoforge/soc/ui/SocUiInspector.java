package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * ===== SoC 面板接入 AI 自动化（薄委托）=====
 *
 * <p>实际实现在通用子包 {@code com.hdf.cryptand.neoforge.aiauto} 的 <b>ldlib 目标</b>
 * （{@link LdlibPanels}）里：布局树导出、截图、程序化打开、UIDebugger。
 * 本类只保留 SoC 侧的原有调用签名，三个面板的构建代码无需改动。</p>
 *
 * <p>产物（AI 直接读）：{@code <gameDir>/cryptand/ai-auto/latest-ldlib-<面板>.{txt,png}}</p>
 * <p>命令：{@code /cryptand aiauto ldlib list|dump|shot|open|capture|debug}</p>
 *
 * @deprecated 新界面请直接用 {@link LdlibPanels#register(String, UIElement, boolean, com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet...)}
 */
@Deprecated
public final class SocUiInspector {

    private SocUiInspector() {
    }

    public static void remember(String name, UIElement root) {
        // 连样式表一起登记：aiauto 才能在程序化打开时还原同样的外观
        LdlibPanels.register(name, root, true, SocUiKit.stylesheets());
    }

    public static void autoDump(String name) {
        LdlibPanels.tick();
    }

    public static void tick() {
        AiAutomation.tick();
    }

    public static Path dump(String name) throws IOException {
        return LdlibPanels.dump(name);
    }

    public static Path dumpAll() throws IOException {
        return LdlibPanels.dumpAll();
    }

    public static Path dumpDir() {
        return AiAutomation.outDir();
    }

    public static List<String> names() {
        return LdlibPanels.names();
    }

    public static UIElement root(String name) {
        return LdlibPanels.root(name);
    }

    public static String describe(UIElement root, String name) {
        return LdlibPanels.describe(root, name);
    }

    public static void dumpAndReport(String name) {
        try {
            final Path path = LdlibPanels.dump(name);
            final var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "[aiauto] 布局已导出：" + path), false);
            }
        } catch (Exception ignored) {
        }
    }
}
