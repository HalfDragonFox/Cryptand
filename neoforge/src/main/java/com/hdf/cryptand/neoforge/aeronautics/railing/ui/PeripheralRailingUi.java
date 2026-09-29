/**
 * ===== 外设栏杆（按钮）绑定界面（LDLib2 · Create 风格，2026-09-13） =====
 *
 * 用户需求："绑定单个按键或多个按键输出不同信号…用于换挡器，可以自定义按键数量，
 * 可以支持多个按键同时绑定一个信号输出，每次需要添加按键在列表中最下面有个+按键用于添加，
 * 然后选中对应条目点击删除即可删除"
 * 列表格式（用户指定）：
 * <pre>
 *   1.0,2按键->5信号
 *   2.1按键->9信号
 * </pre>
 * 即 {@code 序号.按键列表按键->信号值信号}（lang 模板 `entry_format`）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing.ui;

import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBindPayload;
import com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBinding;
import com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBlockEntity;
import com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingInput;
import com.hdf.cryptand.neoforge.core.ui.CreateUi;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Slider;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public final class PeripheralRailingUi {

    private static final String K = "cryptand.peripheral_railing.";

    private static final float LEFT_W = 176f;
    private static final float RIGHT_W = 190f;
    private static final float DEVICE_LIST_H = 60f;
    private static final float ENTRY_LIST_H = 96f;

    private static final int LINK_IDLE = 0;
    private static final int LINK_CONNECTING = 1;
    private static final int LINK_CONNECTED = 2;
    private static final int LINK_FAILED = 3;
    private static final long LINK_TIMEOUT_MS = 5000L;

    private PeripheralRailingUi() {
    }

    public static ModularUI build(BlockUIHolder holder, PeripheralRailingBlockEntity be) {
        PeripheralRailingBinding initial =
                be != null ? be.getBinding() : PeripheralRailingBinding.DEFAULT;
        BlockPos pos = holder.pos;

        final String[] deviceId = { initial.deviceId() };
        // 强制设备类型（用户："按钮与非按钮版本都可以和船舵一样设置设备强制，防止识别有问题"）
        final int[] forceKind = { PeripheralForceKinds.clamp(initial.forceKind()) };
        final List<PeripheralRailingBinding.Entry> entries = new ArrayList<>(initial.entries());
        final int[] selected = { entries.isEmpty() ? -1 : 0 };

        final java.util.function.Supplier<PeripheralRailingBinding> bindingSupplier = () ->
                new PeripheralRailingBinding(deviceId[0], List.copyOf(entries), forceKind[0]);

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

        final int[] linkState = { resolveLinkState(be, deviceId[0], pos) };
        final String[] linkDetail = { resolveLinkDetail(deviceId[0], linkState[0]) };
        final long[] linkStartedAt = { System.currentTimeMillis() };

        final Runnable[] refreshDevices = new Runnable[1];
        refreshDevices[0] = () -> {
            deviceList.clearAllScrollViewChildren();
            List<GameInputDeviceInfo> devices = PeripheralHelmInput.devices();
            deviceList.addScrollViewChild(deviceRow(
                    Component.translatable(K + "none").getString(), null,
                    deviceId[0].isEmpty(),
                    () -> {
                        deviceId[0] = "";
                        refreshDevices[0].run();
                    }));
            for (GameInputDeviceInfo d : devices) {
                int users = PeripheralHelmInput.userCount(d.id());
                // ⚠ 右侧只放【设备类型 + 使用者数】：名字已经由左侧 Label 显示，
                //   这里再拼一次设备名会让两段文字在同一行重叠（实测截图）。
                com.hdf.cryptand.gameinput.GameInputDeviceKind shownKind =
                        PeripheralHelmInput.kindOf(d.id());
                String kind = Component.translatable(kindKey(shownKind == null ? d.kind() : shownKind)).getString()
                        + (users > 0
                        ? " · " + Component.translatable("cryptand.peripheral_helm.shared_mark", users)
                        .getString()
                        : "");
                deviceList.addScrollViewChild(deviceRow(d.name(), kind, d.id().equals(deviceId[0]), () -> {
                    deviceId[0] = d.id();
                    refreshDevices[0].run();
                }));
            }
            if (devices.isEmpty()) {
                deviceList.addScrollViewChild(CreateUi.dim(K + "no_device"));
            }
            diag.setText(Component.literal(PeripheralHelmInput.diagnostic()));
        };
        refreshDevices[0].run();

        Button refreshBtn = CreateUi.smallButton(K + "refresh", 48f, () -> {
            PeripheralHelmInput.requestScan();
            refreshDevices[0].run();
        });
        Button connectBtn = CreateUi.smallButton(K + "connect", 52f, () -> {
            if (deviceId[0].isEmpty()) {
                linkLabel.setText(Component.translatable(K + "link_pick_first"));
                linkLabel.textStyle(ts -> ts.textColor(CreateUi.TEXT_BAD));
                return;
            }
            // 固定流程：先断开当前连接，再连接新设备（与外设船舵/拉杆一致）
            String target = deviceId[0];
            deviceId[0] = "";
            PeripheralRailingBinding disconnected = bindingSupplier.get();
            pushToClient(pos, disconnected);
            PeripheralCore.postSend(() -> PeripheralRailingBindPayload.sendToServer(pos, disconnected));
            deviceId[0] = target;
            PeripheralRailingBinding connected = bindingSupplier.get();
            pushToClient(pos, connected);
            PeripheralCore.postSend(() -> PeripheralRailingBindPayload.sendToServer(pos, connected));
            linkState[0] = LINK_CONNECTING;
            linkStartedAt[0] = System.currentTimeMillis();
            linkDetail[0] = "";
        });
        Button disconnectBtn = CreateUi.smallButton(K + "disconnect", 52f, () -> {
            deviceId[0] = "";
            PeripheralRailingBinding binding = bindingSupplier.get();
            pushToClient(pos, binding);
            PeripheralCore.postSend(() -> PeripheralRailingBindPayload.sendToServer(pos, binding));
            linkState[0] = LINK_IDLE;
            linkDetail[0] = "";
            refreshDevices[0].run();
        });
        UIElement deviceButtons = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
        deviceButtons.addChildren(refreshBtn, connectBtn, disconnectBtn);

        // 实时输入监视：用户看不到"设备到底有没有在报数据"，就无从判断为什么绑不上键。
        // 这里把当前按下的键（含方向帽）实时打出来 —— 有没有数据一眼可见。
        Label monitor = CreateUi.label(Component.empty(), CreateUi.TEXT_DIM, 8f);
        monitor.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        monitor.layout(l -> l.widthPercent(100));

        // 强制设备类型：与船舵同一张候选表（自动 = 按后端检测；键盘分 SDL / GLFW 两种读取方式）
        List<String> forceCandidates = new ArrayList<>();
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
                deviceList, deviceButtons,
                CreateUi.row(K + "force_kind", forceKindSelector),
                diag, linkLabel, monitor);

        // ===== 右列 · 按键映射 =====
        // 设计（用户定稿 2026-09-13）：
        //   条目只有【增加/删除】；条目行左边 = MC 风格的按键绑定按钮（点一下进入"按下一个键"，
        //   按下设备按键即绑定并显示）；右边 = 一个按钮，点击弹出【滑块子页面】（确定/取消）。
        ScrollerView entryList = new ScrollerView();
        entryList.layout(l -> l.widthPercent(100).height(ENTRY_LIST_H));
        entryList.viewPort(v -> v.layout(l -> l.paddingAll(2)));
        entryList.viewContainer(c -> c.layout(l -> l.gapAll(2)));

        Label hint = CreateUi.label(Component.empty(), CreateUi.TEXT_DIM, 8f);
        hint.layout(l -> l.widthPercent(100));

        // 录制状态：正在等待"按下的那个键"（-1 = 未录制）
        final int[] recordingIndex = { -1 };
        // 上一次采样时按下的键清单（统一索引空间）：本次的"新按下"= 差集
        final int[][] lastPressed = { new int[0] };

        // ★ 界面自己的临时设备引用（独立 userKey）：让"选设备 → 直接绑键"不必先点【连接】。
        //   只比对 BE 的 binding 是错的 —— 用户选完设备还没点连接时，该 key 不在设备池里，
        //   sample() 恒返回 EMPTY，表现就是"绑定不了按键"（用户实测）。
        final String uiKey = "railingui:" + pos.asLong();
        final String[] uiBoundDevice = { "" };
        // 声明顺序解决循环依赖：条目区的刷新与弹窗都要用到 panel
        final UIElement[] panelHolder = { null };

        final Runnable[] refreshEntries = new Runnable[1];
        refreshEntries[0] = () -> {
            entryList.clearAllScrollViewChildren();
            for (int i = 0; i < entries.size(); i++) {
                final int index = i;
                PeripheralRailingBinding.Entry entry = entries.get(i);
                boolean waiting = recordingIndex[0] == index;

                // 左：按键绑定按钮（点击 = 选中 + 进入"按下一个键"）
                Button keyBtn = CreateUi.rowButton(selected[0] == index, () -> {
                    selected[0] = index;
                    if (deviceId[0].isEmpty()) {
                        recordingIndex[0] = -1;
                        hint.setText(Component.translatable(K + "hint_no_device"));
                        refreshEntries[0].run();
                        return;
                    }
                    recordingIndex[0] = index;
                    // 基线 = 点这一下时已经按着的键（避免"手还压着"被当成新按下）
                    lastPressed[0] = PeripheralHelmInput.sample(deviceId[0], uiKey).pressedButtons();
                    hint.setText(Component.translatable(K + "hint_recording"));
                    refreshEntries[0].run();
                });
                Label keyLabel = CreateUi.label(Component.literal(waiting
                                ? Component.translatable(K + "press_key").getString()
                                : keyText(entry)),
                        selected[0] == index ? CreateUi.BRASS : CreateUi.TEXT, 9f);
                keyLabel.layout(l -> l.marginLeftAuto());
                keyLabel.layout(l -> l.marginRightAuto());
                keyBtn.addChild(keyLabel);
                keyBtn.layout(l -> l.flex(1).height(15));

                // 右：信号按钮（点击打开滑块子页面）
                Button signalBtn = CreateUi.rowButton(false, () -> openSignalDialog(
                        panelHolder[0], index, entries, refreshEntries[0]));
                Label signalLabel = CreateUi.label(
                        Component.translatable(K + "signal_short", entry.signal()),
                        CreateUi.BRASS, 9f);
                signalLabel.layout(l -> l.marginLeftAuto());
                signalLabel.layout(l -> l.marginRightAuto());
                signalBtn.addChild(signalLabel);
                signalBtn.layout(l -> l.width(56).height(15));

                UIElement row = new UIElement().layout(l ->
                        l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
                row.addChildren(keyBtn, signalBtn);
                entryList.addScrollViewChild(row);
            }
            if (entries.isEmpty()) {
                entryList.addScrollViewChild(CreateUi.dim(K + "no_entry"));
            }
        };
        refreshEntries[0].run();

        Button addBtn = CreateUi.smallButton(K + "btn_add", 56f, () -> {
            entries.add(new PeripheralRailingBinding.Entry(0, PeripheralRailingBinding.NO_BUTTON));
            selected[0] = entries.size() - 1;
            if (deviceId[0].isEmpty()) {
                // 没有设备也不拦着用户建表：只提示"要先连设备才能绑键"
                recordingIndex[0] = -1;
                hint.setText(Component.translatable(K + "hint_no_device"));
            } else {
                recordingIndex[0] = selected[0];  // 新增条目直接进入待绑定
                lastPressed[0] = PeripheralHelmInput.sample(deviceId[0], uiKey).pressedButtons();
                hint.setText(Component.translatable(K + "hint_recording"));
            }
            refreshEntries[0].run();
        });
        Button removeBtn = CreateUi.smallButton(K + "btn_remove", 56f, () -> {
            int idx = selected[0];
            if (idx < 0 || idx >= entries.size()) {
                hint.setText(Component.translatable(K + "hint_select_first"));
                return;
            }
            entries.remove(idx);
            recordingIndex[0] = -1;
            selected[0] = entries.isEmpty() ? -1 : Math.min(idx, entries.size() - 1);
            refreshEntries[0].run();
        });
        UIElement entryButtons = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
        entryButtons.addChildren(addBtn, removeBtn);

        UIElement mapCard = CreateUi.card(K + "section_map",
                entryList,
                entryButtons,
                hint);

        // ===== 底部按钮 =====
        Button save = CreateUi.button(K + "save", () -> {
            PeripheralRailingBinding binding = bindingSupplier.get();
            pushToClient(pos, binding);
            PeripheralCore.postSend(() -> PeripheralRailingBindPayload.sendToServer(pos, binding));
            releaseUiBinding(uiKey, uiBoundDevice);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        });
        save.layout(l -> l.flex(1).height(17));
        Button cancel = CreateUi.button(K + "cancel", () -> {
            releaseUiBinding(uiKey, uiBoundDevice);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        });
        cancel.layout(l -> l.flex(1).height(17));

        // ===== 组装 =====
        UIElement panel = CreateUi.panel(CreateUi.panelWidthFor(LEFT_W, RIGHT_W));
        panelHolder[0] = panel;
        UIElement content = CreateUi.content(panel);
        content.addChildren(
                CreateUi.titleBar(Component.translatable(K + "title"), status),
                CreateUi.body(
                        CreateUi.column(LEFT_W, deviceCard),
                        CreateUi.column(RIGHT_W, mapCard)),
                CreateUi.buttonRow(save, cancel));

        // ===== 每 tick：设备列表指纹（节流）+ 连接轮询 + 录制按键 =====
        final String[] shownSignature = { "" };
        final long[] lastRebuildAt = { 0L };
        panel.addEventListener(UIEvents.TICK, ev -> {
            String signature = deviceSignature(PeripheralHelmInput.devices());
            long nowMs = System.currentTimeMillis();
            if (!signature.equals(shownSignature[0]) && nowMs - lastRebuildAt[0] >= 250L) {
                shownSignature[0] = signature;
                lastRebuildAt[0] = nowMs;
                refreshDevices[0].run();
            }
            if (be == null) {
                return;
            }

            // ★ 界面自己的临时设备引用：选中的设备一变就换绑。
            //   这样"选设备 → 直接点绑定 → 按键"立刻可用，不必先点【连接】。
            if (!uiBoundDevice[0].equals(deviceId[0])) {
                if (!uiBoundDevice[0].isEmpty()) {
                    PeripheralHelmInput.releaseUser(uiKey);
                }
                uiBoundDevice[0] = deviceId[0];
                if (!deviceId[0].isEmpty()) {
                    PeripheralHelmInput.bind(deviceId[0], uiKey);
                }
            } else if (!deviceId[0].isEmpty() && !PeripheralHelmInput.isBound(deviceId[0], uiKey)) {
                PeripheralHelmInput.bind(deviceId[0], uiKey);   // 池被重扫清过 → 补回
            }

            PeripheralHelmInput.Sample uiSample = PeripheralHelmInput.sample(deviceId[0], uiKey);

            // 实时输入监视
            if (deviceId[0].isEmpty()) {
                monitor.setText(Component.translatable(K + "monitor_none"));
                monitor.textStyle(ts -> ts.textColor(CreateUi.TEXT_DIM));
            } else if (!uiSample.connected()) {
                String err = PeripheralHelmInput.deviceError(deviceId[0]);
                monitor.setText(Component.translatable(K + "monitor_offline").copy()
                        .append(Component.literal(err == null ? "" : err)));
                monitor.textStyle(ts -> ts.textColor(CreateUi.TEXT_BAD));
            } else {
                monitor.setText(Component.translatable(K + "monitor_live",
                        Component.literal(pressedText(uiSample))));
                monitor.textStyle(ts -> ts.textColor(CreateUi.TEXT_GOOD));
            }

            // 录制：等待出现"新按下"的键（统一索引空间 = SDL 按钮 0..63 + 方向帽 8 向）
            if (recordingIndex[0] >= 0) {
                if (!uiSample.connected()) {
                    hint.setText(deviceId[0].isEmpty()
                            ? Component.translatable(K + "hint_no_device")
                            : Component.translatable(K + "hint_device_offline"));
                } else {
                    int[] pressed = uiSample.pressedButtons();
                    int picked = -1;
                    for (int b : pressed) {
                        if (!contains(lastPressed[0], b)) {
                            picked = b;
                            break;
                        }
                    }
                    if (picked >= 0) {
                        int idx = recordingIndex[0];
                        if (idx >= 0 && idx < entries.size()) {
                            // 用户 2026-09-14："每条只能绑定一个按键" —— 直接替换本条的按键，
                            // 不追加（一个按键一个信号；要多键同档位就多建一条）
                            PeripheralRailingBinding.Entry old = entries.get(idx);
                            entries.set(idx, new PeripheralRailingBinding.Entry(old.signal(), picked));
                            hint.setText(Component.translatable(K + "hint_recorded",
                                    buttonName(picked)));
                            refreshEntries[0].run();
                        }
                        recordingIndex[0] = -1;
                    }
                    lastPressed[0] = pressed;
                }
            }

            // 界面被移除（含 ESC 关闭）：释放界面自己的临时引用
            // （见下方 UIEvents.REMOVED 监听）

            // 连接状态轮询
            String key = PeripheralRailingInput.userKey(pos);
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

        // 界面被移除（含 ESC 直接关闭）：释放界面自己的临时设备引用
        panel.addEventListener(UIEvents.REMOVED, ev -> releaseUiBinding(uiKey, uiBoundDevice));

        return new ModularUI(UI.of(panel), holder.player);
    }

    // ==================== 小工具 ====================

    private static void pushToClient(BlockPos pos, PeripheralRailingBinding binding) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos)
                instanceof PeripheralRailingBlockEntity railing) {
            railing.setBinding(binding);
        }
    }

    private static int resolveLinkState(PeripheralRailingBlockEntity be, String deviceId, BlockPos pos) {
        if (be == null || deviceId == null || deviceId.isEmpty()) {
            return LINK_IDLE;
        }
        String key = PeripheralRailingInput.userKey(pos);
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

    /** 按键描述（"未绑定" / "按键 3" / "方向帽 上"） */
    private static String keyText(PeripheralRailingBinding.Entry entry) {
        return entry.bound()
                ? buttonName(entry.button())
                : Component.translatable(K + "unbound_key").getString();
    }

    /**
     * 按键名称（用户 2026-09-14："绑定后按下一个键会显示具体按键名称（比如按键1）"）：
     * SDL 索引 0 显示为<b>按键 1</b>（1-based，与设备说明书/游戏里的编号一致）；
     * 方向帽显示方向名（很多换挡器用方向帽报档位）。
     */
    private static String buttonName(int index) {
        if (index >= PeripheralHelmInput.Sample.HAT_BASE) {
            int dir = index - PeripheralHelmInput.Sample.HAT_BASE;
            if (dir < HAT_KEYS.length) {
                return Component.translatable(K + HAT_KEYS[dir]).getString();
            }
            return Component.translatable(K + "button_name", index + 1).getString();
        }
        return Component.translatable(K + "button_name", index + 1).getString();
    }

    /** 方向帽 8 向的 lang 后缀（顺序 = POV 0/4500/…/31500） */
    private static final String[] HAT_KEYS = {
            "hat_up", "hat_up_right", "hat_right", "hat_down_right",
            "hat_down", "hat_down_left", "hat_left", "hat_up_left"
    };

    /** 当前按下的键清单文本（实时监视行用；空 = "—"） */
    private static String pressedText(PeripheralHelmInput.Sample sample) {
        int[] pressed = sample.pressedButtons();
        if (pressed.length == 0) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (int b : pressed) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(buttonName(b));
        }
        return sb.toString();
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** 释放界面自己的临时设备引用（保存/取消/界面被移除时都要做，避免句柄泄漏） */
    private static void releaseUiBinding(String uiKey, String[] uiBoundDevice) {
        if (uiBoundDevice != null && !uiBoundDevice[0].isEmpty()) {
            PeripheralHelmInput.releaseUser(uiKey);
            uiBoundDevice[0] = "";
        }
    }

    /**
     * 输出信号子页面（用户定稿："右边是一个按键，按钮点击后打开一个滑块子页面，包含确定和取消"）。
     * 用共享弹窗层 {@link CreateUi#overlay}（不用 {@code Dialog}——它的尺寸/重排不可控）：
     * 内容是一个 0..15 的滑块，底部【确定】【取消】。
     */
    private static void openSignalDialog(UIElement root, int index,
                                         List<PeripheralRailingBinding.Entry> entries,
                                         Runnable onChanged) {
        if (root == null || index < 0 || index >= entries.size()) {
            return;
        }
        int current = entries.get(index).signal();

        Slider.Horizontal slider = new Slider.Horizontal();
        slider.setRange(0f, 15f);
        slider.setValue((float) current, false);
        slider.sliderStyle(st -> st.trackSize(4).handleSize(8));
        slider.layout(l -> l.flex(1).height(12));

        Label value = CreateUi.valueLabel(Component.literal(String.valueOf(current)),
                36f, CreateUi.BRASS, 10f);
        slider.setOnValueChanged(v -> value.setText(Component.literal(
                String.valueOf(Math.round(v)))));

        UIElement rowElement = new UIElement().layout(l ->
                l.width(140).flexDirection(FlexDirection.ROW).gapAll(6));
        rowElement.addChildren(slider, value);

        UIElement[] overlayRef = new UIElement[1];
        UIElement buttons = new UIElement().layout(l -> l.widthPercent(100)
                .flexDirection(FlexDirection.ROW).gapAll(4));
        buttons.addChild(CreateUi.smallButton(K + "ok", 56f, () -> {
            PeripheralRailingBinding.Entry old = entries.get(index);
            entries.set(index, new PeripheralRailingBinding.Entry(
                    Math.round(slider.getValue()), old.button()));
            onChanged.run();
            CreateUi.closeOverlay(root, overlayRef[0]);
        }));
        buttons.addChild(CreateUi.smallButton(K + "cancel", 56f,
                () -> CreateUi.closeOverlay(root, overlayRef[0])));

        // 弹窗卡片：标题 + 滑块行 + 按钮行（用共享 overlay 层，不用 Dialog）
        UIElement card = new UIElement().layout(l -> l.width(176).paddingAll(6).gapAll(5)
                .flexDirection(FlexDirection.COLUMN))
                .style(s -> s.background(CreateUi.bordered(CreateUi.PANEL_INNER, CreateUi.BRASS_DARK, 2)));
        card.addChild(CreateUi.key(K + "signal_dialog", CreateUi.BRASS, 10f));
        card.addChild(rowElement);
        card.addChild(buttons);
        overlayRef[0] = CreateUi.overlay(root, card);
    }

    private static String kindKey(com.hdf.cryptand.gameinput.GameInputDeviceKind kind) {
        return "cryptand.peripheral_helm.kind_" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String deviceSignature(List<GameInputDeviceInfo> devices) {
        StringBuilder sb = new StringBuilder();
        for (GameInputDeviceInfo d : devices) {
            sb.append(d.id()).append('|');
        }
        return sb.toString();
    }

    private static Button deviceRow(String name, String kindText, boolean selected, Runnable onClick) {
        Button row = CreateUi.rowButton(selected, onClick);
        if (name != null && !name.isEmpty()) {
            Label nameLabel = CreateUi.label(Component.literal(name),
                    selected ? CreateUi.BRASS : CreateUi.TEXT, 9f);
            nameLabel.layout(l -> l.flexShrink(1));
            row.addChild(nameLabel);
        }
        Label kindLabel = CreateUi.label(
                kindText == null ? Component.empty() : Component.literal(kindText),
                CreateUi.TEXT, 9f);
        kindLabel.layout(l -> l.marginLeftAuto());
        row.addChildren(kindLabel);
        return row;
    }
}