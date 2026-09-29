package com.hdf.cryptand.neoforge.soc.ui.plugin;

import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * ===== 动态 UI 插件加载器（2026-09-29，C 路线）=====
 *
 * <p>用户定案：「可以做真 Java 插件，一步到位」「给 ldlib 增加动态接口，然后只需要加载 jar 重载即可」。</p>
 *
 * <p>做法（静态审查结论为依据）：</p>
 * <ol>
 *   <li>外部 jar **不注册成 mod、不放 mods/**，用 {@code URLClassLoader(parent = 本 mod 类加载器)}
 *       加载 ⇒ 插件能看见 MC/LDLib2/我们的 API，FML 不拦；代价是插件类不吃 mixin/AT/&#64;OnlyIn 剥离。</li>
 *   <li>插件**只能用现成控件**（不需要重扫 LDLib2 注册表）；要定义新 XML 标签 ⇒ 走
 *       {@link UiHost#registerTag}，由宿主元素的 {@code parseXmlChildElement} 接管。</li>
 *   <li>只在客户端加载（服务端没有 UI；服务端不构造加载器就不会缺类崩）。</li>
 *   <li>重载 = 卸载（注销面板 + 关类加载器）+ 重新加载（旧类实例一律丢弃）。</li>
 * </ol>
 */
public final class UiPluginLoader {

