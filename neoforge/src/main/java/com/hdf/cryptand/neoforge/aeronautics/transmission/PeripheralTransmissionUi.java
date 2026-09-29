/**
 * ===== 外设模拟传动器 · 绑定与曲线界面（2026-09-14）=====
 *
 * 用户定稿的形态：主界面只放"设备 + 波形图 + 保存/取消"，其余选项收进**子界面**
 * （"对于一些选项可以放到子UI，比如点击按钮出现子UI"），并且面板四周留白
 * （"上下左右必须有一块留白"）—— 参考 NeoECOAEExtension 的机器 UI：紧凑、不遮挡画面。
 *
 * <pre>
 *   ┌ 外设模拟传动器 · 曲线传动 ─────────────┐
 *   │ 外部设备            │ 摇杆曲线        │
 *   │  [设备列表]          │  [波形图]       │
 *   │  [设备设置…] ──┐     │  左键加点/拖动  │
 *   │                │     │  [重置曲线]     │
 *   │                │     │  当前：轴→输出  │
 *   │                ▼     │                 │
 *   │        ┌ 设备设置（子UI）────────┐     │
 *   │        │ 输入轴 / 轴反向 / 强制类型│    │
 *   │        │ 最小输入 / 最大输入      │     │
 *   │        │        [确定] [取消]     │     │
 *   │        └─────────────────────────┘     │
 *   │            [保存并应用] [取消]          │
 *   └────────────────────────────────────────┘
 * </pre>
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import com.hdf.cryptand.algorithm.curve.CurvePoint;
import com.hdf.cryptand.algorithm.curve.EditableCurve;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import com.hdf.cryptand.neoforge.core.ui.CreateUi;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class PeripheralTransmissionUi {

    private static final String K = "cryptand.peripheral_transmission.";
    /** 波形图高度（宽度按列宽自适应） */
    private static final int WAVE_H = 96;

    private PeripheralTransmissionUi() {
    }

    public static ModularUI build(BlockUIHolder holder, PeripheralTransmissionBlockEntity be) {
        PeripheralTransmissionBinding initial =
                be != null ? be.getBinding() : PeripheralTransmissionBinding.DEFAULT;
        BlockPos pos = holder.pos;

        final String[] deviceId = { initial.deviceId() };
        final int[] axisIndex = { initial.axisIndex() };
        final boolean[] invertAxis = { initial.invertAxis() };
        final double[] inMin = { initial.inMin() };
        final double[] inMax = { initial.inMax() };
        final int[] forceKind = { PeripheralForceKinds.clamp(initial.forceKind()) };
        final EditableCurve[] curve = { initial.curve() };

        Label status = CreateUi.label(Component.literal("—"), CreateUi.TEXT_DIM, 9f);
        // ⚠ 这两行说明文字必须允许换行：LDLib2 的 TextElement 默认不换行，
        //   长文本会直接把卡片撑宽（用户看到的就是"曲线图超出"——其实是卡片被撑开了）。
        Label live = CreateUi.label(Component.translatable(K + "live_idle"), CreateUi.TEXT_DIM, 9f);
        live.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true)
                .textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP));
        live.layout(l -> l.widthPercent(100));
        Label hint = CreateUi.label(Component.translatable(K + "curve_hint"), CreateUi.TEXT_DIM, 8f);
        hint.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true)
                .textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP));
        hint.layout(l -> l.widthPercent(100));

        final UIElement[] panelHolder = { null };
        final PeripheralTransmissionCurveCanvas[] canvasHolder = { null };

        // ===== 波形图 =====
        // 面板加宽：左列放设备列表（要能显示设备名），右列放波形（两侧各留 10px 不贴壁）
        // 列宽与其它外设（拉杆/船舵/栏杆）保持一致：176 + 190 —— 用户："设备部分保持和其他的差不多"
        final float leftW = CreateUi.fitWidth(176f);
        final float rightW = CreateUi.fitWidth(190f);
        final int waveW = (int) Math.max(110f, rightW - 24f);
        final PeripheralTransmissionCurveCanvas canvas = new PeripheralTransmissionCurveCanvas(
                waveW, WAVE_H, curve,
                () -> {
                    if (be != null) {
                        be.setBinding(bindingOf(deviceId[0], axisIndex[0], invertAxis[0],
                                inMin[0], inMax[0], curve[0], forceKind[0]));
                    }
                },
                index -> openPointMenu(panelHolder[0], curve, index,
                        () -> canvasHolder[0].syncFromModel()));
        canvasHolder[0] = canvas;

        // ===== 设备列表 =====
        ScrollerView deviceList = new ScrollerView();
        deviceList.layout(l -> l.widthPercent(100).height(54));
        deviceList.viewPort(v -> v.layout(l -> l.paddingAll(2)));
        deviceList.viewContainer(c -> c.layout(l -> l.gapAll(2)));
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            deviceList.clearAllScrollViewChildren();
            for (var d : PeripheralHelmInput.devices()) {
                boolean selected = d.id().equals(deviceId[0]);
                Button row = CreateUi.rowButton(selected, () -> {
                    deviceId[0] = d.id();
                    refresh[0].run();
                });
                // ⚠ 设备名必须自己加 Label —— rowButton 本身只是空按钮（漏了这里列表就是空白条）
                Label nameLabel = CreateUi.label(Component.literal(d.name()),
                        selected ? CreateUi.BRASS : CreateUi.TEXT, 9f);
                nameLabel.layout(l -> l.flexShrink(1).height(12).marginLeft(4));
                row.addChild(nameLabel);
                // 类型后缀（"Keyboard 键盘"）——与船舵/拉杆的列表写法一致
                Label kindLabel = CreateUi.label(
                        Component.translatable(kindKey(d.kind())), CreateUi.TEXT_DIM, 8f);
                kindLabel.layout(l -> l.height(12).marginLeft(4));
                row.addChild(kindLabel);
                deviceList.addScrollViewChild(row);
            }
            if (PeripheralHelmInput.devices().isEmpty()) {
                deviceList.addScrollViewChild(CreateUi.dim(K + "no_device"));
            }
        };
        refresh[0].run();

        // ===== 设备设置子界面 =====
        Button settingsBtn = CreateUi.smallButton(K + "device_settings", 96f, () ->
                openDeviceSettings(panelHolder[0], axisIndex, invertAxis, inMin, inMax, forceKind));
        settingsBtn.layout(l -> l.widthPercent(100).height(15));

        // ===== 连接 / 断开 / 刷新（对齐外设船舵的连接设置）=====
        Label linkLabel = CreateUi.label(Component.translatable(K + "link_idle"),
                CreateUi.TEXT_DIM, 8f);
        linkLabel.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        linkLabel.layout(l -> l.widthPercent(100));

        // 当前界面参数 → 绑定对象（连接 / 断开 / 保存共用）
        final java.util.function.Supplier<PeripheralTransmissionBinding> bindingSupplier =
                () -> bindingOf(deviceId[0], axisIndex[0], invertAxis[0], inMin[0], inMax[0],
                        curve[0], forceKind[0]);

        // 只渲染池内现有设备（用户要求："只有点刷新才会进行一次扫描"）
        Button refreshBtn = CreateUi.smallButton(K + "refresh", 46f, () -> {
            PeripheralHelmInput.requestScan();
            refresh[0].run();
        });
        // 连接：先【断开当前】再【连接新设备】—— 断开让设备池释放旧引用（引用计数归零
        // ⇒ 采样线程关闭旧句柄），随后才绑定新设备（与外设船舵同一套流程）
        Button connectBtn = CreateUi.smallButton(K + "connect", 52f, () -> {
            if (deviceId[0].isEmpty()) {
                linkLabel.setText(Component.translatable(K + "link_pick_first"));
                return;
            }
            String target = deviceId[0];
            deviceId[0] = "";
            PeripheralTransmissionBinding off = bindingSupplier.get();
            if (be != null) {
                be.setBinding(off);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionBindPayload.sendToServer(pos, off));

            deviceId[0] = target;
            PeripheralTransmissionBinding on = bindingSupplier.get();
            if (be != null) {
                be.setBinding(on);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionBindPayload.sendToServer(pos, on));
            linkLabel.setText(Component.translatable(K + "link_connecting"));
        });
        // 断开：清空设备选择并解绑（设备池随后关闭该设备）
        Button disconnectBtn = CreateUi.smallButton(K + "disconnect", 52f, () -> {
            deviceId[0] = "";
            PeripheralTransmissionBinding off = bindingSupplier.get();
            if (be != null) {
                be.setBinding(off);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionBindPayload.sendToServer(pos, off));
            linkLabel.setText(Component.translatable(K + "link_disconnected"));
            refresh[0].run();
        });
        UIElement linkRow = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(4));
        linkRow.addChildren(refreshBtn, connectBtn, disconnectBtn);
        refreshBtn.layout(l -> l.flex(1).height(14));
        connectBtn.layout(l -> l.flex(1).height(14));
        disconnectBtn.layout(l -> l.flex(1).height(14));

        Button resetBtn = CreateUi.smallButton(K + "reset", 78f, () -> {
            curve[0] = curve[0].reset();
            canvas.syncFromModel();
        });
        resetBtn.layout(l -> l.widthPercent(100).height(15));

        // ===== 参数与诊断（与外设船舵的设备卡片同构：连接按钮下方显示参数）=====
        // 只读摘要；真正的编辑入口是上面的【设备设置…】。每 tick 取真实值，不用界面局部状态。
        Label paramDevice = CreateUi.valueLabel(Component.literal("—"), 104f, CreateUi.BRASS, 9f);
        Label paramAxis = CreateUi.valueLabel(Component.literal("—"), 104f, CreateUi.BRASS, 9f);
        Label paramRange = CreateUi.valueLabel(Component.literal("—"), 104f, CreateUi.BRASS, 9f);
        // 后端诊断（SDL2 是否可用 / 失败原因）——"没有设备"时一眼看到根因，与其他外设一致
        Label diag = CreateUi.label(Component.literal(PeripheralHelmInput.diagnostic()),
                CreateUi.TEXT_DIM, 8f);
        diag.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        diag.layout(l -> l.widthPercent(100));

        UIElement leftCol = CreateUi.column(leftW,
                CreateUi.card(K + "section_device",
                        deviceList,
                        settingsBtn,
                        linkRow,
                        linkLabel,
                        CreateUi.key(K + "params_title", CreateUi.BRASS, 9f),
                        CreateUi.row(K + "param_device", paramDevice),
                        CreateUi.row(K + "axis", paramAxis),
                        CreateUi.row(K + "param_range", paramRange),
                        diag));
        UIElement rightCol = CreateUi.column(rightW,
                CreateUi.card(K + "section_curve", canvas, hint, resetBtn, live));

        // ===== 底部 =====
        Button save = CreateUi.button(K + "save", () -> {
            PeripheralTransmissionBinding binding = bindingOf(deviceId[0], axisIndex[0],
                    invertAxis[0], inMin[0], inMax[0], curve[0], forceKind[0]);
            if (be != null) {
                be.setBinding(binding);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionBindPayload.sendToServer(pos, binding));
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        });
        save.layout(l -> l.flex(1).height(16));
        Button cancel = CreateUi.button(K + "cancel", () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        });
        cancel.layout(l -> l.flex(1).height(16));

        // 宽度固定；高度自适应 —— 内容已经压得很小，自适应高度才是"刚好包住内容"，
        // 写死高度反而会在内容稍多时溢出（实测"保存/取消被顶出面板"）。
        UIElement panel = CreateUi.panel(CreateUi.panelWidthFor(leftW, rightW));
        panelHolder[0] = panel;
        UIElement content = CreateUi.content(panel);
        content.addChildren(
                CreateUi.titleBar(Component.translatable(K + "title"), status),
                CreateUi.body(leftCol, rightCol),
                CreateUi.buttonRow(save, cancel));

        // ===== 每 tick：实时输出 + 设备列表签名（节流）=====
        final String[] shownSig = { "" };
        final long[] lastRebuild = { 0L };
        panel.addEventListener(UIEvents.TICK, ev -> {
            String sig = signature();
            long now = System.currentTimeMillis();
            if (!sig.equals(shownSig[0]) && now - lastRebuild[0] >= 300L) {
                shownSig[0] = sig;
                lastRebuild[0] = now;
                refresh[0].run();
            }
            // 标题栏右侧状态（此前定义了却从未更新，永远显示 "—"）
            if (be == null) {
                status.setText(Component.translatable(K + "status_idle"));
                status.textStyle(ts -> ts.textColor(CreateUi.TEXT_DIM));
            } else if (!be.isClientDeviceConnected()) {
                status.setText(Component.translatable(K + "status_idle"));
                status.textStyle(ts -> ts.textColor(CreateUi.TEXT_WARN));
            } else {
                status.setText(Component.translatable(K + "status_ready"));
                status.textStyle(ts -> ts.textColor(CreateUi.TEXT_GOOD));
            }
            // 连接提示同样取【真实状态源】，否则关掉界面再打开会退回"需要连接"
            if (be == null || deviceId[0].isEmpty()) {
                linkLabel.setText(Component.translatable(K + "link_idle"));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_DIM));
            } else if (be.isClientDeviceConnected()) {
                linkLabel.setText(Component.translatable(K + "link_connected"));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_GOOD));
            } else {
                linkLabel.setText(Component.translatable(K + "link_connecting"));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_WARN));
            }
            // 参数摘要（只读；取值全来自真实配置/设备池，不是界面局部缓存）
            paramDevice.setText(Component.literal(deviceKindText(deviceId[0])));
            paramAxis.setText(Component.literal(axisNameOf(axisIndex[0])
                    + (invertAxis[0] ? "  (inv)" : "")));
            paramRange.setText(Component.literal(numText(inMin[0]) + " ~ " + numText(inMax[0])));
            if (be == null) {
                return;
            }
            if (!be.isClientDeviceConnected()) {
                live.setText(Component.translatable(K + "live_idle"));
                live.textStyle(ts -> ts.textColor(CreateUi.TEXT_DIM));
            } else {
                live.setText(Component.translatable(K + "live",
                        String.format("%.2f", be.getClientAxisRaw()),
                        String.format("%.0f", be.getClientRatio() * 100f)));
                live.textStyle(ts -> ts.textColor(CreateUi.TEXT_GOOD));
            }
        });

        return new ModularUI(UI.of(panel), holder.player);
    }

    /** 设备表指纹（节流用，避免每 tick 重建列表） */
    private static String signature() {
        StringBuilder sb = new StringBuilder();
        for (var d : PeripheralHelmInput.devices()) {
            sb.append(d.id()).append('|');
        }
        return sb.toString();
    }

    /**
     * 设备设置子界面（用户："对于一些选项可以放到子UI，比如点击按钮出现子UI"）：
     * 输入轴 / 轴反向 / 强制设备类型 / 输入范围都搬到这里，主面板因此只留设备列表 + 波形。
     */
    private static void openDeviceSettings(UIElement root, int[] axisIndex, boolean[] invertAxis,
                                           double[] inMin, double[] inMax, int[] forceKind) {
        if (root == null) {
            return;
        }
        // 轴名与船舵/拉杆/按键版传动器共用同一张表（CreateUi.AXIS_NAMES）——
        // 以前这里是 "Axis 0..7"，和其它外设对不上（用户实测截图要求统一）
        List<String> axisNames = CreateUi.AXIS_NAMES;
        Selector<String> axisSelector = new Selector<>();
        axisSelector.setCandidates(axisNames);
        axisSelector.setSelected(axisNames.get(Math.max(0, Math.min(7, axisIndex[0]))), false);
        axisSelector.setOnValueChanged(v -> axisIndex[0] = Math.max(0, axisNames.indexOf(v)));
        axisSelector.layout(l -> l.width(72).height(14));

        List<String> invertNames = List.of(
                Component.translatable("options.off").getString(),
                Component.translatable("options.on").getString());
        Selector<String> invertSelector = new Selector<>();
        invertSelector.setCandidates(invertNames);
        invertSelector.setSelected(invertNames.get(invertAxis[0] ? 1 : 0), false);
        invertSelector.setOnValueChanged(v -> invertAxis[0] = invertNames.indexOf(v) == 1);
        invertSelector.layout(l -> l.width(72).height(14));

        List<String> forceCandidates = new ArrayList<>();
        for (String key : PeripheralForceKinds.KEYS) {
            forceCandidates.add(Component.translatable(
                    PeripheralForceKinds.LANG_PREFIX + key).getString());
        }
        Selector<String> forceSelector = new Selector<>();
        forceSelector.setCandidates(forceCandidates);
        forceSelector.setSelected(forceCandidates.get(forceKind[0]), false);
        forceSelector.setOnValueChanged(v ->
                forceKind[0] = Math.max(0, forceCandidates.indexOf(v)));
        forceSelector.layout(l -> l.width(72).height(14));

        TextField minField = new TextField();
        minField.setNumbersOnlyDouble(-1.0E6, 1.0E6);
        minField.setText(String.valueOf(inMin[0]), false);
        minField.layout(l -> l.width(72).height(14));
        minField.setTextResponder(t -> {
            try {
                inMin[0] = Double.parseDouble(t.trim());
            } catch (Throwable ignored) {
            }
        });
        TextField maxField = new TextField();
        maxField.setNumbersOnlyDouble(-1.0E6, 1.0E6);
        maxField.setText(String.valueOf(inMax[0]), false);
        maxField.layout(l -> l.width(72).height(14));
        maxField.setTextResponder(t -> {
            try {
                inMax[0] = Double.parseDouble(t.trim());
            } catch (Throwable ignored) {
            }
        });

        // 内容区：每行 14px，5 行
        UIElement box = new UIElement().layout(l -> l.width(190)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN).gapAll(3));
        box.addChildren(
                CreateUi.row(K + "axis", axisSelector),
                CreateUi.row(K + "invert", invertSelector),
                CreateUi.row(K + "force_kind", forceSelector),
                CreateUi.row(K + "in_min", minField),
                CreateUi.row(K + "in_max", maxField));

        UIElement[] overlayRef = new UIElement[1];
        UIElement buttons = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(4));
        buttons.addChild(CreateUi.smallButton(K + "ok", 52f, () -> CreateUi.closeOverlay(root, overlayRef[0])));
        buttons.addChild(CreateUi.smallButton(K + "cancel", 52f, () -> CreateUi.closeOverlay(root, overlayRef[0])));

        UIElement card = new UIElement().layout(l -> l.width(206).paddingAll(6).gapAll(5)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN))
                .style(s -> s.background(CreateUi.bordered(CreateUi.PANEL_INNER, CreateUi.BRASS_DARK, 2)));
        card.addChild(CreateUi.key(K + "device_settings", CreateUi.BRASS, 10f));
        card.addChild(box);
        card.addChild(buttons);

        overlayRef[0] = CreateUi.overlay(root, card);
    }

    /** 右键子页面（用户："右键点可以设置删除与其他操作（通过子页面）"） */
    private static void openPointMenu(UIElement root, EditableCurve[] curve, int index,
                                      Runnable onChanged) {
        if (root == null) {
            return;
        }
        UIElement[] overlayRef = new UIElement[1];
        UIElement buttons = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(4));
        buttons.addChild(CreateUi.smallButton(K + "point_delete", 70f, () -> {
            curve[0] = curve[0].withoutPoint(index);
            onChanged.run();
            CreateUi.closeOverlay(root, overlayRef[0]);
        }));
        buttons.addChild(CreateUi.smallButton(K + "reset", 70f, () -> {
            curve[0] = curve[0].reset();
            onChanged.run();
            CreateUi.closeOverlay(root, overlayRef[0]);
        }));
        buttons.addChild(CreateUi.smallButton(K + "cancel", 54f, () -> CreateUi.closeOverlay(root, overlayRef[0])));

        UIElement card = new UIElement().layout(l -> l.width(232).paddingAll(6).gapAll(5)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN))
                .style(s -> s.background(CreateUi.bordered(CreateUi.PANEL_INNER, CreateUi.BRASS_DARK, 2)));
        card.addChild(CreateUi.key(K + "point_menu", CreateUi.BRASS, 10f));
        card.addChild(buttons);

        overlayRef[0] = CreateUi.overlay(root, card);
    }

    /**
     * 弹出子界面（照 LDLib2 生态 NeoECOAEExtension 的做法 —— 该项目<b>全项目不用 `Dialog`</b>）：
     * 在 root 内叠一个<b>绝对定位的全屏遮罩</b>，内部用 flex <b>居中</b>放内容卡片，
     * 关闭时把遮罩`setDisplay(NONE)` 并移除。
     * <p>这样弹窗既不参与主面板的 flex 流（"打开时页面保持在中心位置不要修改页面"），
     * 位置也完全由我们控制（居中），不像 `Dialog.windowMode` 会重排整个 UI。
     * <p>⚠ 遮罩必须<b>最后 addChild</b> 才会盖在最上层（参考 mod 的注记）。
     */

    /** 设备类型的可读名（复用船舵的 lang 键，保持各外设列表写法一致） */
    private static String kindKey(com.hdf.cryptand.gameinput.GameInputDeviceKind kind) {
        return "cryptand.peripheral_helm.kind_" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 当前选中设备的可读描述（查设备池；查不到回显 id） */
    private static String deviceKindText(String id) {
        if (id == null || id.isEmpty()) {
            return "—";
        }
        for (var d : PeripheralHelmInput.devices()) {
            if (d.id().equals(id)) {
                return d.name() + " · " + Component.translatable(kindKey(d.kind())).getString();
            }
        }
        return id;
    }

    /** 轴下标 → 展示名（与船舵/拉杆共用的 {@link CreateUi#AXIS_NAMES}） */
    private static String axisNameOf(int axisIndex) {
        return CreateUi.AXIS_NAMES.get(Math.max(0, Math.min(
                CreateUi.AXIS_NAMES.size() - 1, axisIndex)));
    }

    /** 数值文本：整数不带小数点 */
    private static String numText(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9
                ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static PeripheralTransmissionBinding bindingOf(String deviceId, int axis,
                                                           boolean invert, double min, double max,
                                                           EditableCurve curve, int forceKind) {
        return new PeripheralTransmissionBinding(deviceId, axis, invert, min, max, curve, forceKind);
    }
}
