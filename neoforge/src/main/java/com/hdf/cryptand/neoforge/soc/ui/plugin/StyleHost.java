package com.hdf.cryptand.neoforge.soc.ui.plugin;

/**
 * 样式核心交给样式插件的登记面（与 {@code UiHost} 对称）。
 *
 * <p>只表达一件事：把一段 <b>自包含的 LSS 文本</b>登记成一个可取名引用的样式表。
 * 之所以是"文本"而不是资源路径：插件 jar 内的资源<b>不会</b>被 MC 的资源管理器索引
 * （资源包列表在启动期定死），所以样式必须由插件自己带进来。</p>
 */
public interface StyleHost {

    /**
     * 登记一份样式。
     *
     * @param name 样式名（插件内唯一；重名以最后一次为准并给出警告）
     * @param lss  LSS 文本（由 LDLib2 的 {@code Stylesheet.parse(String)} 解析）
     */
    void registerStyle(String name, String lss);
}
