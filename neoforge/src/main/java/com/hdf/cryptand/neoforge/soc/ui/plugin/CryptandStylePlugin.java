package com.hdf.cryptand.neoforge.soc.ui.plugin;

import com.hdf.cryptand.dynamic.api.DynamicPlugin;

/**
 * ===== 样式插件契约（动态框架的第二个具体核心的插件面）=====
 *
 * <p>与 {@link CryptandUiPlugin} 对称：UI 插件管"界面树"，样式插件管"样式表"。
 * 同一个 jar 可以<b>同时</b>是 UI 插件与样式插件（各自的服务文件独立），
 * 这是"框架只提供土壤、具体核心各自装配"的直接体现。</p>
 */
public interface CryptandStylePlugin extends DynamicPlugin {

    /** 登记本插件的样式（由样式核心在 {@code attach} 时调用，主线程）。 */
    void loadStyles(StyleHost host);
}
