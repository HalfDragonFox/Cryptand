/**
 * ===== 外设拉杆绑定（2026-09-13） =====
 *
 * 复制自航空学（simulated）的油门拉杆 `throttle_lever`：官方本体是"玩家按住手柄、逐档推动的
 * 16 档推杆"（state = 0..15），并把档位当作**红石信号强度**输出（`getSignal` 0..15）。
 * 官方另留了 `setSignal(int)` 这个"外部设定档位"的入口 —— 外设拉杆就是从这里接进来的：
 * 把外部设备（踏板/方向盘的离合、油门、刹车轴，或按钮）映射成 0..15 档，喂给 setSignal。
 *
 * 输入模式（用户定稿）：
 * <ul>
 *   <li>{@link #MODE_AUTO 自动}：轴与按键<b>同时生效</b>（谁动听谁的，不需要预判设备类型）；</li>
 *   <li>{@link #MODE_AXIS 仅轴}：只有踏板轴驱动；</li>
 *   <li>{@link #MODE_BUTTON 仅按键}：只有"加档/减档"两个按钮驱动（点动式）。</li>
 * </ul>
 * 轴 → 档位是**线性映射**（用户定稿"线性 0-15"）：行程比例 × 15，四舍五入。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

public record PeripheralLeverBinding(
        /** 稳定设备 id（"" = 未绑定） */
        String deviceId,
        /** 输入模式：0 自动 / 1 仅轴 / 2 仅按键 */
        int inputMode,
        /** 踏板轴索引 0..7（顺序 = GameInputState 的 8 轴标准顺序） */
        int axisIndex,
        /** 轴反向（部分踏板"未踩"读数是 +1 或 -1，与直觉相反） */
        boolean invertAxis,
        /** 死区（0..0.9；吸收踏板空行程） */
        float deadzone,
        /** 行程下限（归一化轴值；映射到档位 0） */
        float travelMin,
        /** 行程上限（归一化轴值；映射到档位 15） */
        float travelMax,
        /** 加档按钮索引（-1 = 未指定） */
        int buttonUp,
        /** 减档按钮索引（-1 = 未指定） */
        int buttonDown,
        /**
         * 强制设备类型（0 = 自动，按后端检测；见 {@code PeripheralForceKinds}）。
         * 用户："外设拉杆按钮与非按钮版本都可以和船舵一样设置设备强制，防止识别有问题"。
         */
        int forceKind) {

    public static final int MODE_AUTO = 0;
    public static final int MODE_AXIS = 1;
    public static final int MODE_BUTTON = 2;

    /** 默认：未绑定设备；自动模式；X 轴；行程 0..1（多数油门踏板未踩=0、踩到底=1） */
    public static final PeripheralLeverBinding DEFAULT =
            new PeripheralLeverBinding("", MODE_AUTO, 0, false, 0.05f, 0f, 1f, -1, -1, 0);

    public boolean bound() {
        return deviceId != null && !deviceId.isEmpty();
    }

    /** 轴输入是否生效（自动模式下与按键并存） */
    public boolean axisEnabled() {
        return inputMode == MODE_AUTO || inputMode == MODE_AXIS;
    }

    /** 按键输入是否生效 */
    public boolean buttonEnabled() {
        return inputMode == MODE_AUTO || inputMode == MODE_BUTTON;
    }

    /**
     * 轴原始值 → 档位 0..15（<b>线性映射</b>）：
     * <pre>
     *   v = 反向 ? -raw : raw
     *   t = (v - travelMin) / (travelMax - travelMin)      // 行程比例，超出即夹取
     *   t ≤ deadzone      → 0（空行程）
     *   否则              → (t - deadzone) / (1 - deadzone)
     *   state = round(t × 15)
     * </pre>
     */
    public int stateOfAxis(float raw) {
        float v = invertAxis ? -raw : raw;
        float span = travelMax - travelMin;
        if (Math.abs(span) < 1.0E-4f) {
            return 0;
        }
        float t = Mth.clamp((v - travelMin) / span, 0f, 1f);
        float dz = Mth.clamp(deadzone, 0f, 0.9f);
        if (t <= dz) {
            t = 0f;
        } else {
            t = (t - dz) / (1f - dz);
        }
        return Mth.clamp(Math.round(t * 15f), 0, 15);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Device", deviceId == null ? "" : deviceId);
        tag.putInt("Mode", inputMode);
        tag.putInt("Axis", axisIndex);
        tag.putBoolean("Invert", invertAxis);
        tag.putFloat("Deadzone", deadzone);
        tag.putFloat("TravelMin", travelMin);
        tag.putFloat("TravelMax", travelMax);
        tag.putInt("BtnUp", buttonUp);
        tag.putInt("BtnDown", buttonDown);
        tag.putInt("ForceKind", forceKind);   // 固定化参数（用户配置）→ 落盘
        return tag;
    }

    public static PeripheralLeverBinding load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return DEFAULT;
        }
        return new PeripheralLeverBinding(
                tag.getString("Device"),
                tag.getInt("Mode"),
                tag.getInt("Axis"),
                tag.getBoolean("Invert"),
                tag.contains("Deadzone") ? tag.getFloat("Deadzone") : DEFAULT.deadzone(),
                tag.contains("TravelMin") ? tag.getFloat("TravelMin") : DEFAULT.travelMin(),
                tag.contains("TravelMax") ? tag.getFloat("TravelMax") : DEFAULT.travelMax(),
                tag.contains("BtnUp") ? tag.getInt("BtnUp") : -1,
                tag.contains("BtnDown") ? tag.getInt("BtnDown") : -1,
                tag.contains("ForceKind") ? tag.getInt("ForceKind") : 0);
    }
}
