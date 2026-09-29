package com.hdf.cryptand.neoforge.dynamic.style;

import com.hdf.cryptand.dynamic.api.CoreHost;
import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.DynamicCoreSpi;
import com.hdf.cryptand.dynamic.api.ExecutorPlan;
import com.hdf.cryptand.dynamic.api.FormatProbe;
import com.hdf.cryptand.dynamic.api.LoadWindow;
import com.hdf.cryptand.dynamic.api.Source;
import com.hdf.cryptand.dynamic.api.Stage;
import com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandStylePlugin;
import com.hdf.cryptand.neoforge.soc.ui.plugin.StyleHost;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ===== 样式动态核心（第二个具体核心，2026-09-29）=====
 *
 * <p>目的有两个：① 让插件能带来<b>自包含样式</b>（jar 内资源不被 MC 索引，所以走
 * {@code Stylesheet.parse(String)}）；② <b>验伪抽象通用性</b> —— 与 {@code ui} 核心相比，
 * 本核心不碰 UI 树、不建面板，只把 {@code .lss} 文本变成可取名引用的样式表，
 * 证明"框架 + 具体核心"这套抽象不是为 UI 量身定做的。</p>
 *
 * <ul>
 *   <li>识别：jar 内带 {@code META-INF/services/...CryptandStylePlugin}；</li>
 *   <li>装配：解析为 {@link Stylesheet} 存入 {@link UiStyles}，由 {@code CoreHost.own} 登记回收；</li>
 *   <li>阶段：{@link LoadWindow#CLIENT_SETUP}（LDLib2 样式表属于客户端 UI 栈）。</li>
 * </ul>
 */
public final class DynamicStyleCore implements DynamicCoreSpi<CryptandStylePlugin> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return "style";
    }

    @Override
    public Set<String> suffixes() {
        return Set.of(".jar", ".zip");
    }

    @Override
    public Class<CryptandStylePlugin> pluginType() {
        return CryptandStylePlugin.class;
    }

    @Override
    public LoadWindow window() {
        return LoadWindow.CLIENT_SETUP;
    }

    /** 与 ui 核心同构：并行探测/解包，LOAD 串行（插件静态初始化），装配回主线程（登记表 + LDLib2）。 */
    @Override
    public ExecutorPlan plan() {
        return new ExecutorPlan(EnumSet.of(Stage.DISCOVER, Stage.PROBE, Stage.EXTRACT), false, true);
    }

    @Override
    public boolean canHandle(Source src, FormatProbe probe) {
        return probe.zip() && probe.hasEntry(DynamicApi.servicePath(CryptandStylePlugin.class));
    }

    @Override
    public void attach(CryptandStylePlugin plugin, CoreHost host) {
        final Set<String> names = new LinkedHashSet<>();
        plugin.loadStyles(new StyleHost() {
            @Override
            public void registerStyle(String name, String lss) {
                if (name == null || name.isBlank() || lss == null || lss.isBlank()) {
                    return;
                }
                if (names.contains(name)) {
                    host.warn("样式重名（后者覆盖）：" + name);
                }
                final Stylesheet sheet = Stylesheet.parse(lss);
                UiStyles.put(name, sheet);
                names.add(name);
                LOGGER.info("[dynamic] 样式已登记：{}（插件 {}，core=style，注册表现有 {} 项）",
                        name, plugin.id(), UiStyles.size());
                host.own("style:" + name, () -> UiStyles.remove(name));
            }
        });
    }

    @Override
    public void detach(CryptandStylePlugin plugin, CoreHost host) {
        // 全部资源都以 host.own 登记，宿主统一回收；这里无需额外动作（与 ui 核心同规矩）。
    }
}
