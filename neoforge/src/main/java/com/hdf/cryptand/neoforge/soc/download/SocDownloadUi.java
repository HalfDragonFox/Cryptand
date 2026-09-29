package com.hdf.cryptand.neoforge.soc.download;

import com.hdf.cryptand.neoforge.soc.config.ConfigSoc;
import com.hdf.cryptand.neoforge.soc.ui.SocUiKit;
import com.hdf.cryptand.toolchain.CToolDownload;
import com.hdf.cryptand.toolchain.CToolDownloader;
import com.hdf.cryptand.toolchain.CToolDownloads;
import com.hdf.cryptand.toolchain.CToolMirror;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 工具链下载面板（LDLib2，2026-09-15 傻瓜式版）=====
 *
 * <p><b>设计目标（用户原话）："列出所有工具，然后点击是否下载之类的，傻瓜式，然后可以选择镜像站防止下载卡顿"。</b>
 * 因此本面板：</p>
 * <ol>
 *   <li><b>列出所有工具</b>：遍历 {@link CToolDownloads#toolIds()}，本平台没有内置地址的也列出并标注（灰显），
 *       不让用户面对"空列表"猜原因；</li>
 *   <li><b>点击即勾选</b>：每行左侧一个 24px 勾选按钮（✔ / ☐），点一下切换，不需要理解任何术语；</li>
 *   <li><b>一键选择</b>：全选 / 推荐（本平台可下载的）/ 清空；</li>
 *   <li><b>镜像站</b>：◀ ▶ 切换内置镜像（官方直连 / ghfast.top / gh-proxy.com / ghproxy.net / gh.moeyy.xyz），
 *       配置里填了 {@code downloadUrlOverride} 时额外出现"自定义镜像"项，切一次全局生效；</li>
 *   <li><b>并行多线程</b>：每个工具一条独立线程（{@link SocDownloadManager} 按 toolId 持有），右栏逐行显示进度，
 *       关页面不断，重开恢复。</li>
 * </ol>
 */
public final class SocDownloadUi {

    /** 目标端 */
    public enum Side {
        CLIENT("客户端（本机）"), SERVER("服务端（需 OP + 服务端允许）");

        private final String label;

        Side(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final String platform;
    /** 工具选中状态（可多选并行） */
    private final Map<String, Boolean> selected = new LinkedHashMap<>();
    private final Map<String, Label> toolLabels = new LinkedHashMap<>();
    private final Map<String, Button> toolChecks = new LinkedHashMap<>();
    private final List<Label> progressLines = new ArrayList<>();

    /** 镜像候选（内置 + 可选自定义） */
    private final List<CToolMirror> mirrors = new ArrayList<>();
    private int mirrorIndex;

    private final Label headerState;
    private final Label targetLabel;
    private final Label mirrorLabel;
    private final ProgressBar progressBar;

    private volatile Side side = Side.CLIENT;

    public SocDownloadUi() {
        this(CToolDownloads.currentPlatform());
    }

    public SocDownloadUi(String platform) {
        this.platform = platform;
        for (String toolId : CToolDownloads.toolIds()) {
            selected.put(toolId, "riscv-gcc".equals(toolId));   // 默认勾选固件工具链（最常用）
        }
        this.mirrors.addAll(CToolMirror.builtin());
        final String override = ConfigSoc.downloadUrlOverride();
        if (override != null && !override.isBlank()) {
            this.mirrors.add(CToolMirror.custom(override));
        }
        // 恢复全局已选镜像（重开面板保持一致）
        final CToolMirror current = SocDownloadManager.mirror();
        for (int i = 0; i < mirrors.size(); i++) {
            if (mirrors.get(i).id().equalsIgnoreCase(current.id())
                    && mirrors.get(i).prefix().equals(current.prefix())) {
                mirrorIndex = i;
                break;
            }
        }
        SocDownloadManager.setMirror(mirrors.get(mirrorIndex));

        this.headerState = new Label().setValue(Component.literal("就绪").withStyle(ChatFormatting.GRAY));
        this.targetLabel = new Label().setValue(Component.literal(targetText()).withStyle(ChatFormatting.AQUA));
        this.mirrorLabel = new Label().setValue(mirrorText());   // mirrorText() 内部已 withStyle
        this.progressBar = new ProgressBar().setRange(0f, 1f);
        this.progressBar.layout(l -> l.widthPercent(100).height(8));   // 与 cryptand.lss 的 progress-bar:host 一致
        try {
            this.progressBar.setProgress(0f);
        } catch (Throwable ignored) {
        }
        ServerDownloadClient.query();
    }

    public UIElement buildPanel() {
        // ==================== 标题栏 ====================
        final UIElement header = SocUiKit.titleBar(SocUiKit.title("▌ 工具链下载"), headerState);

        // ==================== 左栏：工具清单（全部工具 + 点击勾选 + 一键选择） ====================
        final UIElement toolsBox = new UIElement();
        toolsBox.layout(l -> l.widthPercent(100).gapAll(4));
        for (String toolId : CToolDownloads.toolIds()) {
            toolsBox.addChild(buildToolRow(toolId));
        }

        final Button pickRecommended = new Button().setText(Component.literal("推荐"))
                .setOnClick(e -> {
                    for (String toolId : selected.keySet()) {
                        selected.put(toolId, CToolDownloads.forPlatform(toolId, platform) != null);
                    }
                    refreshToolLines();
                });
        final Button pickAll = new Button().setText(Component.literal("全选"))
                .setOnClick(e -> {
                    for (String toolId : selected.keySet()) {
                        selected.put(toolId, CToolDownloads.forPlatform(toolId, platform) != null);
                    }
                    refreshToolLines();
                });
        final Button pickNone = new Button().setText(Component.literal("清空"))
                .setOnClick(e -> {
                    for (String toolId : selected.keySet()) {
                        selected.put(toolId, false);
                    }
                    refreshToolLines();
                });
        final UIElement quickRow = SocUiKit.row(pickRecommended, pickAll, pickNone);

        final UIElement toolsGroup = SocUiKit.group("工具清单（点一下勾选，可多选并行）", toolsBox, quickRow);

        // ==================== 左栏：镜像站（防卡顿） ====================
        final Button mirrorPrev = new Button().setText(Component.literal("◀"))
                .setOnClick(e -> shiftMirror(-1));
        final Button mirrorNext = new Button().setText(Component.literal("▶"))
                .setOnClick(e -> shiftMirror(1));
        final UIElement mirrorRow = SocUiKit.row(mirrorPrev, mirrorNext);
        final UIElement mirrorGroup = SocUiKit.group("镜像站（GitHub 直连慢就换一个）", mirrorRow, mirrorLabel);

        // ==================== 左栏：目标端 ====================
        final Selector<Side> sideSelector = new Selector<Side>()
                .setCandidates(List.of(Side.CLIENT, Side.SERVER))
                .setSelected(Side.CLIENT)
                .setOnValueChanged(v -> {
                    side = v == null ? Side.CLIENT : v;
                    targetLabel.setValue(Component.literal(targetText()).withStyle(ChatFormatting.AQUA));
                });
        final UIElement targetGroup = SocUiKit.group("目标端", sideSelector, targetLabel);

        final UIElement left = new UIElement();
        // ⚠ 用百分比而非固定宽度：面板宽会被 GUI Scale/屏幕尺寸钳制，
        //   写死 340+340 在窄屏下会横向溢出、左右被裁（2026-09-15 用户实测）
        left.layout(l -> l.widthPercent(49).gapAll(6).flexShrink(1));
        left.addChildren(toolsGroup, mirrorGroup, targetGroup);

        // ==================== 右栏：下载进度（每工具一行） ====================
        final UIElement progressBox = new UIElement();
        progressBox.layout(l -> l.widthPercent(100).gapAll(4));
        for (int i = 0; i < 7; i++) {
            final Label line = new Label().setValue(Component.literal(i == 0 ? "（无下载任务）" : "")
                    .withStyle(i == 0 ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
            progressLines.add(line);
            progressBox.addChild(line);
        }
        final Label hint = new Label().setValue(Component.literal(
                "关闭本页下载继续，重开即恢复显示").withStyle(ChatFormatting.DARK_GRAY));

        final UIElement right = new UIElement();
        right.layout(l -> l.widthPercent(49).gapAll(6).flexShrink(1));
        right.addChildren(SocUiKit.group("下载进度（每工具一行）", progressBox), hint,
                SocUiKit.group("总进度", progressBar));

        final UIElement body = SocUiKit.row(left, right);

        // ==================== 底部按钮行 ====================
        final Button start = new Button().setText(Component.literal("开始下载")).setOnClick(e -> startSelected());
        final Button cancelAll = new Button().setText(Component.literal("中断全部"))
                .setOnClick(e -> {
                    SocDownloadManager.cancelAll();
                    refresh();
                });
        final Button check = new Button().setText(Component.literal("检查工具"))
                .setOnClick(e -> {
                    com.hdf.cryptand.neoforge.soc.compile.ClientToolchainService.probeAsync(true, null);
                    headerState.setValue(Component.literal("正在检查工具链（后台）…")
                            .withStyle(ChatFormatting.YELLOW));
                });
        final Button clear = new Button().setText(Component.literal("清理已完成"))
                .setOnClick(e -> {
                    SocDownloadManager.clearFinished();
                    refresh();
                });
        final UIElement buttons = SocUiKit.row(start, cancelAll, check, clear);

        final UIElement panel = SocUiKit.panel(700, 300);
        panel.addChildren(header, SocUiKit.body(body), buttons);
        com.hdf.cryptand.neoforge.soc.ui.SocUiInspector.remember("download", panel);   // 登记给布局导出器
        return panel;
    }

    /** 单行工具：勾选按钮 + 名称/大小/状态 */
    private UIElement buildToolRow(String toolId) {
        final CToolDownload dl = CToolDownloads.forPlatform(toolId, platform);
        final Label line = new Label().setValue(toolLine(toolId, dl));
        toolLabels.put(toolId, line);
        final Button check = new Button().setText(checkMark(toolId))
                .setOnClick(e -> {
                    selected.put(toolId, !selected.getOrDefault(toolId, false));
                    // ⚠ 不能在 lambda 里引用尚未初始化的自身变量 check → 从 map 取
                    final Button self = toolChecks.get(toolId);
                    if (self != null) {
                        self.setText(checkMark(toolId));
                    }
                    line.setValue(toolLine(toolId, dl));
                    headerState.setValue(Component.literal(selectedCount() + " 个工具已选择")
                            .withStyle(ChatFormatting.GRAY));
                });
        check.layout(l -> l.width(24));
        toolChecks.put(toolId, check);

        final UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100).gapAll(6).flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        row.addChildren(check, line);
        return row;
    }

    // ==================== 镜像 ====================

    private void shiftMirror(int delta) {
        if (mirrors.isEmpty()) {
            return;
        }
        mirrorIndex = Math.floorMod(mirrorIndex + delta, mirrors.size());
        SocDownloadManager.setMirror(mirrors.get(mirrorIndex));
        mirrorLabel.setValue(mirrorText());
        headerState.setValue(Component.literal("镜像已切换：" + mirrors.get(mirrorIndex).displayName())
                .withStyle(ChatFormatting.AQUA));
    }

    private Component mirrorText() {
        final CToolMirror m = mirrors.isEmpty() ? CToolMirror.DIRECT : mirrors.get(mirrorIndex);
        return Component.literal(m.displayName() + (m.direct() ? "（GitHub 原地址）" : "  " + m.prefix()))
                .withStyle(ChatFormatting.AQUA);
    }

    // ==================== 每帧刷新 ====================

    public void refresh() {
        if (side == Side.SERVER) {
            headerState.setValue(Component.literal(ServerDownloadClient.stateLine())
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            final int running = SocDownloadManager.runningCount();
            headerState.setValue(Component.literal(running > 0
                    ? "下载中 " + running + " 个任务（并行）"
                    : "就绪").withStyle(running > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }

        final List<String> lines = SocDownloadManager.statusLines();
        for (int i = 0; i < progressLines.size(); i++) {
            final Label label = progressLines.get(i);
            if (i < lines.size() && !lines.get(i).isBlank()) {
                label.setValue(Component.literal(lines.get(i)).withStyle(ChatFormatting.AQUA));
            } else {
                label.setValue(Component.literal(i == 0 && lines.isEmpty() ? "（无下载任务）" : "")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        // 工具行状态（下载中/完成）随任务变化
        refreshToolStates();

        double sum = 0;
        int n = 0;
        for (CToolDownloader task : SocDownloadManager.tasks().values()) {
            final double r = task.progress().ratio();
            if (r >= 0) {
                sum += r;
                n++;
            }
        }
        try {
            progressBar.setProgress(n == 0 ? 0f : (float) (sum / n));
        } catch (Throwable ignored) {
        }
    }

    private void refreshToolLines() {
        for (Map.Entry<String, Label> e : toolLabels.entrySet()) {
            e.getValue().setValue(toolLine(e.getKey(), CToolDownloads.forPlatform(e.getKey(), platform)));
            final Button b = toolChecks.get(e.getKey());
            if (b != null) {
                b.setText(checkMark(e.getKey()));
            }
        }
    }

    private void refreshToolStates() {
        for (Map.Entry<String, Label> e : toolLabels.entrySet()) {
            final String toolId = e.getKey();
            final CToolDownloader task = SocDownloadManager.task(toolId);
            if (task != null && task.isRunning()) {
                e.getValue().setValue(toolLine(toolId, CToolDownloads.forPlatform(toolId, platform)));
            }
        }
    }

    // ==================== 动作 ====================

    private void startSelected() {
        final List<String> chosen = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : selected.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                chosen.add(e.getKey());
            }
        }
        if (chosen.isEmpty()) {
            headerState.setValue(Component.literal("请先点勾选至少一个工具").withStyle(ChatFormatting.RED));
            return;
        }
        if (side == Side.SERVER) {
            for (String toolId : chosen) {
                ServerDownloadClient.request(CToolDownloads.forPlatform(toolId, platform));
            }
            headerState.setValue(Component.literal("已请求服务端下载 " + chosen.size() + " 个工具")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        final Path dir = ConfigSoc.downloadTargetDir().isBlank()
                ? FMLPaths.GAMEDIR.get().resolve("cryptand").resolve("tool").resolve(platform)
                : Path.of(ConfigSoc.downloadTargetDir());
        int started = 0;
        for (String toolId : chosen) {
            final CToolDownload dl = CToolDownloads.forPlatform(toolId, platform);
            if (dl == null) {
                continue;   // 本平台无内置地址：跳过（行内已灰显说明）
            }
            SocDownloadManager.start(toolId, dl, dir);
            started++;
        }
        if (started == 0) {
            headerState.setValue(Component.literal("所选工具在当前平台没有可用地址")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        headerState.setValue(Component.literal("已启动 " + started + " 个并行下载（镜像："
                + SocDownloadManager.mirror().displayName() + "）").withStyle(ChatFormatting.GREEN));
        refresh();
    }

    private int selectedCount() {
        int n = 0;
        for (Boolean b : selected.values()) {
            if (Boolean.TRUE.equals(b)) {
                n++;
            }
        }
        return n;
    }

    /** 勾选标记（紧凑按钮，替代"选择/取消"长文本，避免中文换行） */
    private Component checkMark(String toolId) {
        final boolean on = selected.getOrDefault(toolId, false);
        return Component.literal(on ? "✔" : "☐")
                .withStyle(on ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY);
    }

    private Component toolLine(String toolId, CToolDownload dl) {
        final boolean on = selected.getOrDefault(toolId, false);
        final String name = CToolDownloads.displayName(toolId);
        final String size = dl == null ? "本平台无内置地址" : dl.sizeText();
        final CToolDownloader task = SocDownloadManager.task(toolId);
        final String state = task == null ? "" : "  ·  " + task.progress().state().label();
        return Component.literal(name + "  " + size + state)
                .withStyle(dl == null ? ChatFormatting.DARK_GRAY
                        : (on ? ChatFormatting.WHITE : ChatFormatting.GRAY));
    }

    private String targetText() {
        return side == Side.SERVER
                ? "目标：服务端 cryptand/tool/" + platform + "/"
                : "目标：本机 cryptand/tool/" + platform + "/";
    }
}