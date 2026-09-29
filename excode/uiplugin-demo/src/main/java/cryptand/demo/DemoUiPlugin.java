package cryptand.demo;

import com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandUiPlugin;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiContext;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiHost;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.network.chat.Component;

/**
 * ===== 示例动态 UI 插件（2026-09-29）=====
 *
 * <p>演示"动态原型 → 静态固化"工作流：这个类的源码先以 jar 形式被运行期加载
 * （{@code /cryptand aiauto ldlib plugin load <jar>}），改完 reload 即生效；
 * 稳定后把源码原样搬进 mod 源码树、去掉 jar 加载即可。</p>
 */
public class DemoUiPlugin implements CryptandUiPlugin {

    @Override
    public String id() {
        return "demo-ui";
    }

    @Override
    public String label() {
        return "示例动态 UI";
    }

    @Override
    public void load(UiHost host) {
        host.registerPanel(id(), this::build, true);
    }

    @Override
    public UIElement buildRoot(UiContext ctx) {
        return null;   // 面板已在 load() 里登记
    }

    /** 面板内容：每次 reload 都会被重新调用（旧类实例作废，所以状态不要存在控件里）。 */
    private UIElement build() {
        final UIElement root = new UIElement();
        root.layout(l -> l.width(280).height(110));

        final Label title = new Label();
        title.setText(Component.literal("动态 UI 插件：demo-ui"));
        root.addChild(title);

        final Label stamp = new Label();
        stamp.setText(Component.literal("本次构建于 " + java.time.LocalTime.now().withNano(0)));
        root.addChild(stamp);

        final Button click = new Button();
        click.setText(Component.literal("点我（验证事件绑定）"));
        click.setOnClick(e -> stamp.setText(Component.literal("点过了 " + System.currentTimeMillis())));
        root.addChild(click);

        return root;
    }
}
