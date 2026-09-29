/**
 * ===== 外设船舵绑定参数（2026-09-13） =====
 *
 * 一个"外设船舵"方块对应一份绑定：外部设备 id（gameinput 库的 SDL 设备）+ 轴映射
 * + 手感参数（含【船体加速度 → 力反馈】阈值）。全部字段可变（UI 里改完发
 * {@link PeripheralHelmBindPayload} 到服务端）。
 *
 * 轴上语义沿用 {@link com.hdf.cryptand.gameinput.GameInputState} 的 8 轴标准顺序
 * （0=X 转向 / 1=Y 离合 / 2=Z 油门 / 3=Rx 刹车 …）。
 * <p>⚠ 2026-09-13 用户定稿："外设船舵仅方向盘" —— 本方块只处理<b>转向</b>，
 * 原"油门轴"字段已移除（油门改由其它机制实现）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import net.minecraft.nbt.CompoundTag;

public record PeripheralHelmBinding(
        /** 绑定设备 id（"sdl:0" 等；空串 = 未绑定） */
        String deviceId,
        /** 转向轴索引（0..7） */
        int steerAxis,
        /** 转向反向（部分方向盘 X 轴方向相反） */
        boolean invertSteer,
        /** 舵轮最大转角（度；90..900，对应真车方向盘锁止角） */
        float maxAngleDeg,
        /** 死区（0..0.3；消除方向盘回正抖动） */
        float deadzone,
        /** 力反馈开关 */
        boolean forceFeedback,
        /** 力反馈最大强度（0..1；加速度满量程时的阻尼力度） */
        float ffbStrength,
        /** 力反馈【最小加速度】阈值（m/s²；低于它不反馈——用户要求） */
        float ffbMinAccel,
        /** 力反馈【满力度加速度】（m/s²；达到/超过它输出 {@link #ffbStrength} 满力度） */
        float ffbFullAccel,
        /**
         * 强制设备类型覆盖（0=自动检测 1=方向盘 2=手柄 3=摇杆 4=通用）。
         * <p>自动检测对部分方向盘（如 PXN/FXN 系）会失效，用户可在界面下拉框里强制指定；
         * <b>只在点【连接】时写入并生效</b>（未点连接前改下拉框不影响已连接的设备）。
         */
        int forceKind,
        /** 手感预设（0=均衡 1=拉力 2=重卡 3=细腻）：只调各力反馈通道比例，总增益仍是 forceFeedback/ffbStrength */
        int feelPreset) {

    /** 默认绑定（未绑定设备 + 常见罗技布局：X=转向、Z=油门） */
    public static final PeripheralHelmBinding DEFAULT =
            new PeripheralHelmBinding("", 0, false, 180f, 0.05f, true, 0.35f, 2.0f, 20.0f, 0, 0);

    public boolean bound() {
        return deviceId != null && !deviceId.isEmpty();
    }

    /** 舵角缩放：外部设备归一转角 → 本方块的舵角（度） */
    public float angleOf(float steer) {
        return steer * maxAngleDeg;
    }

    /**
     * 船体加速度 → 力反馈归一化强度（0..1）：
     * <pre>
     *   a &lt;= ffbMinAccel              → 0（不反馈）
     *   a &gt;= ffbFullAccel             → ffbStrength（满力度）
     *   中间                          → 线性插值 × ffbStrength
     * </pre>
     */
    public float forceStrengthFor(float accelMps2) {
        if (!forceFeedback || ffbStrength <= 0f) {
            return 0f;
        }
        float min = Math.max(0f, ffbMinAccel);
        float full = Math.max(min + 0.01f, ffbFullAccel);
        float excess = accelMps2 - min;
        if (excess <= 0f) {
            return 0f;
        }
        // 轮胎力饱和曲线（更仿真）：真实轮胎的侧向力在接近附着极限时增长变缓，
        // 用 tanh 平滑饱和取代"线性到阈值 + 硬截断"（后者到点会突然满力，手感发假）。
        float norm = (float) Math.tanh(excess / (full - min));
        return norm * Math.min(1f, ffbStrength);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Device", deviceId == null ? "" : deviceId);
        tag.putInt("SteerAxis", steerAxis);
        tag.putBoolean("InvertSteer", invertSteer);
        tag.putFloat("MaxAngle", maxAngleDeg);
        tag.putFloat("Deadzone", deadzone);
        tag.putBoolean("ForceFeedback", forceFeedback);
        tag.putFloat("FfbStrength", ffbStrength);
        tag.putFloat("FfbMinAccel", ffbMinAccel);
        tag.putFloat("FfbFullAccel", ffbFullAccel);
        tag.putInt("ForceKind", forceKind);
        tag.putInt("FeelPreset", feelPreset);
        return tag;
    }

    public static PeripheralHelmBinding load(CompoundTag tag) {
        if (tag == null || !tag.contains("Device")) {
            return DEFAULT;
        }
        return new PeripheralHelmBinding(
                tag.getString("Device"),
                tag.getInt("SteerAxis"),
                tag.getBoolean("InvertSteer"),
                tag.contains("MaxAngle") ? tag.getFloat("MaxAngle") : DEFAULT.maxAngleDeg,
                tag.contains("Deadzone") ? tag.getFloat("Deadzone") : DEFAULT.deadzone,
                !tag.contains("ForceFeedback") || tag.getBoolean("ForceFeedback"),
                tag.contains("FfbStrength") ? tag.getFloat("FfbStrength") : DEFAULT.ffbStrength,
                tag.contains("FfbMinAccel") ? tag.getFloat("FfbMinAccel") : DEFAULT.ffbMinAccel,
                tag.contains("FfbFullAccel") ? tag.getFloat("FfbFullAccel") : DEFAULT.ffbFullAccel,
                tag.contains("ForceKind") ? tag.getInt("ForceKind") : 0,
                tag.contains("FeelPreset") ? tag.getInt("FeelPreset") : 0);
    }
}
