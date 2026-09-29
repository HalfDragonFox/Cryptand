package com.hdf.cryptand.neoforge.dynamic.ui;

import com.hdf.cryptand.dynamic.api.CoreHost;
import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.DynamicCoreSpi;
import com.hdf.cryptand.dynamic.api.ExecutorPlan;
import com.hdf.cryptand.dynamic.api.FormatProbe;
import com.hdf.cryptand.dynamic.api.LoadWindow;
import com.hdf.cryptand.dynamic.api.Source;
import com.hdf.cryptand.dynamic.api.Stage;
import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandUiPlugin;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiContext;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiHost;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ===== LDLib2 UI 动态核心（2026-09-29）=====
 *
 * <p>用户要求：「完成后写 ldlib 的 UI 动态核心」。本类是<b>具体核心</b>：
 * 基础框架负责发现/依赖/并发/生命周期/内存，本类只负责"UI 插件怎么接进 LDLib2"。</p>
 *
 * <ul>
 *   <li>识别：jar 内带 {@code META-INF/services/...CryptandUiPlugin}（由注解处理器生成）；</li>
 *   <li>装配：插件登记的面板走 {@link LdlibPanels#registerBuilt} —— <b>同名接管语义天然成立</b>
 *       （复用同一 root、清子重填 ⇒ 热重载不关屏、不闪，这是之前修好的 P1 行为）；</li>
 *   <li>释放：每个面板/标签都用 {@code CoreHost.own} 登记，退役时由宿主统一回收；
 *       接管时框架会自动跳过"已被新实例接管"的资源 ⇒ 不会把刚装配的界面拆掉（P2b 由框架兜住）；</li>
 *   <li>阶段：{@link LoadWindow#CLIENT_SETUP}（LDLib2 界面只能在客户端就绪后建）。</li>
 * </ul>
 */
public final class DynamicUiCore implements DynamicCoreSpi<CryptandUiPlugin> {

    private final Supplier<UiContext> contextSupplier;

    public DynamicUiCore(Supplier<UiContext> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    @Override
    public String id() {
        return "ui";
    }

    @Override
    public Set<String> suffixes() {
        return Set.of(".jar", ".zip");
    }

    @Override
    public Class<CryptandUiPlugin> pluginType() {
        return CryptandUiPlugin.class;
    }

    @Override
    public LoadWindow window() {
        return LoadWindow.CLIENT_SETUP;
    }

    /** 并行探测/解包；LOAD 串行（插件静态初始化可能触碰 MC）；装配回主线程（LDLib2 UI 树）。 */
    @Override
    public ExecutorPlan plan() {
        return new ExecutorPlan(EnumSet.of(Stage.DISCOVER, Stage.PROBE, Stage.EXTRACT), false, true);
    }

    @Override
    public boolean canHandle(Source src, FormatProbe probe) {
        return probe.zip() && probe.hasEntry(DynamicApi.servicePath(CryptandUiPlugin.class));
    }

    @Override
    public void attach(CryptandUiPlugin plugin, CoreHost host) {
        final UiContext ctx = contextSupplier == null ? null : contextSupplier.get();
        final Set<String> panels = new LinkedHashSet<>();
        final UiHost uiHost = new UiHost() {
            @Override
            public void registerPanel(String name, Supplier<UIElement> build, boolean autoCapture,
                                      Stylesheet... sheets) {
                panels.add(name);
                // registerBuilt：同名接管（复用 root + clearAllExternalChildren）⇒ reload 不关屏
                LdlibPanels.registerBuilt(name, root -> root.addChild(build.get()), autoCapture, sheets);
                host.own("panel:" + name, () -> LdlibPanels.unregister(name));
            }

            @Override
            public void registerTag(String tag, Supplier<UIElement> factory) {
                UiTagRegistry.put(tag, factory);
                host.own("tag:" + tag, () -> UiTagRegistry.remove(tag));
            }
        };
        plugin.load(uiHost);
        // 没在 load() 里登记面板的插件：用插件 id 建一个面板（root = buildRoot 的返回值）
        final UIElement root = plugin.buildRoot(ctx);
        if (root != null && panels.isEmpty()) {
            final String name = plugin.id();
            LdlibPanels.registerBuilt(name, r -> r.addChild(root), true);
            host.own("panel:" + name, () -> LdlibPanels.unregister(name));
        }
    }

    @Override
    public void detach(CryptandUiPlugin plugin, CoreHost host) {
        // 只清理插件自己的资源；面板/标签的注销由宿主按 own 登记统一执行（顺序由框架保证）
        plugin.unload();
    }
}
