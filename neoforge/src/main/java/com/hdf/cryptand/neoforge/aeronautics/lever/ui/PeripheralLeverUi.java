/**
 * ===== 外设拉杆 · 绑定界面（LDLib2，Create 风格平铺，2026-09-13） =====
 *
 * 与船舵同一套视觉语言（{@link com.hdf.cryptand.neoforge.core.ui.CreateUi}）与同一个客户端设备池
 * （设备共享 + 引用计数）。可绑定：
 * <ul>
 *   <li><b>踏板轴</b>（离合 / 油门 / 刹车 / 任意 8 轴之一）+ 反向 + 死区 + 行程上下限；</li>
 *   <li><b>加档 / 减档按钮</b>（点动式；自动模式下可在轴档位之上加减）；</li>
 *   <li>输入模式：自动（轴+按键）/ 仅轴 / 仅按键。</li>
 * </ul>
 * 轴 → 档位是线性映射（0..15），最终走官方 {@code setSignal} 输出红石信号强度。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever.ui;

import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds;

import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBindPayload;
import com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBinding;
import com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBlockEntity;
import com.hdf.cryptand.neoforge.core.ui.CreateUi;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Slider;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public final class PeripheralLeverUi {

    private static final String K = "cryptand.peripheral_lever.";

    private static final float LEFT_W = 176f;
    private static final float RIGHT_W = 190f;
    private static final float DEVICE_LIST_H = 60f;

    /** 轴候选（唯一来源 {@link CreateUi#AXIS_NAMES}：与船舵/传动器同一张表，别再各写一份） */
    private static final List<String> AXES = CreateUi.AXIS_NAMES;

    /** 连接状态机（与外设船舵同一套语义：选中设备 → 点【连接】→ 提示框显示结果） */
    private static final int LINK_IDLE = 0;
    private static final int LINK_CONNECTING = 1;
    private static final int LINK_CONNECTED = 2;
    private static final int LINK_FAILED = 3;
    /** 连接超时（ms）：超过仍未拿到 connected 采样 → 判定失败 */
    private static final long LINK_TIMEOUT_MS = 5000L;

    private PeripheralLeverUi() {
    }

    public static ModularUI build(BlockUIHolder holder, PeripheralLeverBlockEntity be) {
        PeripheralLeverBinding initial =
                be != null ? be.getBinding() : PeripheralLeverBinding.DEFAULT;
        BlockPos pos = holder.pos;

        final String[] deviceId = { initial.deviceId() };
        final int[] mode = { Mth.clamp(initial.inputMode(), 0, 2) };
        final int[] axisIndex = { Mth.clamp(initial.axisIndex(), 0, 7) };
        final boolean[] invertAxis = { initial.invertAxis() };
        final float[] deadzone = { initial.deadzone() };
        final float[] travelMin = { initial.travelMin() };
        final float[] travelMax = { initial.travelMax() };
        final int[] buttonUp = { initial.buttonUp() };
        final int[] buttonDown = { initial.buttonDown() };
        // 强制设备类型（与船舵同一张表；自动 = 按后端检测）
        final int[] forceKind = { PeripheralForceKinds.clamp(initial.forceKind()) };

        final java.util.function.Supplier<PeripheralLeverBinding> bindingSupplier = () ->
                new PeripheralLeverBinding(deviceId[0], mode[0], axisIndex[0], invertAxis[0],
                        deadzone[0], travelMin[0], travelMax[0], buttonUp[0], buttonDown[0],
                        forceKind[0]);

        // ===== 标题栏 =====
        Label status = CreateUi.label(Component.literal("—"), CreateUi.TEXT_DIM, 9f);

        // ===== 左列 · 外部设备 =====
        ScrollerView deviceList = new ScrollerView();
        deviceList.layout(l -> l.widthPercent(100).height(DEVICE_LIST_H));
        deviceList.viewPort(v -> v.layout(l -> l.paddingAll(2)));
        deviceList.viewContainer(c -> c.layout(l -> l.gapAll(2)));

        Label diag = CreateUi.label(Component.literal(PeripheralHelmInput.diagnostic()),
                CreateUi.TEXT_DIM, 8f);
        diag.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        diag.layout(l -> l.widthPercent(100));

        Label linkLabel = CreateUi.label(Component.translatable(K + "link_idle"),
                CreateUi.TEXT_DIM, 8f);
        linkLabel.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        linkLabel.layout(l -> l.widthPercent(100));
        // 连接状态来自【真实状态源】（BE 的绑定 + 客户端设备池），不是界面局部变量 ——
        // 否则关掉界面再打开会退回"需要连接"（用户实测反馈）。
        final int[] linkState = { resolveLinkState(be, deviceId[0], pos) };
        final String[] linkDetail = { resolveLinkDetail(deviceId[0], linkState[0]) };
        final long[] linkStartedAt = { System.currentTimeMillis() };

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            deviceList.clearAllScrollViewChildren();
            List<GameInputDeviceInfo> devices = PeripheralHelmInput.devices();
            deviceList.addScrollViewChild(deviceRow(
                    Component.translatable(K + "none").getString(), null,
                    deviceId[0].isEmpty(),
                    () -> {
                        deviceId[0] = "";
                        refresh[0].run();
                    }));
            for (GameInputDeviceInfo d : devices) {
                int users = PeripheralHelmInput.userCount(d.id());
                com.hdf.cryptand.gameinput.GameInputDeviceKind shownKind =
                    PeripheralHelmInput.kindOf(d.id());
            String kind = Component.translatable(kindKey(shownKind == null ? d.kind() : shownKind)).getString()
                        + (users > 0
                        ? " · " + Component.translatable("cryptand.peripheral_helm.shared_mark", users)
                        .getString()
                        : "");
                deviceList.addScrollViewChild(deviceRow(d.name(), kind, d.id().equals(deviceId[0]), () -> {
                    deviceId[0] = d.id();
                    refresh[0].run();
                }));
            }
            if (devices.isEmpty()) {
                deviceList.addScrollViewChild(CreateUi.dim(K + "no_device"));
            }
            diag.setText(Component.literal(PeripheralHelmInput.diagnostic()));
        };
        refresh[0].run();

        Button refreshBtn = CreateUi.smallButton(K + "refresh", 48f, () -> {
            PeripheralHelmInput.requestScan();
            refresh[0].run();
        });
        Button connectBtn = CreateUi.smallButton(K + "connect", 52f, () -> {
            if (deviceId[0].isEmpty()) {
                linkLabel.setText(Component.translatable(K + "link_pick_first"));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_BAD));
                return;
            }
            // ★ 连接流程（用户定稿 2026-09-13）：先【断开当前连接】，再【连接新设备】。
            //   两段各走一次完整的本地 + 服务端同步：断开 → 设备池释放旧引用
            //   （引用计数归零 ⇒ 采样线程关闭旧句柄、停掉全部力反馈），随后再绑定新设备。
            //   这样绝不会残留"旧设备的力还挂在方向盘上"（就是回正后停在 ±180° 的那类现象）。
            String target = deviceId[0];

            deviceId[0] = "";
            PeripheralLeverBinding disconnected = bindingSupplier.get();
            pushToClient(pos, disconnected);
            PeripheralCore.postSend(() -> PeripheralLeverBindPayload.sendToServer(pos, disconnected));

            deviceId[0] = target;
            PeripheralLeverBinding connected = bindingSupplier.get();
            pushToClient(pos, connected);
            PeripheralCore.postSend(() -> PeripheralLeverBindPayload.sendToServer(pos, connected));

            // 进入"连接中"，由每 tick 的轮询给出最终结果（成功 / 超时失败）
            linkState[0] = LINK_CONNECTING;
            linkStartedAt[0] = System.currentTimeMillis();
            linkDetail[0] = "";
        });
        Button disconnectBtn = CreateUi.smallButton(K + "disconnect", 52f, () -> {
            deviceId[0] = "";
            PeripheralLeverBinding binding = bindingSupplier.get();
            pushToClient(pos, binding);
            PeripheralCore.postSend(() -> PeripheralLeverBindPayload.sendToServer(pos, binding));
            linkState[0] = LINK_IDLE;
            linkDetail[0] = "";
            refresh[0].run();
        });
        UIElement deviceButtons = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
        deviceButtons.addChildren(refreshBtn, connectBtn, disconnectBtn);

        // 强制设备类型下拉框（"防止识别有问题"：国产方向盘常被认成通用控制器）
        java.util.List<String> forceCandidates = new java.util.ArrayList<>();
        for (String key : PeripheralForceKinds.KEYS) {
            forceCandidates.add(Component.translatable(
                    PeripheralForceKinds.LANG_PREFIX + key).getString());
        }
        Selector<String> forceKindSelector = new Selector<>();
        forceKindSelector.setCandidates(forceCandidates);
        forceKindSelector.setSelected(forceCandidates.get(forceKind[0]), false);
        forceKindSelector.setOnValueChanged(v ->
                forceKind[0] = Math.max(0, forceCandidates.indexOf(v)));
        forceKindSelector.layout(l -> l.width(96).height(14));

        UIElement deviceCard = CreateUi.card(K + "section_device",
                deviceList,
                deviceButtons,
                CreateUi.row(K + "force_kind", forceKindSelector),
                diag,
                linkLabel);

        // ===== 右列 · 输入映射 =====
        List<String> modes = new ArrayList<>();
        for (String m : List.of("auto", "axis", "button")) {
            modes.add(Component.translatable(K + "mode_" + m).getString());
        }
        Selector<String> modeSelector = new Selector<>();
        modeSelector.setCandidates(modes);
        modeSelector.setSelected(modes.get(mode[0]), false);
        modeSelector.setOnValueChanged(v -> mode[0] = Math.max(0, modes.indexOf(v)));
        modeSelector.layout(l -> l.width(104).height(14));

        Selector<String> axisSelector = new Selector<>();
        axisSelector.setCandidates(AXES);
        axisSelector.setSelected(AXES.get(axisIndex[0]), false);
        axisSelector.setOnValueChanged(v -> axisIndex[0] = Math.max(0, AXES.indexOf(v)));
        axisSelector.layout(l -> l.width(74).height(14));

        Switch invertSwitch = new Switch();
        invertSwitch.setOn(invertAxis[0]);
        invertSwitch.setOnSwitchChanged(v -> invertAxis[0] = v);
        invertSwitch.layout(l -> l.width(16).height(9));

        Slider.Horizontal deadzoneSlider = slider(0f, 50f, deadzone[0] * 100f);
        Label deadzoneValue = CreateUi.valueLabel(Component.literal(percentText(deadzone[0])), 46f, CreateUi.BRASS, 10f);
        deadzoneSlider.setOnValueChanged(v -> {
            deadzone[0] = v / 100f;
            deadzoneValue.setText(percentText(deadzone[0]));
        });

        // ===== 轴值范围预设（用户："我的这个是 -1 到 1……" + "需要支持从 -1 到 1 以及从 1 到 -1"）=====
        // 映射本身一直支持任意区间：stateOfAxis 用【带符号】的 span = max - min，
        // 所以把下限设得比上限大（1 → -1）就是反序映射，无需额外开关。
        // 这里只是把常见区间做成直白的预设，省得去拖两个滑块。
        final Slider.Horizontal[] minHolder = new Slider.Horizontal[1];
        final Slider.Horizontal[] maxHolder = new Slider.Horizontal[1];
        final Label[] minLabelHolder = new Label[1];
        final Label[] maxLabelHolder = new Label[1];
        final boolean[] syncing = { false };

        List<String> ranges = List.of(
                Component.translatable(K + "range_0_1").getString(),
                Component.translatable(K + "range_-1_1").getString(),
                Component.translatable(K + "range_1_-1").getString(),
                Component.translatable(K + "range_custom").getString());
        Selector<String> rangeSelector = new Selector<>();
        rangeSelector.setCandidates(ranges);
        rangeSelector.setSelected(ranges.get(rangePresetOf(travelMin[0], travelMax[0])), false);
        rangeSelector.setOnValueChanged(v -> {
            int idx = Math.max(0, ranges.indexOf(v));
            if (idx == 0) {
                travelMin[0] = 0f;
                travelMax[0] = 1f;
            } else if (idx == 1) {
                travelMin[0] = -1f;
                travelMax[0] = 1f;
            } else if (idx == 2) {
                travelMin[0] = 1f;      // 反序：+1 ⇒ 0 档，-1 ⇒ 15 档
                travelMax[0] = -1f;
            }
            if (idx != 3) {
                syncing[0] = true;
                if (minHolder[0] != null) {
                    minHolder[0].setValue(travelMin[0] * 100f, false);
                    minLabelHolder[0].setText(percentText(travelMin[0]));
                }
                if (maxHolder[0] != null) {
                    maxHolder[0].setValue(travelMax[0] * 100f, false);
                    maxLabelHolder[0].setText(percentText(travelMax[0]));
                }
                syncing[0] = false;
            }
        });
        rangeSelector.layout(l -> l.width(126).height(14));

        Slider.Horizontal minSlider = slider(-100f, 100f, travelMin[0] * 100f);
        Label minValue = CreateUi.valueLabel(Component.literal(percentText(travelMin[0])), 46f, CreateUi.BRASS, 10f);
        minHolder[0] = minSlider;
        minLabelHolder[0] = minValue;
        minSlider.setOnValueChanged(v -> {
            travelMin[0] = v / 100f;
            minValue.setText(percentText(travelMin[0]));
            if (!syncing[0]) {
                rangeSelector.setSelected(ranges.get(3), false);   // 手改即"自定义"
            }
        });

        Slider.Horizontal maxSlider = slider(-100f, 200f, travelMax[0] * 100f);
        Label maxValue = CreateUi.valueLabel(Component.literal(percentText(travelMax[0])), 46f, CreateUi.BRASS, 10f);
        maxHolder[0] = maxSlider;
        maxLabelHolder[0] = maxValue;
        maxSlider.setOnValueChanged(v -> {
            travelMax[0] = v / 100f;
            maxValue.setText(percentText(travelMax[0]));
            if (!syncing[0]) {
                rangeSelector.setSelected(ranges.get(3), false);
            }
        });

        UIElement inputCard = CreateUi.card(K + "section_input",
                CreateUi.row(K + "mode", modeSelector),
                CreateUi.row(K + "axis", axisSelector),
                CreateUi.row(K + "invert", invertSwitch),
                CreateUi.row(K + "axis_range", rangeSelector),
                CreateUi.sliderRow(K + "deadzone", deadzoneSlider, deadzoneValue),
                CreateUi.sliderRow(K + "travel_min", minSlider, minValue),
                CreateUi.sliderRow(K + "travel_max", maxSlider, maxValue));

        // ===== 左列（下）· 按键映射 =====
        List<String> buttonCandidates = new ArrayList<>();
        buttonCandidates.add(Component.translatable(K + "btn_none").getString());
        for (int i = 0; i < 16; i++) {
            buttonCandidates.add(Component.translatable(K + "btn_index", i).getString());
        }
        Selector<String> upSelector = new Selector<>();
        upSelector.setCandidates(buttonCandidates);
        upSelector.setSelected(buttonCandidates.get(Mth.clamp(buttonUp[0] + 1, 0, 16)), false);
        upSelector.setOnValueChanged(v ->
                buttonUp[0] = Math.max(0, buttonCandidates.indexOf(v)) - 1);
        upSelector.layout(l -> l.width(96).height(14));

        Selector<String> downSelector = new Selector<>();
        downSelector.setCandidates(buttonCandidates);
        downSelector.setSelected(buttonCandidates.get(Mth.clamp(buttonDown[0] + 1, 0, 16)), false);
        downSelector.setOnValueChanged(v ->
                buttonDown[0] = Math.max(0, buttonCandidates.indexOf(v)) - 1);
        downSelector.layout(l -> l.width(96).height(14));

        UIElement buttonCard = CreateUi.card(K + "section_buttons",
                CreateUi.row(K + "btn_up", upSelector),
                CreateUi.row(K + "btn_down", downSelector));

        // ===== 右列（下）· 实时 =====
        UIElement stateFill = new UIElement();
        Label stateValue = CreateUi.valueLabel(Component.literal("0 / 15"), 86f, CreateUi.TEXT, 9f);
        Label axisValue = CreateUi.valueLabel(Component.literal("X:+0.00"), 128f, CreateUi.TEXT, 8f);

        UIElement liveCard = CreateUi.card(K + "section_live",
                CreateUi.gaugeLine(K + "live_state", stateFill, stateValue),
                CreateUi.row(K + "live_axis", axisValue));

        // ===== 底部按钮 =====
        Button save = CreateUi.button(K + "save", () -> {
            PeripheralLeverBinding binding = bindingSupplier.get();
            pushToClient(pos, binding);
            PeripheralCore.postSend(() -> PeripheralLeverBindPayload.sendToServer(pos, binding));
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        });
        save.layout(l -> l.flex(1).height(17));

        Button cancel = CreateUi.button(K + "cancel", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        });
        cancel.layout(l -> l.flex(1).height(17));

        // ===== 组装（横向平铺） =====
        UIElement panel = CreateUi.panel(CreateUi.panelWidthFor(LEFT_W, RIGHT_W));
        UIElement content = CreateUi.content(panel);
        content.addChildren(
                CreateUi.titleBar(Component.translatable(K + "title"), status),
                CreateUi.body(
                        CreateUi.column(LEFT_W, deviceCard, buttonCard),
                        CreateUi.column(RIGHT_W, inputCard, liveCard)),
                CreateUi.buttonRow(save, cancel));

        // ===== 每 tick 刷新实时数据 =====
        final String[] shownSignature = { "" };
        // 双保险：即使列表指纹发生抖动，也限制"重建整个列表"的最小间隔 ——
        // 重建会创建大量 LDLib2 元素并触发整屏重绘，绝不能每 tick 做（那会把渲染线程 CPU 打满）。
        final long[] lastRebuildAt = { 0L };
        panel.addEventListener(UIEvents.TICK, ev -> {
            String signature = deviceSignature(PeripheralHelmInput.devices());
            long nowMs = System.currentTimeMillis();
            if (!signature.equals(shownSignature[0]) && nowMs - lastRebuildAt[0] >= 250L) {
                shownSignature[0] = signature;
                lastRebuildAt[0] = nowMs;
                refresh[0].run();
            }
            if (be == null) {
                status.setText(Component.literal("—"));
                return;
            }
            int state = be.getState();
            stateFill.layout(l -> l.widthPercent(Math.min(100f, state / 15f * 100f)));
            stateValue.setText(Component.literal(state + " / 15"));

            String key = com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverInput
                    .userKey(pos);
            float raw = PeripheralHelmInput.sample(deviceId[0], key).axis(axisIndex[0]);
            axisValue.setText(Component.literal(AXES.get(axisIndex[0])
                    + String.format(":%+.2f", raw)));

            // ===== 连接状态轮询（点【连接】后）：连接中 → 已连接 / 超时失败 =====
            // ⚠ 之前这里只有"把文字设成连接中"，没有任何后续更新 ⇒ 界面永远停在"连接中"（实测）。
            if (linkState[0] == LINK_CONNECTING) {
                boolean held = PeripheralHelmInput.isBound(deviceId[0], key);
                boolean live = PeripheralHelmInput.sample(deviceId[0], key).connected();
                if (held && live) {
                    linkState[0] = LINK_CONNECTED;
                    linkDetail[0] = "";
                } else if (System.currentTimeMillis() - linkStartedAt[0] > LINK_TIMEOUT_MS) {
                    linkState[0] = LINK_FAILED;
                    String err = PeripheralHelmInput.deviceError(deviceId[0]);
                    linkDetail[0] = err != null ? err
                            : Component.translatable(K + "link_timeout").getString();
                }
            }
            int linkColor;
            Component linkText;
            switch (linkState[0]) {
                case LINK_CONNECTING -> {
                    linkColor = CreateUi.TEXT_WARN;
                    linkText = Component.translatable(K + "link_connecting");
                }
                case LINK_CONNECTED -> {
                    linkColor = CreateUi.TEXT_GOOD;
                    linkText = Component.translatable(K + "link_connected").copy()
                            .append(Component.literal(deviceLabel(deviceId[0])));
                }
                case LINK_FAILED -> {
                    linkColor = CreateUi.TEXT_BAD;
                    linkText = Component.translatable(K + "link_failed").copy()
                            .append(Component.literal(linkDetail[0]));
                }
                default -> {
                    linkColor = CreateUi.TEXT_DIM;
                    linkText = Component.translatable(K + "link_idle");
                }
            }
            linkLabel.setText(linkText);
            linkLabel.textStyle(ts -> ts.textColor(linkColor));

            if (deviceId[0].isEmpty()) {
                status.setText(CreateUi.key(K + "status_unbound", CreateUi.TEXT_DIM, 9f).getText());
            } else if (linkState[0] == LINK_CONNECTED) {
                status.setText(CreateUi.key(K + "status_connected", CreateUi.TEXT_GOOD, 9f).getText());
            } else if (linkState[0] == LINK_FAILED) {
                status.setText(CreateUi.key(K + "status_disconnected", CreateUi.TEXT_BAD, 9f).getText());
            } else {
                status.setText(CreateUi.key(K + "status_disconnected", CreateUi.TEXT_DIM, 9f).getText());
            }
        });

        return new ModularUI(UI.of(panel), holder.player);
    }

    // ==================== 连接状态（取自真实状态源） ====================

    /** 界面打开时的真实连接状态：未绑定 → 空闲；池里已登记且采样连通 → 已连接；有错误 → 失败；否则连接中 */
    private static int resolveLinkState(PeripheralLeverBlockEntity be, String deviceId, BlockPos pos) {
        if (be == null || deviceId == null || deviceId.isEmpty()) {
            return LINK_IDLE;
        }
        String key = com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverInput.userKey(pos);
        if (PeripheralHelmInput.isBound(deviceId, key)
                && PeripheralHelmInput.sample(deviceId, key).connected()) {
            return LINK_CONNECTED;
        }
        return PeripheralHelmInput.deviceError(deviceId) != null ? LINK_FAILED : LINK_CONNECTING;
    }

    private static String resolveLinkDetail(String deviceId, int state) {
        if (state != LINK_FAILED || deviceId == null || deviceId.isEmpty()) {
            return "";
        }
        String error = PeripheralHelmInput.deviceError(deviceId);
        return error == null ? "" : error;
    }

    // ==================== 小工具 ====================

    /** 本地立刻生效（不等服务端回包），驱动者本人无延迟 */
    private static void pushToClient(BlockPos pos, PeripheralLeverBinding binding) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos)
                instanceof PeripheralLeverBlockEntity lever) {
            lever.setBinding(binding);
        }
    }

    private static Slider.Horizontal slider(float min, float max, float value) {
        Slider.Horizontal s = new Slider.Horizontal();
        s.setRange(min, max);
        s.setValue(Mth.clamp(value, min, max), false);
        s.sliderStyle(st -> st.trackSize(4).handleSize(8));
        s.layout(l -> l.flex(1).height(9));
        return s;
    }

    /** 由当前 min/max 反推范围预设：0 = 0~1 / 1 = -1~1 / 2 = 1~-1（反序）/ 3 = 自定义 */
    private static int rangePresetOf(float min, float max) {
        if (Math.abs(min) < 1.0E-3f && Math.abs(max - 1f) < 1.0E-3f) {
            return 0;
        }
        if (Math.abs(min + 1f) < 1.0E-3f && Math.abs(max - 1f) < 1.0E-3f) {
            return 1;
        }
        if (Math.abs(min - 1f) < 1.0E-3f && Math.abs(max + 1f) < 1.0E-3f) {
            return 2;
        }
        return 3;
    }

    private static String percentText(float v) {
        return String.format("%.0f%%", v * 100f);
    }


    /**
     * 已连接设备的标签（" · Logitech G29… [方向盘]"）—— 用户要求："连接设备后显示当前设备
     * 作为什么设备连接的"。类型取 {@code kindOf}：强制类型优先，未强制（自动）则用后端判定，
     * 与设备池实际生效的完全一致。
     * <p>池里找不到时回退显示绑定里保存的那个标识，而不是留空（否则界面看起来像连上了、
     * 却不知道连的是什么）。
     */
    private static String deviceLabel(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return "";
        }
        String name = PeripheralHelmInput.deviceName(deviceId);
        com.hdf.cryptand.gameinput.GameInputDeviceKind kind = PeripheralHelmInput.kindOf(deviceId);
        StringBuilder sb = new StringBuilder(" · ");
        sb.append(name == null || name.isEmpty() ? deviceId : name);
        if (kind != null) {
            sb.append(" [").append(Component.translatable(kindKey(kind)).getString()).append(']');
        }
        return sb.toString();
    }

    private static String kindKey(com.hdf.cryptand.gameinput.GameInputDeviceKind kind) {
        return "cryptand.peripheral_helm.kind_" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String deviceSignature(List<GameInputDeviceInfo> devices) {
        StringBuilder sb = new StringBuilder();
        for (GameInputDeviceInfo d : devices) {
            sb.append(d.id()).append('|').append(d.name()).append(';');
        }
        return sb.toString();
    }

    /** 设备行（与船舵界面同款：整行可点、选中高亮） */
    private static Button deviceRow(String name, String kindText, boolean selected, Runnable onClick) {
        Button row = CreateUi.rowButton(selected, onClick);
        Label nameLabel = CreateUi.label(Component.literal(name),
                selected ? CreateUi.BRASS : CreateUi.TEXT, 9f);
        nameLabel.layout(l -> l.flexShrink(1));
        Label kindLabel = CreateUi.label(
                kindText == null ? Component.empty() : Component.literal(kindText),
                CreateUi.TEXT_DIM, 9f);
        kindLabel.layout(l -> l.marginLeftAuto());
        row.addChildren(nameLabel, kindLabel);
        return row;
    }
}