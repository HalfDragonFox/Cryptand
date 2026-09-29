package cryptand.demo;

import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandUiPlugin;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiContext;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiHost;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.network.chat.Component;

/**
 * ===== 复验用「越权插件」（2026-09-29）=====
 *
 * <p>用途：真机验证 {@code UiPluginLoader.retire} 的 P2b 检测。它在自己的 {@code unload()} 里
 * {@code LdlibPanels.unregister(自己的面板名)} —— 而热重载时该名字已被新插件接管 ⇒
 * 宿主必须让这次越权**可见**（LOGGER.warn），不许静默。</p>
 *
 * <p>它不是示例，只是回归材料：单独打成 {@code crit-ui.jar}。</p>
 */
public class CriticalUiPlugin implements CryptandUiPlugin {

    @Override
    public String id() {
        return "crit-ui";
    }

    @Override
    public String label() {
        return "复验越权插件";
    }

    @Override
    public void load(UiHost host) {
        host.registerPanel(id(), this::build, true);
    }

    @Override
    public UIElement buildRoot(UiContext ctx) {
        return null;
    }

    @Override
    public void unload() {
        LdlibPanels.unregister(id());          // 越权：注销面板本该由宿主负责
    }

    private UIElement build() {
        final UIElement root = new UIElement();
        root.layout(l -> l.width(200).height(60));
        final Label title = new Label();
        title.setText(Component.literal("越权插件 crit-ui " + java.time.LocalTime.now().withNano(0)));
        root.addChild(title);
        return root;
    }
}
