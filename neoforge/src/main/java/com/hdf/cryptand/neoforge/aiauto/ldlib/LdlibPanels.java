package com.hdf.cryptand.neoforge.aiauto.ldlib;

import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== LDLib2 自动化流水线（aiauto 的 ldlib 目标）=====
 *
 * <p>把 LDLib2 界面的两样"AI 看不见"的东西导出成可读产物：</p>
 * <ul>
 *   <li><b>布局树</b>：Taffy 计算后的每元素 pos/size/文本 —— {@code latest-ldlib-<面板>.txt}</li>
 *   <li><b>真实截图</b>：MC 原生抓帧 —— {@code latest-ldlib-<面板>.png}（AI 可直接读图）</li>
 * </ul>
 *
 * <h3>两种驱动方式</h3>
 * <ol>
 *   <li><b>被动</b>：界面被打开（玩家右键/命令）→ {@link #tick()} 自动导出布局 + 截图；</li>
 *   <li><b>主动</b>：{@link #open(String)} 用登记时留存的样式表重建 ModularUI 并推上屏幕
 *       —— 不需要玩家操作，配合 {@link AiAutomation#startCapture} 形成"打开→等稳定→截图→还原"闭环。</li>
 * </ol>
 *
 * <p>⚠ 主动打开会复用登记时的 root（被新 ModularUI 重新绑定）：仅适合数据上下文弱的界面；
 * 需要真实容器数据的界面（如芯片机箱）应走游戏内正常入口。</p>
 */
public final class LdlibPanels {

    /**
     * 面板构建器：往**给定的 root** 里填内容。
     *
     * <p>为什么是"填"而不是"返回新 root"：动态插件重载（{@code rebuild}）要**复用同一个 root 对象**
     * （ModularUI 没有 replaceRoot），只换树内容 ⇒ 已打开的界面不关、不闪
     * （用户 2026-09-29 定案："刷新最好不要关闭整个 UI 再打开，否则会有闪烁"）。</p>
     */
    @FunctionalInterface
    public interface PanelBuilder {
        void fill(UIElement root);
    }

    /** 登记项：root + 是否自动捕获 + 重建用样式表 + 构建器（null = 固定 root，老用法） */
    public record Panel(String name, UIElement root, boolean autoCapture,
                        List<com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet> sheets,
                        PanelBuilder builder) {
    }

    /** 当前正在显示的面板名（rebuild/unregister 时要判断是否需要动屏幕） */
    private static volatile String openPanel = "";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Map<String, Panel> PANELS = new LinkedHashMap<>();
    private static final Map<String, Long> LAST_DUMP = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_TEXT = new ConcurrentHashMap<>();
    private static final Map<String, Integer> ZERO_FRAMES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_SHOT = new ConcurrentHashMap<>();
    private static final java.util.Set<String> SHOT_KEYS = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> DEGRADED = ConcurrentHashMap.newKeySet();

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_DEPTH = 24;
    private static final long THROTTLE_MS = 1000L;

    private LdlibPanels() {
    }

    // ==================== 登记 ====================

    public static void register(String name, UIElement root) {
        register(name, root, true);
    }

    public static void register(String name, UIElement root, boolean autoCapture){
        PANELS.put(name, new Panel(name, root, autoCapture, List.of(), null));
    }

    /** 连样式表一起登记 —— 只有这样 {@link #open(String)} 才能还原同样的外观 */
    public static void register(String name, UIElement root, boolean autoCapture,
                                com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet... sheets) {
        PANELS.put(name, new Panel(name, root, autoCapture, List.of(sheets), null));
    }

    /**
     * **工厂式登记**（动态插件/可重载界面用）：root 由 builder 填；{@link #rebuild(String)} 时
     * 对同一 root 清子重填 ⇒ 界面不关不闪。
     *
     * @return 建好的 root（调用方若需要可直接用）
     */
    public static UIElement registerBuilt(String name, PanelBuilder builder, boolean autoCapture,
                                          com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet... sheets) {
        final Panel previous = PANELS.get(name);
        // 同名登记 = **接管**：必须复用同一个 root 对象。
        // ModularUI 持有的是登记时那个 root（UIElement.getModularUI() 指回来），换新 root 只会让
        // 已打开的界面与新树脱钩 —— 这正是「plugin reload 关屏 + 面板内容不刷新」的根因
        //（2026-09-22 真机实测：reload 后 screen.change （无），且 new root 的标签从未上屏）。
        // 接管失败**必须显式拒绝**（只读静态审查 P3）：静默 new root 正是「新树永不上屏」的复现路径 ——
        // 屏幕仍握着旧 root、旧 root 继续冻结在活 ModularUI 里，而调用方拿不到任何信号还会打成功日志。
        // 宿主不留第二条路径（不做「关屏重开」兜底）：接管不了就报错给人看。
        final UIElement root;
        if (previous == null) {
            root = new UIElement();                                    // 首次登记
        } else {
            if (previous.builder() == null) {
                throw new IllegalStateException("无法就地接管面板 " + name
                        + "：它是旧式 register(...) 登记的面板（没有 builder，不能清子重填）");
            }
            if (!prepareRebind(previous.root())) {
                throw new IllegalStateException("无法就地接管面板 " + name
                        + "：它的 root 正被带菜单的 ModularUI 持有（ItemSlot 只增不减，不能原地清子）");
            }
            root = previous.root();
            root.clearAllExternalChildren();
        }
        builder.fill(root);
        PANELS.put(name, new Panel(name, root, autoCapture, List.of(sheets), builder));
        return root;
    }

    /** 原地重建：同一个 root 清子再填 ⇒ 已打开的界面不关、不闪（插件热重载用）。 */
    public static boolean rebuild(String name) {
        final Panel p = PANELS.get(name);
        if (p == null || p.builder() == null || !prepareRebind(p.root())) {
            return false;
        }
        // 只清我们的外部子元素，别动控件内部元素（审查第 7 条）
        p.root().clearAllExternalChildren();
        p.builder().fill(p.root());
        return true;
    }

    /**
     * 原地清子重填前的判定（{@link #registerBuilt} 接管与 {@link #rebuild} 共用同一套规则）。
     *
     * <p>依据 LDLib2 源码：{@code UIElement.getModularUI()} 给出当前持有该元素的 ModularUI
     * （{@code @Getter @Nullable private ModularUI modularUI}），{@code ModularUI.getMenu()} 由
     * Lombok 暴露。</p>
     *
     * @return false = 该 root 正被**带菜单**的 ModularUI 持有 ⇒ 不能原地清
     *         （ItemSlot 只往 menu.slots 里加、没有移除 API —— uiloader-static-review 第 3 条），
     *         调用方必须走关屏重开
     */
    private static boolean prepareRebind(UIElement root) {
        final com.lowdragmc.lowdraglib2.gui.ui.ModularUI holder = root.getModularUI();
        if (holder == null) {
            return true;                                  // 没上屏：清子不会伤到任何屏幕
        }
        if (holder.getMenu() != null) {
            return false;
        }
        // 清了子元素但焦点仍指着被删元素 ⇒ 键盘事件照派给它（审查第 2 条）
        holder.clearFocus();
        return true;
    }

    /** 取登记项（插件加载时用来记录接管前状态，失败后回滚）。 */
    public static Panel panel(String name) {
        return PANELS.get(name);
    }

    /**
     * 回滚一次接管：登记恢复成 {@code previous}，并用它自己的 builder 重填**同一个 root**
     * ⇒ 界面回到接管前的内容且不关屏（插件加载失败路径专用）。
     */
    public static void restore(Panel previous) {
        if (previous == null) {
            return;
        }
        PANELS.put(previous.name(), previous);
        if (previous.builder() != null) {
            previous.root().clearAllExternalChildren();
            previous.builder().fill(previous.root());
        }
    }

    /** 注销登记（卸载插件用）：从表里移除；若正显示它，先把屏幕关掉再移除。 */
    public static void unregister(String name) {
        if (PANELS.remove(name) == null) {
            return;
        }
        LAST_DUMP.remove(name);
        LAST_SHOT.remove(name);
        SHOT_KEYS.remove(name);
        DEGRADED.remove(name);
        ZERO_FRAMES.remove(name);
        if (name.equals(openPanel)) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ModularUIScreen) {
                mc.setScreen(null);
            }
            openPanel = "";
        }
    }

    /** 面板是否有构建器（能 rebuild）。 */
    public static boolean rebuildable(String name) {
        final Panel p = PANELS.get(name);
        return p != null && p.builder() != null;
    }

    public static List<String> names() {
        return new ArrayList<>(PANELS.keySet());
    }

    public static UIElement root(String name) {
        final Panel p = PANELS.get(name);
        return p == null ? null : p.root();
    }

    /** 当前屏幕是否是 LDLib2 界面（截图/调试器的前提） */
    public static boolean onScreen() {
        return Minecraft.getInstance().screen instanceof ModularUIScreen;
    }

    // ==================== 每帧驱动 ====================

    public static void tick() {
        for (String name : names()) {
            autoDump(name);
            autoShot(name);
        }
    }

    // ==================== 布局树 ====================

    private static void autoDump(String name) {
        final UIElement root = root(name);
        if (root == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = LAST_DUMP.get(name);
        if (last != null && now - last < THROTTLE_MS) {
            return;
        }
        try {
            final boolean zero = root.getSizeWidth() <= 0 || root.getSizeHeight() <= 0;
            if (zero && ZERO_FRAMES.getOrDefault(name, 0) <= 600) {
                ZERO_FRAMES.merge(name, 1, Integer::sum);
                return;                                  // 尚未布局，等下一帧
            }
            if (zero && DEGRADED.add(name)) {
                LOGGER.warn("[aiauto/ldlib] 面板 {} 布局尺寸为 0（{}x{}）——已导出诊断快照",
                        name, Math.round(root.getSizeWidth()), Math.round(root.getSizeHeight()));
            }
            LAST_DUMP.put(name, now);
            final String text = describe(root, name);
            if (text.equals(LAST_TEXT.get(name))) {
                return;                                  // 布局没变，不重复写盘
            }
            LAST_TEXT.put(name, text);
            Files.createDirectories(AiAutomation.outDir());
            Files.writeString(artifact(name, "txt"), text, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 自动化设施绝不拖垮界面
        }
    }

    /** 导出单个面板布局（并保留时间戳副本） */
    public static Path dump(String name) throws IOException {
        final UIElement root = root(name);
        if (root == null) {
            throw new IOException("面板 " + name + " 尚未构建（先在游戏里打开一次）");
        }
        Files.createDirectories(AiAutomation.outDir());
        final String text = describe(root, name);
        Files.writeString(artifact(name, "txt"), text, StandardCharsets.UTF_8);
        Files.writeString(AiAutomation.outDir().resolve(
                        "ldlib-" + name + "-" + LocalDateTime.now().format(STAMP) + ".txt"),
                text, StandardCharsets.UTF_8);
        LAST_TEXT.put(name, text);
        return artifact(name, "txt");
    }

    /** 导出全部已登记面板 */
    public static Path dumpAll() throws IOException {
        if (PANELS.isEmpty()) {
            throw new IOException("还没有登记任何界面（先在游戏里打开一次）");
        }
        Files.createDirectories(AiAutomation.outDir());
        for (String name : names()) {
            try {
                dump(name);
            } catch (IOException ignored) {
            }
        }
        return AiAutomation.outDir();
    }

    private static Path artifact(String name, String ext) {
        return AiAutomation.artifact("ldlib", name, ext);
    }

    // ==================== 截图 ====================

    private static void autoShot(String name) {
        final Panel panel = PANELS.get(name);
        if (panel == null || !panel.autoCapture() || !AiAutomation.allowed()) {
            return;
        }
        if (!com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto.autoCapture()) {
            return;
        }
        final UIElement root = panel.root();
        if (root.getSizeWidth() <= 0 || root.getSizeHeight() <= 0 || !onScreen()) {
            return;                                      // 不在屏幕上时抓到的会是世界画面
        }
        final String key = name + "#" + Math.round(root.getSizeWidth()) + "x"
                + Math.round(root.getSizeHeight()) + "#" + LAST_TEXT.getOrDefault(name, "").hashCode();
        if (!SHOT_KEYS.add(key)) {
            return;                                      // 这个布局已经抓过
        }
        final long now = System.currentTimeMillis();
        final Long last = LAST_SHOT.get(name);
        if (last != null && now - last < 2000) {
            return;                                      // 节流 2s
        }
        LAST_SHOT.put(name, now);
        AiAutomation.grabScreenshot("ldlib", name);
    }

    // ==================== 主动打开 / 调试器 ====================

    /** 用登记时留存的样式表重建 ModularUI 并推到屏幕上（不需要玩家操作） */
    public static boolean open(String name) {
        final Panel panel = PANELS.get(name);
        if (panel == null) {
            return false;
        }
        try {
            final var sheets = panel.sheets();
            final var ui = sheets.isEmpty()
                    ? com.lowdragmc.lowdraglib2.gui.ui.UI.of(panel.root())
                    : com.lowdragmc.lowdraglib2.gui.ui.UI.of(panel.root(),
                            sheets.toArray(new com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet[0]));
            final Minecraft mc = Minecraft.getInstance();
            final var mui = mc.player == null
                    ? com.lowdragmc.lowdraglib2.gui.ui.ModularUI.of(ui)
                    : com.lowdragmc.lowdraglib2.gui.ui.ModularUI.of(ui, mc.player);
            mc.setScreen(new ModularUIScreen(mui, Component.literal("aiauto: " + name)));
            openPanel = name;
            LOGGER.info("[aiauto/ldlib] 已程序化打开界面 {}", name);
            return true;
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto/ldlib] 打开界面 {} 失败：{}", name, ex.toString());
            return false;
        }
    }

    /** 打开 LDLib2 自带 UIDebugger（层级树 / 计算样式 / 盒模型高亮） */
    public static boolean debug() {
        if (Minecraft.getInstance().screen instanceof ModularUIScreen screen) {
            screen.getModularUI().enableDebugger(true);
            return true;
        }
        return false;
    }

    // ==================== 文本生成 ====================

    /**
     * 布局文本：缩进树 + 每元素计算后 pos/size + 文本内容。
     *
     * <pre>
     * 0 UIElement panel_bg pos=(0,0) size=(700,300)
     * 1   UIElement card pos=(6,6) size=(688,120)
     * 2     Label "▌ 工具链下载" pos=(11,11) size=(88,9)
     * </pre>
     */
    public static String describe(UIElement root, String name) {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("# Cryptand aiauto — ldlib 布局树（").append(name).append("）\n");
        sb.append("# 生成于 ").append(LocalDateTime.now().format(STAMP)).append('\n');
        sb.append("# 字段：depth 元素名 [文本] pos=(x,y) size=(宽,高)  ← Taffy 计算后的最终值\n");
        sb.append("# 自查：size=0 → 被挤没；子 x+宽 > 父宽 → 溢出；y 相同 → 同一行；换行 → 该行放不下\n");
        walk(root, 0, sb);
        return sb.toString();
    }

    private static void walk(UIElement element, int depth, StringBuilder sb) {
        if (element == null || depth > MAX_DEPTH) {
            return;
        }
        try {
            sb.append(depth).append(' ').append("  ".repeat(Math.min(depth, MAX_DEPTH)));
            String type = null;
            try {
                type = element.getElementName();
            } catch (Throwable ignored) {
            }
            if (type == null || type.isBlank()) {
                type = element.getClass().getSimpleName();
            }
            sb.append(type);
            if (element instanceof Label label) {
                final Component value = label.getValue();
                final String text = value == null ? "" : value.getString();
                if (!text.isEmpty()) {
                    sb.append("  \"").append(text.replace("\n", " ")).append('"');
                }
            }
            sb.append("  pos=(").append(Math.round(element.getPositionX())).append(',')
                    .append(Math.round(element.getPositionY())).append(')');
            sb.append(" size=(").append(Math.round(element.getSizeWidth())).append(',')
                    .append(Math.round(element.getSizeHeight())).append(')');
            sb.append('\n');
            for (UIElement child : element.getChildren()) {
                walk(child, depth + 1, sb);
            }
        } catch (Throwable ignored) {
            // 单元素异常不影响整体导出
        }
    }
}
