package com.hdf.cryptand.dynamic.core;

import com.hdf.cryptand.dynamic.api.DynamicApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 插件元数据（免类加载读取）：{@code META-INF/cryptand-dynamic/plugin.properties}。
 *
 * <p>用 properties 而不是 json —— common 侧零第三方依赖（json 库不在 common 依赖里），
 * {@link Properties} 是 JDK 自带，且足够表达这些扁平字段。</p>
 *
 * <p>字段：{@code id}、{@code version}、{@code apiVersion}、{@code mainClass}、
 * {@code core}（可选，指定目标核心）、{@code depends}（逗号分隔的插件 id）。</p>
 */
public record PluginMeta(String id, String version, int apiVersion, String mainClass,
                         String coreId, List<String> depends) {

    /** 无元数据（回退到 ServiceLoader 反射读 id）。 */
    public static final PluginMeta NONE = new PluginMeta(null, null, 0, null, null, List.of());

    public boolean present() {
        return id != null && !id.isBlank();
    }

    public static PluginMeta read(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zip.getEntry(DynamicApi.PLUGIN_META);
            if (entry == null) {
                return NONE;
            }
            final Properties p = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                p.load(in);
            }
            final List<String> deps = new ArrayList<>();
            final String raw = p.getProperty("depends", "");
            for (final String part : raw.split(",")) {
                final String d = part.trim();
                if (!d.isEmpty()) {
                    deps.add(d);
                }
            }
            return new PluginMeta(
                    p.getProperty("id"),
                    p.getProperty("version"),
                    parseInt(p.getProperty("apiVersion"), 1),
                    p.getProperty("mainClass"),
                    p.getProperty("core"),
                    List.copyOf(deps));
        } catch (IOException ex) {
            return NONE;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
