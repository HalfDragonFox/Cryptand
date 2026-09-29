package com.hdf.cryptand.neoforge.dynamic.style;

import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态样式表注册表（样式核心的产出面）。
 *
 * <p>插件退役时由 {@code CoreHost.own} 的释放动作移除 ⇒ 不留残影（与 {@code UiTagRegistry} 同规矩）。</p>
 */
public final class UiStyles {

    private static final Map<String, Stylesheet> STYLES = new ConcurrentHashMap<>();

    private UiStyles() {
    }

    public static void put(String name, Stylesheet sheet) {
        if (name != null && !name.isBlank() && sheet != null) {
            STYLES.put(name, sheet);
        }
    }

    public static void remove(String name) {
        if (name != null) {
            STYLES.remove(name);
        }
    }

    public static Stylesheet get(String name) {
        return name == null ? null : STYLES.get(name);
    }

    /** 便捷：按名取多份（缺的跳过），可直接喂给 {@code UiHost.registerPanel(..., sheets)}。 */
    public static Stylesheet[] get(String... names) {
        if (names == null || names.length == 0) {
            return new Stylesheet[0];
        }
        final java.util.List<Stylesheet> out = new java.util.ArrayList<>(names.length);
        for (final String n : names) {
            final Stylesheet s = get(n);
            if (s != null) {
                out.add(s);
            }
        }
        return out.toArray(new Stylesheet[0]);
    }

    public static Set<String> names() {
        return Set.copyOf(STYLES.keySet());
    }

    public static int size() {
        return STYLES.size();
    }
}
