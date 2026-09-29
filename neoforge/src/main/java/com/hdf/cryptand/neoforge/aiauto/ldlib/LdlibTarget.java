package com.hdf.cryptand.neoforge.aiauto.ldlib;

import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.AiTarget;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * ===== aiauto 的 ldlib 目标：LDLib2 界面自动化流水线 =====
 *
 * <p>把 {@link LdlibPanels} 适配成通用 {@link AiTarget}，于是
 * {@code /cryptand aiauto ldlib …} 就能对任何登记过的 LDLib2 界面做：
 * 导出布局树、截图、程序化打开、打开调试器。</p>
 *
 * <h3>登记（一行接入）</h3>
 * <pre>
 *   LdlibPanels.register("download", panel, true, SocUiKit.stylesheets());
 * </pre>
 */
public final class LdlibTarget implements AiTarget {

    public static final LdlibTarget INSTANCE = new LdlibTarget();

    private LdlibTarget() {
    }

    @Override
    public String id() {
        return "ldlib";
    }

    @Override
    public String displayName() {
        return "LDLib2 界面（布局树 + 截图 + UIDebugger）";
    }

    /** LDLib2 是硬依赖，客户端恒可用 */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<String> items() {
        return LdlibPanels.names();
    }

    @Override
    public void tick() {
        LdlibPanels.tick();
    }

    @Override
    public Path dump(String item) {
        try {
            return LdlibPanels.dump(item);
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public boolean open(String item) {
        return LdlibPanels.open(item);
    }

    @Override
    public boolean shot(String item) {
        if (!LdlibPanels.onScreen()) {
            return false;                    // 界面不在屏幕上时截到的会是世界画面
        }
        AiAutomation.grabScreenshot(id(), item);
        return true;
    }

    @Override
    public boolean ready(String item) {
        return LdlibPanels.onScreen();
    }

    @Override
    public boolean debug() {
        return LdlibPanels.debug();
    }

    @Override
    public String describe(String item) {
        return LdlibPanels.root(item) == null
                ? "界面 " + item + "（尚未登记：先在游戏里打开一次）"
                : "界面 " + item + "（已登记，可 dump / shot / open / capture）";
    }
}
