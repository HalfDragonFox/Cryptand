package com.hdf.cryptand.neoforge.soc.ui.plugin;

import com.hdf.cryptand.dynamic.api.DynamicPlugin;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

/**
 * ===== 动态 UI 插件接口（SPI，2026-09-29）=====
 *
 * <p>用户定案：「可以做真 Java 插件，一步到位」——UI 打成 jar，运行期由
 * {@link UiPluginLoader} 用独立 {@code URLClassLoader} 加载；重载 = 卸载旧类加载器 + 重新加载 +
 * 重建树（JVM 不能换类，所以旧类实例一律丢弃）。</p>
 *
 * <p>jar 里必须有 {@code META-INF/services/com.hdf.cryptand.neoforge.soc.ui.plugin.CryptandUiPlugin}
 * 指向实现类（ServiceLoader 约定）。插件**只能用公开 API**（LDLib2 + 我们的 public 类），
 * 不要塞影子类 —— 固化进 mod 源码时才不会撞名。</p>
 */
public interface CryptandUiPlugin extends DynamicPlugin {

    /** 唯一 id（重载/卸载按它找）。规范：{@code <组>:<界面名>} —— 组用 modid 风格（小写、点分），
     *  界面名用小写/连字符；兼容裸名（无冒号 ⇒ 组为 {@code default}）。 */
    String id();

    /** 组名（= 所属 mod / UI 包组，便于识别与分组重载）。默认取 {@link #id()} 的冒号前缀。 */
    default String group() {
        return groupOf(id());
    }

    /** 界面名（不含组前缀）。默认取 {@link #id()} 的冒号后缀。 */
    default String name() {
        return nameOf(id());
    }

    /** 由 id 解析组名：{@code "tacz:gun"} → {@code "tacz"}；裸名 → {@code "default"}。 */
    static String groupOf(String id) {
        final int i = id == null ? -1 : id.indexOf(':');
        return i < 0 ? "default" : id.substring(0, i);
    }

    /** 由 id 解析界面名：{@code "tacz:gun"} → {@code "gun"}；裸名原样。 */
    static String nameOf(String id) {
        final int i = id == null ? -1 : id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    /** 显示名（默认同 id）。 */
    default String label() {
        return id();
    }

    /** 加载期：往宿主登记面板与自有 XML 标签。 */
    default void load(UiHost host) {
    }

    /**
     * 构建 UI 树的根（可为 null —— 若已在 {@link #load} 里登记了面板，就不必再给）。
     */
    UIElement buildRoot(UiContext ctx);

    /** 卸载期：释放自己的资源（宿主会负责注销面板与关闭类加载器）。 */
    default void unload() {
    }
}
