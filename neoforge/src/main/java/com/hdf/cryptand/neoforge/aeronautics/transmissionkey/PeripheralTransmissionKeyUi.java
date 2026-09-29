/**
 * ===== 外设模拟传动器 · 绑定与曲线界面（2026-09-14）=====
 *
 * 2026-09-16 改版（用户："参考此页面对外设模拟传动器页面进行优化，包括参数显示等"）：
 * 输入轴 / 轴反向 / 强制设备类型 / 输入范围从"设备设置…"子界面搬到主界面（与外设拉杆同一版式），
 * 右列在波形图下方新增【实时】卡片（输出进度条 + 轴值 + 目标点）。
 *
 * <pre>
 *   ┌ 外设模拟传动器（按键）· 映射曲线传动 ─────┐
 *   │ ┌ 外部设备 ──────────┐ ┌ 映射曲线 ───────┐ │
 *   │ │ [设备列表]          │ │ [波形图]         │ │
 *   │ │ [刷新][连接][断开]  │ │ 左键加点/拖动    │ │
 *   │ │ 强制设备类型 [▾]    │ │ [重置曲线]       │ │
 *   │ │ 就绪 · 后端2 · 设备6│ │                  │ │
 *   │ │ 已连接：… [方向盘]  │ │ ┌ 实时 ─────────┐│ │
 *   │ │ ── 输入映射 ──      │ │ │ 输出 ▓▓▓▓ 55% ││ │
 *   │ │ 输入轴 [Axis 0 ▾]   │ │ │ 轴值 X:+0.42  ││ │
 *   │ │ 轴反向 [开关]       │ │ │ 目标 点2·推进中││ │
 *   │ │ 输入范围 [a] [b]    │ │ └───────────────┘│ │
 *   │ └────────────────────┘ └──────────────────┘ │
 *   │               [保存并应用] [取消]             │
 *   └─────────────────────────────────────────────┘
 * </pre>
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

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
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class PeripheralTransmissionKeyUi {

    private static final String K = "cryptand.peripheral_transmission_key.";
    /** 波形图高度（宽度按列宽自适应） */
    private static final int WAVE_H = 96;
    /** 设备列表高度（与外设拉杆/船舵一致） */
    private static final float DEVICE_LIST_H = 60f;

    /** 轴候选（唯一来源 {@link CreateUi#AXIS_NAMES}：与船舵/拉杆同一张表） */
    private static final List<String> AXES = CreateUi.AXIS_NAMES;

    private PeripheralTransmissionKeyUi() {
    }

    public static ModularUI build(BlockUIHolder holder, PeripheralTransmissionKeyBlockEntity be) {
        PeripheralTransmissionKeyBinding initial =
                be != null ? be.getBinding() : PeripheralTransmissionKeyBinding.DEFAULT;
        BlockPos pos = holder.pos;

        final String[] deviceId = { initial.deviceId() };
        final int[] axisIndex = { initial.axisIndex() };
        final boolean[] invertAxis = { initial.invertAxis() };
        final double[] inMin = { initial.inMin() };
        final double[] inMax = { initial.inMax() };
        final int[] forceKind = { PeripheralForceKinds.clamp(initial.forceKind()) };
        final EditableCurve[] curve = { initial.curve() };

        Label status = CreateUi.label(Component.literal("—"), CreateUi.TEXT_DIM, 9f);
        // ⚠ 说明文字必须允许换行：LDLib2 的 TextElement 默认不换行，
        //   长文本会直接把卡片撑宽（用户看到的就是"曲线图超出"——其实是卡片被撑开了）。
        Label hint = CreateUi.label(Component.translatable(K + "curve_hint"), CreateUi.TEXT_DIM, 8f);
        hint.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true)
                .textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP));
        hint.layout(l -> l.widthPercent(100));

        final UIElement[] panelHolder = { null };
        final PeripheralTransmissionKeyCurveCanvas[] canvasHolder = { null };

        // ===== 波形图 =====
        // 面板加宽：左列放设备列表（要能显示设备名），右列放波形（两侧各留 10px 不贴壁）
        // 列宽与其它外设（拉杆/船舵/栏杆）保持一致：176 + 190 —— 用户："设备部分保持和其他的差不多"
        final float leftW = CreateUi.fitWidth(176f);
        final float rightW = CreateUi.fitWidth(190f);
        final int waveW = (int) Math.max(110f, rightW - 24f);
        // 每点按键/时间表：从当前绑定拷一份（可编辑），保存时经 bindingOf 带回服务端
        currentPointKeys = new java.util.ArrayList<>(
                initial.pointKeys() == null ? java.util.List.of() : initial.pointKeys());
        // 正在录制按键的点（-1 = 无）；录制期间由画布每帧钩子轮询设备按键（边沿检测"新按下"）
        final int[] recordingPoint = { -1 };
        final long[] recordLastButtons = { 0L };

        final PeripheralTransmissionKeyCurveCanvas canvas = new PeripheralTransmissionKeyCurveCanvas(
                waveW, WAVE_H, curve,
                () -> {
                    if (be != null) {
                        be.setBinding(bindingOf(deviceId[0], axisIndex[0], invertAxis[0],
                                inMin[0], inMax[0], curve[0], forceKind[0]));
                    }
                },
                index -> openPointMenu(panelHolder[0], curve, index, currentPointKeys,
                        recordingPoint, () -> canvasHolder[0].syncFromModel(),
                        () -> {
                            if (be != null) {
                                be.setBinding(bindingOf(deviceId[0], axisIndex[0], invertAxis[0],
                                        inMin[0], inMax[0], curve[0], forceKind[0]));
                            }
                        }));
        canvasHolder[0] = canvas;
        // 滑块指示：只读进度值（= 方块缓存的输出百分比，用户口径"这个值也是输入输出映射的百分比"）
        canvas.setProgressSource(() -> be != null ? be.getTargetRatio() : 0.0);
        // 每帧钩子：为「按键录制」轮询设备按键 —— newly = now & ~last 即本次新按下的位
        canvas.setTickHook(() -> {
            if (recordingPoint[0] < 0 || be == null) {
                return;
            }
            var sample = PeripheralHelmInput.sample(deviceId[0],
                    PeripheralTransmissionKeyInput.userKey(be.getBlockPos()));
            final long now = sample.connected() ? sample.buttons() : 0L;
            final long newly = now & ~recordLastButtons[0];
            recordLastButtons[0] = now;
            if (newly != 0L) {
                final int idx = recordingPoint[0];
                final int timeMs = idx < currentPointKeys.size()
                        ? currentPointKeys.get(idx).timeMs() : 0;
                while (currentPointKeys.size() <= idx) {
                    currentPointKeys.add(PeripheralTransmissionKeyBinding.PointKey.NONE);
                }
                currentPointKeys.set(idx,
                        new PeripheralTransmissionKeyBinding.PointKey(newly, timeMs));
                recordingPoint[0] = -1;
                if (be != null) {
                    be.setBinding(bindingOf(deviceId[0], axisIndex[0], invertAxis[0],
                            inMin[0], inMax[0], curve[0], forceKind[0]));
                }
                canvas.requestRedraw();
            }
        });

        // ===== 设备列表 =====
        ScrollerView deviceList = new ScrollerView();
        deviceList.layout(l -> l.widthPercent(100).height(DEVICE_LIST_H));
        deviceList.viewPort(v -> v.layout(l -> l.paddingAll(2)));
        deviceList.viewContainer(c -> c.layout(l -> l.gapAll(2)));
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            deviceList.clearAllScrollViewChildren();
            // 首项"（不绑定）"：与拉杆/船舵一致 —— 主动清空选择（断开时用得上）
            Button noneRow = CreateUi.rowButton(deviceId[0].isEmpty(), () -> {
                deviceId[0] = "";
                refresh[0].run();
            });
            Label noneLabel = CreateUi.label(Component.translatable(K + "none"),
                    deviceId[0].isEmpty() ? CreateUi.BRASS : CreateUi.TEXT, 9f);
            noneLabel.layout(l -> l.flexShrink(1).height(12).marginLeft(4));
            noneRow.addChild(noneLabel);
            deviceList.addScrollViewChild(noneRow);
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

        // ===== 输入映射（对齐外设拉杆：参数直接摆在主界面，不再藏进子界面）=====
        // 强制设备类型（"防止识别有问题"：国产方向盘常被认成通用控制器）
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
        forceSelector.layout(l -> l.width(96).height(14));

        Selector<String> axisSelector = new Selector<>();
        axisSelector.setCandidates(AXES);
        axisSelector.setSelected(AXES.get(Math.max(0, Math.min(7, axisIndex[0]))), false);
        axisSelector.setOnValueChanged(v -> axisIndex[0] = Math.max(0, AXES.indexOf(v)));
        axisSelector.layout(l -> l.width(74).height(14));

        Switch invertSwitch = new Switch();
        invertSwitch.setOn(invertAxis[0]);
        invertSwitch.setOnSwitchChanged(v -> invertAxis[0] = v);
        invertSwitch.layout(l -> l.width(16).height(9));

        TextField minField = new TextField();
        minField.setNumbersOnlyDouble(-1.0E6, 1.0E6);
        minField.setText(numText(inMin[0]), false);
        minField.layout(l -> l.width(72).height(14));
        minField.setTextResponder(t -> {
            try {
                inMin[0] = Double.parseDouble(t.trim());
            } catch (Throwable ignored) {
            }
        });
        TextField maxField = new TextField();
        maxField.setNumbersOnlyDouble(-1.0E6, 1.0E6);
        maxField.setText(numText(inMax[0]), false);
        maxField.layout(l -> l.width(72).height(14));
        maxField.setTextResponder(t -> {
            try {
                inMax[0] = Double.parseDouble(t.trim());
            } catch (Throwable ignored) {
            }
        });

        // ===== 连接 / 断开 / 刷新（对齐外设船舵的连接设置）=====
        Label linkLabel = CreateUi.label(Component.translatable(K + "link_idle"),
                CreateUi.TEXT_DIM, 8f);
        linkLabel.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        linkLabel.layout(l -> l.widthPercent(100));

        // 当前界面参数 → 绑定对象（连接 / 断开 / 保存共用）
        final java.util.function.Supplier<PeripheralTransmissionKeyBinding> bindingSupplier =
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
            PeripheralTransmissionKeyBinding off = bindingSupplier.get();
            if (be != null) {
                be.setBinding(off);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionKeyBindPayload.sendToServer(pos, off));

            deviceId[0] = target;
            PeripheralTransmissionKeyBinding on = bindingSupplier.get();
            if (be != null) {
                be.setBinding(on);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionKeyBindPayload.sendToServer(pos, on));
            linkLabel.setText(Component.translatable(K + "link_connecting"));
        });
        // 断开：清空设备选择并解绑（设备池随后关闭该设备）
        Button disconnectBtn = CreateUi.smallButton(K + "disconnect", 52f, () -> {
            deviceId[0] = "";
            PeripheralTransmissionKeyBinding off = bindingSupplier.get();
            if (be != null) {
                be.setBinding(off);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionKeyBindPayload.sendToServer(pos, off));
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

        // ===== 诊断 =====
        // 后端诊断（SDL 是否可用 / 失败原因 / 设备数）——"没有设备"时一眼看到根因，与其他外设一致
        Label diag = CreateUi.label(Component.literal(PeripheralHelmInput.diagnostic()),
                CreateUi.TEXT_DIM, 8f);
        diag.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        diag.layout(l -> l.widthPercent(100));

        // ===== 左列 · 外部设备 + 输入映射（与外设拉杆同构）=====
        UIElement deviceCard = CreateUi.card(K + "section_device",
                deviceList,
                linkRow,
                CreateUi.row(K + "force_kind", forceSelector),
                diag,
                linkLabel,
                CreateUi.key(K + "section_input", CreateUi.BRASS, 9f),
                CreateUi.row(K + "axis", axisSelector),
                CreateUi.row(K + "invert", invertSwitch),
                CreateUi.row(K + "in_min", minField),
                CreateUi.row(K + "in_max", maxField));

        // ===== 右列 · 映射曲线 + 实时 =====
        UIElement curveCard = CreateUi.card(K + "section_curve", canvas, hint, resetBtn);

        UIElement liveFill = new UIElement();
        Label liveOutput = CreateUi.valueLabel(Component.literal("0%"), 46f, CreateUi.BRASS, 10f);
        Label liveAxis = CreateUi.valueLabel(Component.literal("—"), 120f, CreateUi.TEXT, 8f);
        Label liveTarget = CreateUi.valueLabel(Component.literal("—"), 120f, CreateUi.TEXT, 8f);
        UIElement liveCard = CreateUi.card(K + "section_live",
                CreateUi.gaugeLine(K + "live_output", liveFill, liveOutput),
                CreateUi.row(K + "live_axis", liveAxis),
                CreateUi.row(K + "live_target", liveTarget));

        UIElement leftCol = CreateUi.column(leftW, deviceCard);
        UIElement rightCol = CreateUi.column(rightW, curveCard, liveCard);

        // ===== 底部 =====
        Button save = CreateUi.button(K + "save", () -> {
            PeripheralTransmissionKeyBinding binding = bindingOf(deviceId[0], axisIndex[0],
                    invertAxis[0], inMin[0], inMax[0], curve[0], forceKind[0]);
            if (be != null) {
                be.setBinding(binding);
            }
            PeripheralCore.postSend(() ->
                    PeripheralTransmissionKeyBindPayload.sendToServer(pos, binding));
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
                linkLabel.setText(Component.translatable(K + "link_connected").copy()
                        .append(Component.literal(deviceKindText(deviceId[0]))));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_GOOD));
            } else {
                linkLabel.setText(Component.translatable(K + "link_connecting"));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_WARN));
            }
            // ===== 实时（全取真实状态源：方块缓存 + 客户端滑块状态机，不用界面局部变量）=====
            double output = be == null
                    ? 0.0 : Math.max(0.0, Math.min(1.0, be.getTargetRatio()));
            liveFill.layout(l -> l.widthPercent((float) (output * 100.0)));
            liveOutput.setText(Component.literal(String.format(
                    java.util.Locale.ROOT, "%.0f%%", output * 100.0)));
            liveAxis.setText(Component.literal(
                    AXES.get(Math.max(0, Math.min(7, axisIndex[0])))
                            + String.format(java.util.Locale.ROOT, ":%+.2f",
                            be == null ? 0f : be.getClientAxisRaw())
                            + (invertAxis[0] ? "  (inv)" : "")));
            if (PeripheralTransmissionKeyInput.sliderRunning(pos)) {
                liveTarget.setText(Component.translatable(K + "live_running",
                        String.valueOf(PeripheralTransmissionKeyInput.sliderTarget(pos) + 1)));
                liveTarget.textStyle(ts -> ts.textColor(CreateUi.TEXT_GOOD));
            } else {
                liveTarget.setText(Component.translatable(K + "live_target_idle"));
                liveTarget.textStyle(ts -> ts.textColor(CreateUi.TEXT_DIM));
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
     * 右键子页面（用户 2026-09-16："右键打开子页面可以为这个点绑定按键和时间（ms数）"）。
     *
     * <p>布局：标题 → 【录制按键】/【清除按键】+ 当前绑定显示 → 时间（ms）加减按钮 →
     * 删除该点 / 重置曲线 / 关闭。按键录制进入"等待态"后，由画布每帧钩子轮询设备按键，
     * 一旦检测到<b>新按下</b>的位就写入该点（可重复录制 = 多键绑同一点）。</p>
     *
     * @param keys           每点按键/时间表（就地编辑）
     * @param recordingPoint 录制态（[0] = 正在录制的点下标；-1 = 无）
     * @param onCurveChanged 曲线结构变化（删除点 / 重置）后的回调
     * @param onApply        把当前编辑写回方块（保存 binding）
     */
    private static void openPointMenu(UIElement root, EditableCurve[] curve, int index,
                                      java.util.List<PeripheralTransmissionKeyBinding.PointKey> keys,
                                      int[] recordingPoint,
                                      Runnable onCurveChanged, Runnable onApply) {
        if (root == null) {
            return;
        }
        final UIElement[] overlayRef = new UIElement[1];

        // ---------- 按键 ----------
        final Label keyState = CreateUi.label(keyMaskText(recordingPoint[0] == index ? -1L : keyMaskOf(keys, index)),
                CreateUi.TEXT, 8f);
        keyState.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        keyState.layout(l -> l.widthPercent(100));

        final UIElement keyRow = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(4));
        keyRow.addChild(CreateUi.smallButton(K + "point_record_key", 96f, () -> {
            recordingPoint[0] = index;
            keyState.setText(Component.translatable(K + "point_recording"));
        }));
        keyRow.addChild(CreateUi.smallButton(K + "point_clear_key", 70f, () -> {
            setKeyTime(keys, index, 0L, keyTimeMs(keys, index));
            recordingPoint[0] = -1;
            onApply.run();
            keyState.setText(keyMaskText(0L));
        }));

        // ---------- 时间（ms）----------
        final int[] timeMs = { keyTimeMs(keys, index) };
        final Label timeState = CreateUi.label(Component.literal(timeMs[0] + " ms"), CreateUi.BRASS, 9f);
        timeState.layout(l -> l.width(76).height(14));
        final java.util.function.IntConsumer bump = delta -> {
            timeMs[0] = Math.max(0, Math.min(60_000, timeMs[0] + delta));
            setKeyTime(keys, index, keyMaskOf(keys, index), timeMs[0]);
            timeState.setText(Component.literal(timeMs[0] + " ms"));
            onApply.run();      // 立即写回，免得玩家忘了按保存
        };
        final UIElement timeRow = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(3));
        timeRow.addChild(CreateUi.smallButton(K + "time_m500", 38f, () -> bump.accept(-500)));
        timeRow.addChild(CreateUi.smallButton(K + "time_m100", 38f, () -> bump.accept(-100)));
        timeRow.addChild(timeState);
        timeRow.addChild(CreateUi.smallButton(K + "time_p100", 38f, () -> bump.accept(+100)));
        timeRow.addChild(CreateUi.smallButton(K + "time_p500", 38f, () -> bump.accept(+500)));

        // ---------- 底部按钮 ----------
        final UIElement buttons = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(4));
        buttons.addChild(CreateUi.smallButton(K + "point_delete", 70f, () -> {
            recordingPoint[0] = -1;
            curve[0] = curve[0].withoutPoint(index);
            if (index >= 0 && index < keys.size()) {
                keys.remove(index);
            }
            onCurveChanged.run();
            onApply.run();
            CreateUi.closeOverlay(root, overlayRef[0]);
        }));
        buttons.addChild(CreateUi.smallButton(K + "reset", 70f, () -> {
            recordingPoint[0] = -1;
            curve[0] = curve[0].reset();
            keys.clear();
            onCurveChanged.run();
            onApply.run();
            CreateUi.closeOverlay(root, overlayRef[0]);
        }));
        buttons.addChild(CreateUi.smallButton(K + "cancel", 54f, () -> {
            recordingPoint[0] = -1;
            CreateUi.closeOverlay(root, overlayRef[0]);
        }));

        final UIElement card = new UIElement().layout(l -> l.width(252).paddingAll(6).gapAll(5)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN))
                .style(s -> s.background(CreateUi.bordered(CreateUi.PANEL_INNER, CreateUi.BRASS_DARK, 2)));
        card.addChild(CreateUi.key(K + "point_menu", CreateUi.BRASS, 10f));
        card.addChild(CreateUi.key(K + "point_section_key", CreateUi.TEXT_DIM, 8f));
        card.addChild(keyRow);
        card.addChild(keyState);
        card.addChild(CreateUi.key(K + "point_section_time", CreateUi.TEXT_DIM, 8f));
        card.addChild(timeRow);
        card.addChild(CreateUi.hint(K + "point_time_hint"));
        card.addChild(buttons);

        overlayRef[0] = CreateUi.overlay(root, card);
    }

    // ==================== 每点按键/时间表的读写工具 ====================

    /** 按键掩码 → 可读文本（-1 = 正在录制） */
    private static Component keyMaskText(long mask) {
        if (mask < 0L) {
            return Component.translatable(K + "point_recording");
        }
        if (mask == 0L) {
            return Component.translatable(K + "point_no_key");
        }
        return Component.translatable(K + "point_key_set",
                Long.toHexString(mask).toUpperCase(java.util.Locale.ROOT));
    }

    /** 第 index 点的按键掩码（越界 ⇒ 0） */
    private static long keyMaskOf(java.util.List<PeripheralTransmissionKeyBinding.PointKey> keys, int index) {
        return index >= 0 && index < keys.size()
                ? keys.get(index).keyMask() : 0L;
    }

    /** 第 index 点的时间（ms；越界 ⇒ 0） */
    private static int keyTimeMs(java.util.List<PeripheralTransmissionKeyBinding.PointKey> keys, int index) {
        return index >= 0 && index < keys.size()
                ? keys.get(index).timeMs() : 0;
    }

    /** 写第 index 点的按键/时间（索引超出则补齐中间项） */
    private static void setKeyTime(java.util.List<PeripheralTransmissionKeyBinding.PointKey> keys,
                                   int index, long mask, int timeMs) {
        if (index < 0) {
            return;
        }
        while (keys.size() <= index) {
            keys.add(PeripheralTransmissionKeyBinding.PointKey.NONE);
        }
        keys.set(index, new PeripheralTransmissionKeyBinding.PointKey(mask, timeMs));
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

    /** 数值文本：整数不带小数点 */
    private static String numText(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9
                ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * 当前界面的「每点按键/时间」表（{@code build} 时用 {@code initial.pointKeys()} 初始化，
     * 由「右键点 → 子页面」编辑）。
     *
     * <p>⚠ 放在静态字段是为了让 {@link #bindingOf} 不必在 3 个调用点都多传一个参数；
     * 界面构建与交互都在客户端主线程，同一时刻只会有一个该界面实例，故安全。</p>
     */
    private static java.util.List<PeripheralTransmissionKeyBinding.PointKey> currentPointKeys =
            java.util.List.of();

    private static PeripheralTransmissionKeyBinding bindingOf(String deviceId, int axis,
                                                           boolean invert, double min, double max,
                                                           EditableCurve curve, int forceKind) {
        // 带上已录好的每点按键/时间，避免【保存并应用】把用户录的按键清空
        return new PeripheralTransmissionKeyBinding(deviceId, axis, invert, min, max, curve, forceKind,
                java.util.List.copyOf(currentPointKeys));
    }
}
