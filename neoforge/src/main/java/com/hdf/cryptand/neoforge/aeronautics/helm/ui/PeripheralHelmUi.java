/**
 * ===== 外设船舵绑定界面（LDLib2 · Create 风格，2026-09-13） =====
 *
 * 2026-09-13 二版（用户："太长了，UI 需要平铺；风格保持 Create 风格"）：
 * 由"一条竖到底"改为【左右两列平铺】——
 * <pre>
 *   ┌ 标题栏（黄铜条 + 状态） ─────────────────────┐
 *   │ 左列 186            │ 右列 212              │
 *   │  外部设备(列表+刷新) │  手感与力反馈(5 行)   │
 *   │  轴映射(转向/反向)   │  实时输入(2 条数值条) │
 *   ├ 保存并应用 / 取消 ───────────────────────────┤
 * </pre>
 * 观感仍是 Create：深色黄铜调面板、create:value_settings.png 的黄铜框切片与数值条轨道、
 * 按钮三态黄铜描边。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm.ui;

import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;

import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmBindPayload;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmBlockEntity;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmBinding;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
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
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public final class PeripheralHelmUi {

    private static final String K = "cryptand.peripheral_helm.";

    /** 面板宽（左右两列 + 边距）；高约 230px —— 横向平铺，不再竖成长条 */
    /** 面板宽（缩小版：左右自然留白；两列 + 内部边距） */
    private static final float LEFT_W = 170f;
    private static final float RIGHT_W = 196f;
    /** 设备列表可见高度（4 行 × 15 + 余量） */
    private static final float DEVICE_LIST_H = 46f;
    /** 连接状态机（用户要求：选中条目 → 点【连接】→ 提示框显示连接中/成功/失败） */
    private static final int LINK_IDLE = 0;
    private static final int LINK_CONNECTING = 1;
    private static final int LINK_CONNECTED = 2;
    private static final int LINK_FAILED = 3;
    /** 连接超时（ms）：超过仍未拿到 connected 采样 → 判定失败 */
    private static final long LINK_TIMEOUT_MS = 5000L;

    /**
     * 强制设备类型候选（索引即 {@code PeripheralHelmBinding.forceKind}）。
     * <p>2026-09-14 统一到 {@link com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds}：
     * 三个外设共用同一张表，并新增【键盘(SDL)】【键盘(GLFW)】两个槽位（用户要求"键盘强制部分添加
     * GLFW 和 SDL 强制两个部分，默认走 SDL"）。0 = 自动 ⇒ 完全按后端检测结果。
     */
    private static final List<String> FORCE_KINDS =
            com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds.KEYS;

    /** 手感预设候选（索引即 PeripheralHelmBinding.feelPreset：0 均衡 1 拉力 2 重卡 3 细腻） */
    private static final List<String> FEEL_PRESETS = List.of("balanced", "rally", "truck", "smooth");

    /** 轴候选（唯一来源 {@link CreateUi#AXIS_NAMES}：与拉杆/传动器同一张表，别再各写一份） */
    private static final List<String> AXES = CreateUi.AXIS_NAMES;

    private PeripheralHelmUi() {
    }

    public static ModularUI build(BlockUIHolder holder, PeripheralHelmBlockEntity be) {
        PeripheralHelmBinding initial = be != null ? be.getBinding() : PeripheralHelmBinding.DEFAULT;
        BlockPos pos = holder.pos;

        // ===== 可变绑定状态（保存时组装） =====
        String[] deviceId = { initial.deviceId() };
        int[] steerAxis = { Mth.clamp(initial.steerAxis(), 0, 7) };
        boolean[] invert = { initial.invertSteer() };
        float[] maxAngle = { initial.maxAngleDeg() };
        float[] deadzone = { initial.deadzone() };
        boolean[] ffb = { initial.forceFeedback() };
        float[] ffbStrength = { initial.ffbStrength() };
        float[] ffbMinAccel = { initial.ffbMinAccel() };
        float[] ffbFullAccel = { initial.ffbFullAccel() };
        // 强制设备类型（用户可在下拉框指定；⚠ 只在点【连接】时随绑定写入生效）
        final int[] forceKind = { Mth.clamp(initial.forceKind(), 0, FORCE_KINDS.size() - 1) };
        // 手感预设（力反馈通道配比）
        final int[] feelPreset = { Mth.clamp(initial.feelPreset(), 0, FEEL_PRESETS.size() - 1) };

        // ===== 标题栏（右侧连接状态） =====
        Label status = CreateUi.label(Component.literal("—"), CreateUi.TEXT_DIM, 9f);

        // ===== 左列 · 外部设备 =====
        ScrollerView deviceList = new ScrollerView();
        deviceList.layout(l -> l.widthPercent(100).height(DEVICE_LIST_H));
        deviceList.viewPort(v -> v.layout(l -> l.paddingAll(2)));
        deviceList.viewContainer(c -> c.layout(l -> l.gapAll(2)));

        // 后端诊断（SDL2 是否可用 / 失败原因）——"没有设备"时一眼看到根因
        Label diag = CreateUi.label(Component.literal(PeripheralHelmInput.diagnostic()),
                CreateUi.TEXT_DIM, 8f);
        diag.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        diag.layout(l -> l.widthPercent(100));

        // 连接提示框（点【连接】后显示：连接中… / 已连接 / 连接失败：原因）
        Label linkLabel = CreateUi.label(Component.translatable(K + "link_idle"),
                CreateUi.TEXT_DIM, 8f);
        linkLabel.textStyle(ts -> ts.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        linkLabel.layout(l -> l.widthPercent(100));
        // ⚠ 连接状态来自【真实状态源】（方块实体的绑定 + 客户端设备池），不是界面局部变量 ——
        //   否则关掉界面再打开会退回"需要连接"（用户实测反馈）。
        final int[] linkState = { resolveLinkState(be, deviceId[0]) };
        final String[] linkDetail = { resolveLinkDetail(deviceId[0], linkState[0]) };
        final long[] linkStartedAt = { System.currentTimeMillis() };

        // 当前界面参数 → 绑定对象（连接 / 断开 / 保存共用）
        final java.util.function.Supplier<PeripheralHelmBinding> bindingSupplier =
                () -> new PeripheralHelmBinding(
                        deviceId[0], steerAxis[0], invert[0],
                        maxAngle[0], deadzone[0], ffb[0], ffbStrength[0],
                        ffbMinAccel[0], Math.max(ffbMinAccel[0] + 0.5f, ffbFullAccel[0]),
                        forceKind[0], feelPreset[0]);

        // 强制类型下拉框（自动检测常把国产方向盘认成"控制器"，这里可手动纠正）
        List<String> kindCandidates = new ArrayList<>();
        for (String kind : FORCE_KINDS) {
            kindCandidates.add(Component.translatable(K + "force_kind_" + kind).getString());
        }
        Selector<String> forceKindSelector = new Selector<>();
        forceKindSelector.setCandidates(kindCandidates);
        forceKindSelector.setSelected(kindCandidates.get(forceKind[0]), false);
        forceKindSelector.setOnValueChanged(v -> forceKind[0] = Math.max(0, kindCandidates.indexOf(v)));
        forceKindSelector.layout(l -> l.width(96).height(14));

        // 手感预设下拉框
        List<String> feelCandidates = new ArrayList<>();
        for (String preset : FEEL_PRESETS) {
            feelCandidates.add(Component.translatable(K + "feel_" + preset).getString());
        }
        Selector<String> feelPresetSelector = new Selector<>();
        feelPresetSelector.setCandidates(feelCandidates);
        feelPresetSelector.setSelected(feelCandidates.get(feelPreset[0]), false);
        feelPresetSelector.setOnValueChanged(v -> feelPreset[0] = Math.max(0, feelCandidates.indexOf(v)));
        feelPresetSelector.layout(l -> l.width(96).height(14));

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            deviceList.clearAllScrollViewChildren();
            // 设备列表来自【客户端全局设备池】（零阻塞；扫描只由"刷新设备"触发）
            List<GameInputDeviceInfo> devices = PeripheralHelmInput.devices();
            String myKey = be != null ? be.userKey() : null;
            deviceList.addScrollViewChild(deviceRow(
                    Component.translatable(K + "none").getString(), null,
                    deviceId[0].isEmpty(),
                    () -> {
                        deviceId[0] = "";
                        refresh[0].run();
                    }));
            for (GameInputDeviceInfo d : devices) {
                // 设备共享（引用计数）：行内标注当前有几个使用者在用 —— 不再有"被占用/抢占"这回事
                int users = PeripheralHelmInput.userCount(d.id());
                // 显示"强制优先"的类型（自动 → 后端判定），让玩家能立刻看到纠正结果
            com.hdf.cryptand.gameinput.GameInputDeviceKind shownKind =
                    com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput.kindOf(d.id());
            String kind = Component.translatable(kindKey(shownKind == null ? d.kind() : shownKind)).getString()
                        + (users > 0
                        ? " · " + Component.translatable(K + "shared_mark", users).getString()
                        : "");
                deviceList.addScrollViewChild(deviceRow(d.name(), kind,
                        d.id().equals(deviceId[0]), () -> {
                            deviceId[0] = d.id();
                            refresh[0].run();
                        }));
            }
            if (devices.isEmpty()) {
                deviceList.addScrollViewChild(CreateUi.dim(K + "no_device"));
            }
            diag.setText(Component.literal(PeripheralHelmInput.diagnostic()));
        };
        // 只渲染池内现有设备（用户要求："只有点刷新才会进行一次扫描"）
        refresh[0].run();

        Button refreshBtn = CreateUi.smallButton(K + "refresh", 48f, () -> {
            PeripheralHelmInput.requestScan();
            refresh[0].run();
        });

        // 连接：先选条目 → 点这里才真正绑定并打开设备；结果写进提示框
        Button connectBtn = CreateUi.smallButton(K + "connect", 52f, () -> {
            if (deviceId[0].isEmpty()) {
                linkState[0] = LINK_FAILED;
                linkDetail[0] = Component.translatable(K + "link_pick_first").getString();
                return;
            }
            // ★ 连接流程（用户定稿 2026-09-13）：先【断开当前连接】，再【连接新设备】——
            //   断开让设备池释放旧引用（引用计数归零 ⇒ 采样线程关闭旧句柄 + 停掉全部力反馈），
            //   随后才绑定新设备，避免旧设备的力残留在方向盘上。
            String target = deviceId[0];
            Minecraft mc = Minecraft.getInstance();

            deviceId[0] = "";
            PeripheralHelmBinding disconnected = bindingSupplier.get();
            if (mc.level != null && mc.level.getBlockEntity(pos)
                    instanceof PeripheralHelmBlockEntity local) {
                local.setBinding(disconnected);
            }
            // 统一走异步核心出站队列（用户："每次UI更新数据到服务器也是走这个异步核心，方便管理"）
            PeripheralCore.postSend(() -> PeripheralHelmBindPayload.sendToServer(pos, disconnected));

            deviceId[0] = target;
            PeripheralHelmBinding connected = bindingSupplier.get();
            if (mc.level != null && mc.level.getBlockEntity(pos)
                    instanceof PeripheralHelmBlockEntity local) {
                local.setBinding(connected);   // 客户端本地 → 设备池按 id 打开（后台线程）
            }
            PeripheralCore.postSend(() -> PeripheralHelmBindPayload.sendToServer(pos, connected));

            linkState[0] = LINK_CONNECTING;
            linkStartedAt[0] = System.currentTimeMillis();
            linkDetail[0] = "";
        });

        // 断开：清空设备选择并解绑（设备池随后关闭该设备）
        Button disconnectBtn = CreateUi.smallButton(K + "disconnect", 52f, () -> {
            deviceId[0] = "";
            PeripheralHelmBinding binding = bindingSupplier.get();   // deviceId 空 = 不绑定
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getBlockEntity(pos)
                    instanceof PeripheralHelmBlockEntity local) {
                local.setBinding(binding);
            }
            PeripheralCore.postSend(() -> PeripheralHelmBindPayload.sendToServer(pos, binding));
            linkState[0] = LINK_IDLE;
            linkDetail[0] = Component.translatable(K + "link_disconnected").getString();
            refresh[0].run();
        });

        // 校准中位：把方向盘【现在停在哪】记为 0 度（中位漂了 / 非中位连接导致一上来就偏时点它）
        Button recenterBtn = CreateUi.smallButton(K + "recenter", 64f, () -> {
            if (deviceId[0].isEmpty()) {
                linkState[0] = LINK_FAILED;
                linkDetail[0] = Component.translatable(K + "link_pick_first").getString();
                return;
            }
            String ownerKey = "";
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getBlockEntity(pos)
                    instanceof PeripheralHelmBlockEntity local) {
                ownerKey = local.userKey();
            }
            if (PeripheralHelmInput.requestRecenter(deviceId[0], ownerKey)) {
                linkState[0] = LINK_CONNECTED;
                linkDetail[0] = Component.translatable(K + "recenter_done").getString();
            } else {
                linkState[0] = LINK_FAILED;
                linkDetail[0] = Component.translatable(K + "recenter_need_connect").getString();
            }
        });

        // 四个按钮平分同一行：左列只有 170px 宽，长文案会被裁掉、还会把卡片撑高到超出窗口
        refreshBtn.layout(l -> l.flex(1).height(14));
        connectBtn.layout(l -> l.flex(1).height(14));
        disconnectBtn.layout(l -> l.flex(1).height(14));
        recenterBtn.layout(l -> l.flex(1).height(14));
        UIElement deviceButtons = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
        deviceButtons.addChildren(refreshBtn, connectBtn, disconnectBtn, recenterBtn);

        UIElement deviceCard = CreateUi.card(K + "section_device",
                CreateUi.row(K + "force_kind", forceKindSelector),
                deviceButtons,
                deviceList,
                diag,
                linkLabel);

        // ===== 左列 · 轴映射 =====
        Selector<String> steerSelector = new Selector<>();
        steerSelector.setCandidates(AXES);
        steerSelector.setSelected(AXES.get(steerAxis[0]), false);
        steerSelector.setOnValueChanged(v -> steerAxis[0] = Math.max(0, AXES.indexOf(v)));
        steerSelector.layout(l -> l.width(74).height(14));

        Switch invertSwitch = new Switch();
        invertSwitch.setOn(invert[0]);
        invertSwitch.setOnSwitchChanged(v -> invert[0] = v);
        invertSwitch.layout(l -> l.width(16).height(9));

        UIElement axisCard = CreateUi.card(K + "section_axis",
                CreateUi.row(K + "steer_axis", steerSelector),
                CreateUi.row(K + "invert", invertSwitch));

        // ===== 右列 · 手感与力反馈 =====
        Slider.Horizontal maxAngleSlider = slider(90f, 900f, maxAngle[0]);
        Label maxAngleValue = CreateUi.valueLabel(angleText(maxAngle[0]), 46f, CreateUi.BRASS, 10f);
        maxAngleSlider.setOnValueChanged(v -> {
            maxAngle[0] = Math.round(v / 10f) * 10f;
            maxAngleValue.setText(angleText(maxAngle[0]));
        });

        Slider.Horizontal deadzoneSlider = slider(0f, 30f, deadzone[0] * 100f);
        Label deadzoneValue = CreateUi.valueLabel(percentText(deadzone[0]), 46f, CreateUi.BRASS, 10f);
        deadzoneSlider.setOnValueChanged(v -> {
            deadzone[0] = Mth.clamp(v / 100f, 0f, 0.3f);
            deadzoneValue.setText(percentText(deadzone[0]));
        });

        Switch ffbSwitch = new Switch();
        ffbSwitch.setOn(ffb[0]);
        ffbSwitch.setOnSwitchChanged(v -> ffb[0] = v);
        ffbSwitch.layout(l -> l.width(16).height(9));

        Slider.Horizontal ffbSlider = slider(0f, 100f, ffbStrength[0] * 100f);
        Label ffbValue = CreateUi.valueLabel(percentText(ffbStrength[0]), 40f, CreateUi.BRASS, 10f);
        ffbSlider.setOnValueChanged(v -> {
            ffbStrength[0] = Mth.clamp(v / 100f, 0f, 1f);
            ffbValue.setText(percentText(ffbStrength[0]));
        });

        UIElement ffbRow = new UIElement()
                .layout(l -> l.widthPercent(100).height(CreateUi.ROW_H).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER).gapAll(4));
        ffbRow.addChild(CreateUi.fixedLabel(K + "force_feedback", CreateUi.LABEL_W, CreateUi.TEXT));
        ffbRow.addChild(ffbSwitch);
        ffbRow.addChild(ffbSlider.layout(l -> l.flex(1).height(9)));
        ffbRow.addChild(ffbValue);

        // ⚠ 满力度控件先声明——"最小加速度"的回调会引用它（Java 局部变量须先声明）
        Slider.Horizontal ffbFullSlider = slider(1f, 60f, ffbFullAccel[0]);
        Label ffbFullValue = CreateUi.valueLabel(accelText(ffbFullAccel[0]), 52f, CreateUi.BRASS, 10f);
        Slider.Horizontal ffbMinSlider = slider(0f, 30f, ffbMinAccel[0]);
        Label ffbMinValue = CreateUi.valueLabel(accelText(ffbMinAccel[0]), 52f, CreateUi.BRASS, 10f);
        ffbMinSlider.setOnValueChanged(v -> {
            ffbMinAccel[0] = Math.round(v * 2f) / 2f;   // 0.5 m/s2 步进
            ffbMinValue.setText(accelText(ffbMinAccel[0]));
            if (ffbFullAccel[0] <= ffbMinAccel[0]) {
                ffbFullAccel[0] = ffbMinAccel[0] + 0.5f;
                ffbFullValue.setText(accelText(ffbFullAccel[0]));
            }
        });
        ffbFullSlider.setOnValueChanged(v -> {
            ffbFullAccel[0] = Math.min(60f, Math.max(ffbMinAccel[0] + 0.5f, Math.round(v)));
            ffbFullValue.setText(accelText(ffbFullAccel[0]));
        });

        UIElement feelCard = CreateUi.card(K + "section_feel",
                CreateUi.sliderRow(K + "max_angle", maxAngleSlider, maxAngleValue),
                CreateUi.sliderRow(K + "deadzone", deadzoneSlider, deadzoneValue),
                ffbRow,
                CreateUi.sliderRow(K + "ffb_min_accel", ffbMinSlider, ffbMinValue),
                CreateUi.sliderRow(K + "ffb_full_accel", ffbFullSlider, ffbFullValue),
                CreateUi.row(K + "feel_preset", feelPresetSelector));

        // ===== 右列 · 实时输入 =====
        UIElement steerFill = new UIElement();
        UIElement accelFill = new UIElement();
        Label steerValue = CreateUi.valueLabel(Component.literal("0.00"), 78f, CreateUi.TEXT, 9f);
        Label accelValue = CreateUi.valueLabel(Component.literal("0.00 m/s2"), 78f, CreateUi.TEXT, 9f);

        // 重置为默认值（保留当前选中的设备；只是把参数恢复出厂并同步控件显示）
        Button resetBtn = CreateUi.button(K + "reset_default", () -> {
            PeripheralHelmBinding defaults = PeripheralHelmBinding.DEFAULT;
            steerAxis[0] = defaults.steerAxis();
            invert[0] = defaults.invertSteer();
            maxAngle[0] = defaults.maxAngleDeg();
            deadzone[0] = defaults.deadzone();
            ffb[0] = defaults.forceFeedback();
            ffbStrength[0] = defaults.ffbStrength();
            ffbMinAccel[0] = defaults.ffbMinAccel();
            ffbFullAccel[0] = defaults.ffbFullAccel();
            forceKind[0] = defaults.forceKind();
            feelPreset[0] = defaults.feelPreset();
            // 同步所有控件显示
            steerSelector.setSelected(AXES.get(steerAxis[0]), false);
            invertSwitch.setOn(invert[0]);
            maxAngleSlider.setValue(maxAngle[0], false);
            maxAngleValue.setText(angleText(maxAngle[0]));
            deadzoneSlider.setValue(deadzone[0] * 100f, false);
            deadzoneValue.setText(percentText(deadzone[0]));
            ffbSwitch.setOn(ffb[0]);
            ffbSlider.setValue(ffbStrength[0] * 100f, false);
            ffbValue.setText(percentText(ffbStrength[0]));
            ffbMinSlider.setValue(ffbMinAccel[0], false);
            ffbMinValue.setText(accelText(ffbMinAccel[0]));
            ffbFullSlider.setValue(ffbFullAccel[0], false);
            ffbFullValue.setText(accelText(ffbFullAccel[0]));
            forceKindSelector.setSelected(kindCandidates.get(forceKind[0]), false);
            feelPresetSelector.setSelected(feelCandidates.get(feelPreset[0]), false);
        });
        resetBtn.layout(l -> l.flex(1).height(16));

        // 测试力反馈：对手持设备下发【最大强度、5 秒】恒定力（不依赖船体是否在动）
        Button ffbTestBtn = CreateUi.button(K + "ffb_test", () -> {
            if (be == null || deviceId[0].isEmpty()
                    || !PeripheralHelmInput.isBound(deviceId[0], be.userKey())) {
                linkState[0] = LINK_FAILED;
                linkDetail[0] = Component.translatable(K + "ffb_test_need_connect").getString();
                return;
            }
            PeripheralHelmInput.requestSelfTest(deviceId[0], be.userKey(), 10000, 5000L);
        });
        ffbTestBtn.layout(l -> l.flex(1).height(16));

        // 停止力反馈：一次停掉设备上【所有】力（含失去追踪的残留效果），并抑制 10 秒自动下发
        // —— 用户实测"方向盘被顶在 -180°、只有插拔 USB 才恢复"，这个按钮就是不用拔线的自救手段
        Button ffbStopBtn = CreateUi.button(K + "ffb_stop", () -> {
            if (be == null || deviceId[0].isEmpty()
                    || !PeripheralHelmInput.isBound(deviceId[0], be.userKey())) {
                linkState[0] = LINK_FAILED;
                linkDetail[0] = Component.translatable(K + "ffb_test_need_connect").getString();
                return;
            }
            if (PeripheralHelmInput.requestStopForce(deviceId[0], be.userKey(), 10000L)) {
                linkState[0] = LINK_CONNECTED;
                linkDetail[0] = Component.translatable(K + "ffb_stop_done").getString();
            } else {
                linkState[0] = LINK_FAILED;
                linkDetail[0] = Component.translatable(K + "ffb_test_need_connect").getString();
            }
        });
        ffbStopBtn.layout(l -> l.flex(1).height(16));

        UIElement liveButtons = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
        liveButtons.addChildren(resetBtn, ffbTestBtn, ffbStopBtn);

        // 轴值诊断：显示【转向轴原始读数】+ 若另一个轴明显更活跃则提示它（一眼看出轴映射选错没）
        Label axesValue = CreateUi.valueLabel(Component.literal(""), 128f, CreateUi.TEXT, 8f);

        UIElement liveCard = CreateUi.card(K + "section_live",
                CreateUi.gaugeLine(K + "live_steer", steerFill, steerValue),
                CreateUi.gaugeLine(K + "live_accel", accelFill, accelValue),
                CreateUi.row(K + "live_axes", axesValue),
                liveButtons);

        // ===== 底部按钮 =====
        Button save = CreateUi.button(K + "save", () -> {
            PeripheralHelmBinding binding = bindingSupplier.get();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getBlockEntity(pos)
                    instanceof PeripheralHelmBlockEntity local) {
                local.setBinding(binding);   // 本地立即生效（含采样线程切设备）
            }
            PeripheralCore.postSend(() -> PeripheralHelmBindPayload.sendToServer(pos, binding));
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

        // ===== 平铺组装 =====
        UIElement panel = CreateUi.panel(CreateUi.panelWidthFor(LEFT_W, RIGHT_W));
        UIElement content = CreateUi.content(panel);
        content.addChildren(
                CreateUi.titleBar(Component.translatable(K + "title"), status),
                CreateUi.body(
                        CreateUi.column(LEFT_W, deviceCard, axisCard),
                        CreateUi.column(RIGHT_W, feelCard, liveCard)),
                CreateUi.buttonRow(save, cancel));

        // ===== 每 tick 刷新实时数据（读客户端 BE 的采样快照） =====
        final String[] shownSignature = { "" };
        // 双保险：即使列表指纹发生抖动，也限制"重建整个列表"的最小间隔 ——
        // 重建会创建大量 LDLib2 元素并触发整屏重绘，绝不能每 tick 做（那会把渲染线程 CPU 打满）。
        final long[] lastRebuildAt = { 0L };
        panel.addEventListener(UIEvents.TICK, ev -> {
            // 后台扫描完成后（设备缓存变化）自动重建列表 —— 主线程始终零阻塞
            String signature = deviceSignature(PeripheralHelmInput.devices());
            long nowMs = System.currentTimeMillis();
            if (!signature.equals(shownSignature[0]) && nowMs - lastRebuildAt[0] >= 250L) {
                shownSignature[0] = signature;
                lastRebuildAt[0] = nowMs;
                refresh[0].run();
            }
            if (be == null) {
                status.setText(Component.translatable(K + "status_unknown"));
                return;
            }
            float steer = be.getClientSteer();
            steerFill.layout(l -> l.widthPercent(Math.min(100f, Math.abs(steer) * 100f)));
            steerValue.setText(Component.literal(String.format("%+.2f  %.0f°", steer, be.getTargetAngle())));

            // 船体加速度 + 力反馈强度（超过"最小加速度"才有值）
            float accel = be.getStructureAccelMps2();
            float span = Math.max(0.5f, ffbFullAccel[0]);
            accelFill.layout(l -> l.widthPercent(
                    Math.min(100f, Math.max(0f, accel / span * 100f))));
            accelValue.setText(Component.literal(String.format("%.1fm/s2 %d%%",
                    accel, Math.round(be.getForceStrength() * 100f))));

            // 轴值诊断：转向轴原始读数（未去死区）+ 更活跃的其它轴提示
            float[] axesNow = be.getClientAxes();
            int steerAxisNow = be.getSteerAxisIndex();
            StringBuilder axisText = new StringBuilder(axisName(steerAxisNow))
                    .append(String.format(":%+.2f", axisValueAt(axesNow, steerAxisNow)));
            int activeAxis = -1;
            float activeBest = 0.25f;
            for (int i = 0; i < axesNow.length; i++) {
                float a = Math.abs(axesNow[i]);
                if (a > activeBest) {
                    activeBest = a;
                    activeAxis = i;
                }
            }
            if (activeAxis >= 0 && activeAxis != steerAxisNow) {
                axisText.append("  ").append(Component.translatable(K + "live_active").getString())
                        .append(axisName(activeAxis))
                        .append(String.format("%+.2f", axesNow[activeAxis]));
            }
            axesValue.setText(Component.literal(axisText.toString()));

            // 绑定设备打开失败的原因（设备被其它程序独占 / 驱动拒绝等）
            String deviceError = deviceId[0].isEmpty()
                    ? null : PeripheralHelmInput.deviceError(deviceId[0]);
            diag.setText(Component.literal(deviceError == null
                    ? PeripheralHelmInput.diagnostic()
                    : PeripheralHelmInput.diagnostic() + " | " + deviceError));

            // ===== 连接状态轮询（点【连接】后）→ 提示框 =====
            if (linkState[0] == LINK_CONNECTING) {
                String target = deviceId[0];
                String myKey = be.userKey();
                boolean held = PeripheralHelmInput.isBound(target, myKey);
                boolean live = PeripheralHelmInput.sample(target, myKey).connected();
                if (held && live) {
                    linkState[0] = LINK_CONNECTED;
                    linkDetail[0] = "";
                    // 连接成功 → 触发一次力反馈自检（恒定力 1.2s）：
                    // 静止时按设计没有加速度反馈，自检用于区分"设备不支持/链路没通"与"没在动"
                    PeripheralHelmInput.requestSelfTest(target, myKey);
                } else if (System.currentTimeMillis() - linkStartedAt[0] > LINK_TIMEOUT_MS) {
                    linkState[0] = LINK_FAILED;
                    String err = PeripheralHelmInput.deviceError(target);
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
                    if (PeripheralHelmInput.isSelfTesting(deviceId[0])) {
                        // 测试力反馈进行中（最大强度 5 秒）
                        linkColor = CreateUi.TEXT_WARN;
                        linkText = Component.translatable(K + "ffb_test_running");
                        break;
                    }
                    linkColor = CreateUi.TEXT_GOOD;
                    String ffbText = PeripheralHelmInput.ffbStatus(deviceId[0]);
                    // ★ "连接设备后显示当前设备作为什么设备连接的"：
                    //   强制类型优先（玩家显式指定），未强制（自动）则用后端判定结果 —— 一律走
                    //   PeripheralHelmInput.kindOf，界面显示的与设备池实际生效的必然一致。
                    com.hdf.cryptand.gameinput.GameInputDeviceKind shownKind =
                            com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                                    .kindOf(deviceId[0]);
                    String shownName =
                            com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                                    .deviceName(deviceId[0]);
                    String kindText = "  ·  "
                            + (shownName == null || shownName.isEmpty() ? deviceId[0] : shownName)
                            + (shownKind == null ? ""
                            : " [" + Component.translatable(kindKey(shownKind)).getString() + "]");
                    linkText = Component.translatable(K + "link_connected").copy()
                            .append(Component.literal(kindText
                                    + (ffbText == null ? "" : "  ·  " + ffbText)));
                }
                case LINK_FAILED -> {
                    linkColor = CreateUi.TEXT_BAD;
                    linkText = Component.translatable(K + "link_failed").copy()
                            .append(Component.literal(linkDetail[0]));
                }
                default -> {
                    linkColor = CreateUi.TEXT_DIM;
                    linkText = linkDetail[0].isEmpty()
                            ? Component.translatable(K + "link_idle")
                            : Component.literal(linkDetail[0]);
                }
            }
            linkLabel.setText(linkText);
            linkLabel.textStyle(ts -> ts.textColor(linkColor));

            boolean bound = !deviceId[0].isEmpty();
            boolean connected = be.isClientDeviceConnected();
            if (!bound) {
                status.setText(CreateUi.key(K + "status_unbound", CreateUi.TEXT_DIM, 9f).getText());
            } else if (connected) {
                status.setText(CreateUi.key(K + "status_connected", CreateUi.TEXT_GOOD, 9f).getText());
            } else {
                status.setText(CreateUi.key(K + "status_disconnected", CreateUi.TEXT_BAD, 9f).getText());
            }
        });

        return new ModularUI(UI.of(panel), holder.player);
    }

    // ==================== 小工具 ====================

    /**
     * 界面打开时的真实连接状态：
     * 绑定为空 → 未连接；池里持有且采样连通 → 已连接；有错误 → 连接失败；否则（正在打开/重试）→ 连接中。
     * 数据全部来自 BE 与设备池 ⇒ 状态跨界面开关、跨重进世界都成立。
     */
    private static int resolveLinkState(PeripheralHelmBlockEntity be, String deviceId) {
        if (be == null || deviceId == null || deviceId.isEmpty()) {
            return LINK_IDLE;
        }
        String key = be.userKey();
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

    /** 设备行：名称 + 右对齐类型（选中行黄铜高亮） */
    private static String axisName(int index) {
        return index >= 0 && index < AXES.size() ? AXES.get(index) : ("轴" + index);
    }

    private static float axisValueAt(float[] axes, int index) {
        return axes != null && index >= 0 && index < axes.length ? axes[index] : 0f;
    }

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

    /** 设备列表指纹（id 序列；用于检测后台扫描结果变化） */
    private static String deviceSignature(List<GameInputDeviceInfo> devices) {
        if (devices.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (GameInputDeviceInfo d : devices) {
            sb.append(d.id()).append('|');
        }
        return sb.toString();
    }

    private static Slider.Horizontal slider(float min, float max, float value) {
        Slider.Horizontal s = new Slider.Horizontal();
        s.setRange(min, max);
        s.setValue(Mth.clamp(value, min, max), false);
        s.sliderStyle(st -> st.trackSize(4).handleSize(8));
        s.layout(l -> l.height(9));
        return s;
    }

    private static Component angleText(float degrees) {
        return Component.literal(String.format("%.0f°", degrees));
    }

    private static Component percentText(float ratio) {
        return Component.literal(String.format("%.0f%%", ratio * 100f));
    }

    /** 加速度数值（m/s²；ASCII "m/s2" —— MC 默认字体无上标） */
    private static Component accelText(float accel) {
        return Component.literal(String.format("%.1f m/s2", accel));
    }

    private static String kindKey(GameInputDeviceKind kind) {
        return switch (kind) {
            case WHEEL -> K + "kind_wheel";
            case GAMEPAD -> K + "kind_gamepad";
            case JOYSTICK -> K + "kind_joystick";
            case KEYBOARD -> K + "kind_keyboard";
            default -> K + "kind_other";
        };
    }
}
