package cryptand.demo;

import com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandStylePlugin;
import com.hdf.cryptand.neoforge.soc.ui.plugin.StyleHost;

/**
 * 样式插件示例：证明"框架 + 具体核心"对非 UI 类资源同样成立。
 *
 * <p>样式文本自包含（不引用 jar 内资源路径）—— 因为插件 jar 的资源不会被 MC 的资源管理器索引，
 * 只能由插件自己把 LSS 文本递进来，由核心用 {@code Stylesheet.parse(String)} 解析。</p>
 */
public final class DemoStylePlugin implements CryptandStylePlugin {

    @Override
    public String id() {
        return "demo-style";
    }

    @Override
    public void loadStyles(StyleHost host) {
        host.registerStyle("demo-style-probe", "#demo-style-probe {\n  background: #FF00AA;\n}\n");
    }
}
