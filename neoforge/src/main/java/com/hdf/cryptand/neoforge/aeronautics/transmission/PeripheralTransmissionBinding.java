/**
 * ===== 外设模拟传动器 · 绑定（2026-09-14）=====
 *
 * 复制原版 `simulated:analog_transmission`（模拟传动器）的语义：
 * 原版用红石信号 0..15 驱动传动比（`modifier = 1 - (signal+1)/16`，signal=0 时等同齿轮箱 1:1，
 * signal=15 时齿轮与传动杆完全分离）；外设版把"红石信号"换成<b>外部设备的一个轴</b>，
 * 中间插入用户可编辑的曲线：
 *
 * <pre>
 *   轴原始值 raw
 *     → t = clamp((raw - inMin) / (inMax - inMin), 0, 1)   ← 界面两个"输入最大/最小值"文本框
 *     → y = curve.apply(t)                                  ← 可添加节点的曲线（PCHIP），0..1
 *     → modifier = y                                        ← 输出转速比例 0%..100%
 * </pre>
 *
 * 曲线核心在 common 的算法库里（{@link com.hdf.cryptand.algorithm.curve.EditableCurve} /
 * {@link com.hdf.cryptand.algorithm.curve.CurveMap}），纯 Java 零 MC 依赖，别的子包也能直接用。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import com.hdf.cryptand.algorithm.curve.CurveMap;
import com.hdf.cryptand.algorithm.curve.CurvePoint;
import com.hdf.cryptand.algorithm.curve.EditableCurve;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public record PeripheralTransmissionBinding(
        /** 稳定设备 id（"" = 未绑定）；复用外设设备池，按名称重连 */
        String deviceId,
        /** 轴索引 0..7（顺序 = GameInputState 的 8 轴标准顺序） */
        int axisIndex,
        /** 轴反向（部分踏板/摇杆"未推"读数是 +1 或 -1，与直觉相反） */
        boolean invertAxis,
        /** 输入下限（界面文本框） */
        double inMin,
        /** 输入上限（界面文本框） */
        double inMax,
        /** 可编辑曲线（默认两点线性 (0,0)→(1,1)） */
        EditableCurve curve,
        /** 强制设备类型（0 = 自动；见 PeripheralForceKinds） */
        int forceKind) {

    /** 默认：未绑定；X 轴；输入 0..1；直通曲线 */
    public static final PeripheralTransmissionBinding DEFAULT =
            new PeripheralTransmissionBinding("", 0, false, 0.0, 1.0, EditableCurve.DEFAULT, 0);

    public PeripheralTransmissionBinding {
        if (curve == null) {
            curve = EditableCurve.DEFAULT;
        }
        axisIndex = Mth.clamp(axisIndex, 0, 7);
        if (!Double.isFinite(inMin)) {
            inMin = 0.0;
        }
        if (!Double.isFinite(inMax)) {
            inMax = 1.0;
        }
    }

    public boolean bound() {
        return deviceId != null && !deviceId.isEmpty();
    }

    /** 求值链用的映射（输入范围 + 反向 + 曲线） */
    public CurveMap map() {
        return new CurveMap(inMin, inMax, invertAxis, curve);
    }

    /** 轴原始值 → 输出转速比例 0..1（0%..100%） */
    public double ratioOf(double rawAxis) {
        return map().apply(rawAxis);
    }

    /** 输出百分比 0..100（界面实时显示用） */
    public double percentOf(double rawAxis) {
        return ratioOf(rawAxis) * 100.0;
    }

    // ==================== 编辑（返回新实例，保持 record 不可变） ====================

    public PeripheralTransmissionBinding withDevice(String id) {
        return new PeripheralTransmissionBinding(id == null ? "" : id, axisIndex, invertAxis,
                inMin, inMax, curve, forceKind);
    }

    public PeripheralTransmissionBinding withAxis(int index, boolean invert) {
        return new PeripheralTransmissionBinding(deviceId, index, invert,
                inMin, inMax, curve, forceKind);
    }

    public PeripheralTransmissionBinding withRange(double min, double max) {
        return new PeripheralTransmissionBinding(deviceId, axisIndex, invertAxis,
                min, max, curve, forceKind);
    }

    public PeripheralTransmissionBinding withCurve(EditableCurve value) {
        return new PeripheralTransmissionBinding(deviceId, axisIndex, invertAxis,
                inMin, inMax, value, forceKind);
    }

    public PeripheralTransmissionBinding withForceKind(int value) {
        return new PeripheralTransmissionBinding(deviceId, axisIndex, invertAxis,
                inMin, inMax, curve, value);
    }

    // ==================== 持久化 ====================
    // 只存【固定化的参数】：设备、轴、输入范围、曲线、强制类型。
    // 运行期状态（当前比例、超速标志）不落盘 —— 与拉杆/栏杆"档位是临时值"的规则一致。

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Device", deviceId == null ? "" : deviceId);
        tag.putInt("Axis", axisIndex);
        tag.putBoolean("Invert", invertAxis);
        tag.putDouble("InMin", inMin);
        tag.putDouble("InMax", inMax);
        tag.putInt("ForceKind", forceKind);
        // 曲线点集（⚠ CompoundTag 在 1.21.1 没有 DoubleArray，用 ListTag + putDouble）
        ListTag list = new ListTag();
        for (CurvePoint p : curve.points()) {
            CompoundTag e = new CompoundTag();
            e.putDouble("X", p.x());
            e.putDouble("Y", p.y());
            list.add(e);
        }
        tag.put("Curve", list);
        return tag;
    }

    public static PeripheralTransmissionBinding load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return DEFAULT;
        }
        return new PeripheralTransmissionBinding(
                tag.getString("Device"),
                tag.contains("Axis") ? tag.getInt("Axis") : 0,
                tag.getBoolean("Invert"),
                tag.contains("InMin") ? tag.getDouble("InMin") : 0.0,
                tag.contains("InMax") ? tag.getDouble("InMax") : 1.0,
                loadCurve(tag),
                tag.contains("ForceKind") ? tag.getInt("ForceKind") : 0);
    }

    /** 读曲线点集（缺失/为空 ⇒ 默认两点线性） */
    private static EditableCurve loadCurve(CompoundTag tag) {
        if (!tag.contains("Curve", Tag.TAG_LIST)) {
            return EditableCurve.DEFAULT;
        }
        ListTag list = tag.getList("Curve", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return EditableCurve.DEFAULT;
        }
        List<CurvePoint> pts = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            pts.add(new CurvePoint(e.getDouble("X"), e.getDouble("Y")));
        }
        return new EditableCurve(pts);
    }
}
