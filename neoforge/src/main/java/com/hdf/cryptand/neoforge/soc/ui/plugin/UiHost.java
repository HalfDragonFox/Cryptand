package com.hdf.cryptand.neoforge.soc.ui.plugin;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;

import java.util.function.Supplier;

/**
 * ===== 宿主交给插件的能力（2026-09-29）=====
 *
 * <p>插件不直接碰 aiauto 内部：要开面板/加 XML 标签，都从这里登记。</p>
 */
public interface UiHost {

    /**
     * 登记一个**可重载面板**：{@code build} 会在每次 reload 时被重新调用
     * （对同一个 root 清子重填 ⇒ 已打开的界面不关、不闪）。
     */
    void registerPanel(String name, Supplier<UIElement> build, boolean autoCapture, Stylesheet... sheets);

    /**
     * 登记一个**自有 XML 标签**：宿主元素的 {@code parseXmlChildElement} 会用它 ——
     * 这样 jar 里的新控件不必进 LDLib2 的注册表（那条路会 NPE：手工 Holder 没有 annotation）。
     */
    void registerTag(String tag, Supplier<UIElement> factory);
}
