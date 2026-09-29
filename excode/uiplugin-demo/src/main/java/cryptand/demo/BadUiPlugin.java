package cryptand.demo;

import com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandUiPlugin;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiContext;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiHost;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

/**
 * ===== 复验用「坏插件」（2026-09-29）=====
 *
 * <p>用途：真机验证 {@code UiPluginLoader} 的**失败回滚**（只读静态审查 P1）。
 * 它抢占 {@code demo-ui} 的面板名，并在 {@code build()} 里抛异常 ⇒ 期望宿主把旧 demo-ui 面板
 * 原样重填回**同一个 root**（界面自愈、不关屏、不留半死空白界面）。</p>
 *
 * <p>它不是示例，只是回归材料：单独打成 {@code bad-ui.jar}（services 只声明本类）。</p>
 */
public class BadUiPlugin implements CryptandUiPlugin {

    @Override
    public String id() {
        return "bad-ui";
    }

    @Override
    public String label() {
        return "复验坏插件（build 抛异常）";
    }

    @Override
    public void load(UiHost host) {
        host.registerPanel("demo-ui", this::build, true);      // 故意抢 demo-ui 的名字
    }

    @Override
    public UIElement buildRoot(UiContext ctx) {
        return null;                                            // 面板已在 load() 里登记
    }

    private UIElement build() {
        throw new IllegalStateException("复验用：故意在 build() 里抛异常（验证失败回滚）");
    }
}