    /** 一个已加载的插件。 */
    public record Loaded(String id, String label, Path jar, URLClassLoader loader,
                         CryptandUiPlugin plugin, List<String> panels,
                         Map<String, Supplier<UIElement>> tags) {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Loaded> LOADED = new LinkedHashMap<>();

    private UiPluginLoader() {
    }

    /** 加载 jar（失败抛异常，调用方给玩家提示）。 */
    public static synchronized Loaded load(Path jar, UiContext ctx) {
        return load(jar, ctx, null);
    }

    /**
     * 加载 jar 的实现。
     *
     * @param allowSameId 允许覆盖的插件 id（{@code null} = 不允许冲突）。热重载传自己的旧 id：
     *                    此时旧项仍在 {@code LOADED} 表里（reload 是先 load 再退役旧插件），
     *                    没有这个白名单会把热重载自己打断（只读静态审查 P2a）。
     */
    private static synchronized Loaded load(Path jar, UiContext ctx, String allowSameId) {
        if (!FMLEnvironment.dist.isClient()) {
            throw new IllegalStateException("UI 插件只在客户端加载（服务端没有 UI）");
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("找不到插件 jar：" + jar);
        }
        try {
            final URLClassLoader loader = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()}, CryptandUiPlugin.class.getClassLoader());
            final Iterator<CryptandUiPlugin> it =
                    ServiceLoader.load(CryptandUiPlugin.class, loader).iterator();
            if (!it.hasNext()) {
                loader.close();
                throw new IllegalStateException("jar 里没有 META-INF/services/"
                        + CryptandUiPlugin.class.getName());
            }
            final CryptandUiPlugin plugin = it.next();
            if (it.hasNext()) {
                loader.close();
                throw new IllegalStateException("一个 jar 只允许一个插件实现（发现多个 "
                        + CryptandUiPlugin.class.getName() + "）");
            }
            // P2a：id 冲突必须显式拒绝 —— 否则两个 jar 声明同一 id 时后者静默覆盖前者：
            // 前者的面板永不注销、类加载器永不关、unload/reload 再也找不到它。
            final Loaded conflict = LOADED.get(plugin.id());
            if (conflict != null && !plugin.id().equals(allowSameId)) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // 关不掉就算了（下面照样抛冲突）
                }
                throw new IllegalStateException("插件 id 冲突：" + plugin.id() + " 已由 "
                        + conflict.jar().getFileName() + " 占用（先 unload 它，或让新 jar 用不同 id）");
            }
            final List<String> panels = new ArrayList<>();
            // 同名面板被**接管前**的原登记：加载失败要原样还回去（界面内容还原、不关屏），
            // 不能把正在显示的界面留在半截状态
            final Map<String, LdlibPanels.Panel> takenOver = new LinkedHashMap<>();
            final Map<String, Supplier<UIElement>> tags = new LinkedHashMap<>();
            final UiHost host = new UiHost() {
                @Override
                public void registerPanel(String name, Supplier<UIElement> build, boolean autoCapture,
                                          Stylesheet... sheets) {
                    final LdlibPanels.Panel previous = LdlibPanels.panel(name);
                    if (previous != null) {
                        takenOver.putIfAbsent(name, previous);
                    }
                    // panels 先记（只读静态审查 P1）：registerBuilt 清子后 builder.fill 抛异常时，
                    // catch 必须知道这个名字要回滚；后记会漏掉「正在登记的那一个」，
                    // 屏上 root 已被清空却没人重填 = 半死界面。
                    panels.add(name);
                    LdlibPanels.registerBuilt(name, root -> root.addChild(build.get()), autoCapture, sheets);
                }

                @Override
                public void registerTag(String tag, Supplier<UIElement> factory) {
                    tags.put(tag, factory);
                }
            };
            try {
                plugin.load(host);
                final UIElement root = plugin.buildRoot(ctx);
                if (root != null && panels.isEmpty()) {
                    final String name = plugin.id();
                    final LdlibPanels.Panel previous = LdlibPanels.panel(name);
                    if (previous != null) {
                        takenOver.putIfAbsent(name, previous);
                    }
                    panels.add(name);                                    // 先记，理由同 P1a
                    LdlibPanels.registerBuilt(name, r -> r.addChild(root), true);
                }
            } catch (Throwable t) {
                // 失败必须**完整回收**：接管过的登记回滚成原样，新登记的面板注销
                //（否则闭包钉住旧类、unload 永远 false），类加载器要关（否则 jar 句柄泄漏）
                // ——审查 2026-09-29 第 1/5 条。
                for (final String panel : panels) {
                    try {
                        final LdlibPanels.Panel previous = takenOver.get(panel);
                        if (previous != null) {
                            LdlibPanels.restore(previous);
                        } else {
                            LdlibPanels.unregister(panel);
                        }
                    } catch (Throwable ignored) {
                        // 清理尽力而为
                    }
                }
                try {
                    loader.close();
                } catch (Throwable ignored) {
                    // 同上
                }
                throw t;
            }
            final Loaded loaded = new Loaded(plugin.id(), plugin.label(), jar, loader, plugin,
                    List.copyOf(panels), Map.copyOf(tags));
            LOADED.put(plugin.id(), loaded);
            LOGGER.info("[uiPlugin] 已加载 {}（{}），面板={}", plugin.id(), jar.getFileName(), panels);
            return loaded;
        } catch (IOException ex) {
            throw new IllegalStateException("加载插件失败：" + ex, ex);
        } catch (Error err) {
            // ServiceConfigurationError 是 Error，不是 IOException（审查第 5 条）
            throw new IllegalStateException("加载插件失败（配置/链接错误）：" + err, err);
        }
    }

    /** 卸载：先注销它登记的面板（正显示它 ⇒ 关屏；**先于**插件自清理，顺序理由见 {@link #retire}），
     *  再让插件清理自己的资源，最后关类加载器。 */
    public static synchronized boolean unload(String id) {
        final Loaded loaded = LOADED.remove(id);
        if (loaded == null) {
            return false;
        }
        retire(loaded, List.of());
        LOGGER.info("[uiPlugin] 已卸载 {}", id);
        return true;
    }

    /**
     * 热重载：**先让新 jar 接管同名面板**（复用同一个 root ⇒ 已打开的界面不关、内容就地换掉），
     * 再退役旧插件。
     *
     * <p>顺序是根因所在：旧实现 {@code unload(id) → load(jar)} 里，unload 会走
     * {@link LdlibPanels#unregister} 见到面板正显示就 {@code mc.setScreen(null)} 关屏；
     * 而且即使不关屏，新 load 也会 new 一个新 root，屏幕绑的却是旧 root ⇒ 新树永远不上屏
     *（2026-09-22 真机实测：reload 后 {@code screen.change （无）}、新构建的标签从未出现）。
     * 现在「复用 root」由 {@link LdlibPanels#registerBuilt} 的同名接管保证。</p>
     */
    public static synchronized boolean reload(String id, UiContext ctx) {
        final Loaded old = LOADED.get(id);
        if (old == null) {
            return false;
        }
        final Path jar = old.jar();
        final Loaded fresh = load(jar, ctx, old.id());    // allowSameId=旧 id：放行「自己换自己」（P2a）
        retire(old, fresh.panels());                      // 只注销新插件不再提供的面板
        if (!old.id().equals(fresh.id())) {
            LOADED.remove(old.id());                      // 插件改了 id：清掉旧键
        }
        LOGGER.info("[uiPlugin] 已热重载 {}（新 jar 接管，界面未关）", id);
        return true;
    }

    /**
     * 让一个插件退役：调它自己的 {@code unload()}、注销它**不在 {@code keepPanels} 里**的面板、
     * 关掉类加载器（{@link #unload} 与 {@link #reload} 共用这一条路径）。
     *
     * @param keepPanels 已被新插件接管的同名面板 —— 不能注销，否则会把刚接管的界面关掉
     */
    private static void retire(Loaded loaded, List<String> keepPanels) {
        // **先收回宿主授权，再让插件清理自己的资源**（只读静态审查 P2b）：
        // LdlibPanels 是 public，插件若在自己的 unload() 里 unregister 面板名，会注销掉
        // **新插件刚接管**的同名登记 ⇒ 命中 unregister 的关屏分支，把刚热重载的界面关掉
        //（修复目标被反噬），且 fresh.panels() 与 PANELS 不一致却仍打印成功日志。
        for (final String panel : loaded.panels()) {
            if (!keepPanels.contains(panel)) {
                LdlibPanels.unregister(panel);
            }
        }
        try {
            loaded.plugin().unload();
        } catch (Throwable t) {
            LOGGER.warn("[uiPlugin] {} 的 unload() 抛异常：{}", loaded.id(), t.toString());
        }
        // 越权检测（P2b 的第二半）：插件的 unload() 若自己调 LdlibPanels.unregister(自己的面板名)，
        // 会把**新插件刚接管**的同名登记注销掉（界面被关，fresh.panels() 与 PANELS 还不一致）。
        // 已关的屏幕无法无损恢复 ⇒ 至少必须让这件事可见，不许静默。
        for (final String panel : keepPanels) {
            if (LdlibPanels.panel(panel) == null) {
                LOGGER.warn("[uiPlugin] 插件 {} 退役时越权注销了已由新插件接管的面板 {}"
                        + "（契约：注销面板由宿主负责，插件只清理自己的资源）", loaded.id(), panel);
            }
        }
        try {
            loaded.loader().close();
        } catch (IOException ignored) {
            // 关不掉就算了：类加载器会被 GC
        }
    }

    public static synchronized List<String> ids() {
        return new ArrayList<>(LOADED.keySet());
    }

    public static synchronized Loaded get(String id) {
        return LOADED.get(id);
    }

    /** 插件登记的自有 XML 标签（宿主解析时查）。 */
    public static synchronized Supplier<UIElement> tagFactory(String tag) {
        for (final Loaded loaded : LOADED.values()) {
            final Supplier<UIElement> f = loaded.tags().get(tag);
            if (f != null) {
                return f;
            }
        }
        return null;
    }
}
