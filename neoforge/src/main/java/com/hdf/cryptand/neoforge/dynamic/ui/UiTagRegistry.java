package com.hdf.cryptand.neoforge.dynamic.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * UI 插件登记的自有 XML 标签表（宿主元素的 {@code parseXmlChildElement} 解析时查）。
 *
 * <p>插件卸载时由 {@link DynamicUiCore} 通过 {@code CoreHost.own} 的释放动作移除 ⇒ 不留残影。</p>
 */
public final class UiTagRegistry {

    private static final Map<String, Supplier<UIElement>> TAGS = new ConcurrentHashMap<>();

    private UiTagRegistry() {
    }

    public static void put(String tag, Supplier<UIElement> factory) {
        if (tag != null && !tag.isBlank() && factory != null) {
            TAGS.put(tag, factory);
        }
    }

    public static void remove(String tag) {
        if (tag != null) {
            TAGS.remove(tag);
        }
    }

    public static Supplier<UIElement> get(String tag) {
        return TAGS.get(tag);
    }

    public static Set<String> tags() {
        return Set.copyOf(TAGS.keySet());
    }

    public static int size() {
        return TAGS.size();
    }
}
