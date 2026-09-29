/**
 * ===== 外设模拟传动器（按键）· 绑定（2026-09-16）=====
 *
 * 在「轴驱动曲线」（{@code transmission} 那套）基础上增加**按键驱动**：
 *
 * <pre>
 *   ① 轴通道（保留）：raw → t = clamp((raw-inMin)/(inMax-inMin)) → curve.apply(t)
 *   ② 按键通道（新增）：每个曲线点可绑定【按键】与【时间 ms】，
 *      按键按下 = "激活该点" → 滑块以 (Δx ÷ 沿途时间之和) 的速度朝该点推进，
 *      输出 = curve.apply(sliderX)（见 BE 的推进状态机）
 * </pre>
 *
 * 时间语义（用户 2026-09-16 定稿）：点的 {@code timeMs} 表示**从相邻点走到该点**所需时间；
 * 跨多个点时**沿途时间相加**（例：点1=0/3s、点2=50/5s、点3=100/3s，从点1 直接激活点3 ⇒ 5+3=8s）。
 * 中途另一个点被激活 ⇒ 立即按新目标重新计算推进速度（可正可反）。
 *
 * 曲线核心在 common 算法库（{@link com.hdf.cryptand.algorithm.curve.EditableCurve} 等），纯 Java 零 MC 依赖。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

import com.hdf.cryptand.algorithm.curve.CurveMap;
import com.hdf.cryptand.algorithm.curve.CurvePoint;
import com.hdf.cryptand.algorithm.curve.EditableCurve;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public record PeripheralTransmissionKeyBinding(
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
        int forceKind,
        /** 🆕 每个曲线点的按键/时间设置（与 {@link #curve} 的点一一对应；短于曲线 = 未设置） */
        List<PointKey> pointKeys) {

    /**
     * 一个曲线点的按键驱动设置。
     *
     * @param keyMask 绑定的按键位掩码（0 = 未绑定；可多键绑定同一点）——
     *                用 {@code long} 与 {@link com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput.Sample#buttons()} 对齐，
     *                避免高位键被 int 截断
     * @param timeMs  到达该点所需时间（毫秒；&le;0 表示用 {@link #DEFAULT_TIME_MS}）
     */
    public record PointKey(long keyMask, int timeMs) {

        public static final PointKey NONE = new PointKey(0L, 0);

        public boolean hasKey() {
            return keyMask != 0L;
        }

        /** 生效时间（ms）：未设置时用默认值，保证滑块总有速度 */
        public int effectiveTimeMs() {
            return timeMs > 0 ? timeMs : DEFAULT_TIME_MS;
        }
    }

    /** 默认推进时间（ms）：绑定按键但没写时间时使用 */
    public static final int DEFAULT_TIME_MS = 1000;

    /** 默认：未绑定；X 轴；输入 0..1；直通曲线；无按键 */
    public static final PeripheralTransmissionKeyBinding DEFAULT =
            new PeripheralTransmissionKeyBinding("", 0, false, 0.0, 1.0, EditableCurve.DEFAULT, 0, List.of());

    public PeripheralTransmissionKeyBinding {
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
        if (pointKeys == null) {
            pointKeys = List.of();
        } else {
            // 去掉 null 项，保持不可变
            final List<PointKey> cleaned = new ArrayList<>(pointKeys.size());
            for (final PointKey k : pointKeys) {
                cleaned.add(k == null ? PointKey.NONE : k);
            }
            pointKeys = List.copyOf(cleaned);
        }
    }

    public boolean bound() {
        return deviceId != null && !deviceId.isEmpty();
    }

    /** 是否有任何点绑定了按键 */
    public boolean hasAnyKey() {
        for (final PointKey k : pointKeys) {
            if (k.hasKey()) {
                return true;
            }
        }
        return false;
    }

    /** 第 index 个点的按键设置（越界或未设置 ⇒ NONE） */
    public PointKey keyAt(int index) {
        return index >= 0 && index < pointKeys.size() ? pointKeys.get(index) : PointKey.NONE;
    }

    /** 曲线点数量 */
    public int pointCount() {
        return curve.points().size();
    }

    /** 求值链用的映射（输入范围 + 反向 + 曲线） */
    public CurveMap map() {
        return new CurveMap(inMin, inMax, invertAxis, curve);
    }

    /** 轴原始值 → 输出转速比例 0..1（0%..100%） */
    public double ratioOf(double rawAxis) {
        return map().apply(rawAxis);
    }

    /** 曲线参数 t → 输出比例 0..1（按键通道用：滑块位置直接喂曲线） */
    public double ratioAt(double t) {
        return curve.apply(Mth.clamp(t, 0.0, 1.0));
    }

    /** 输出百分比 0..100（界面实时显示用） */
    public double percentOf(double rawAxis) {
        return ratioOf(rawAxis) * 100.0;
    }

    // ==================== 编辑（返回新实例，保持 record 不可变） ====================

    public PeripheralTransmissionKeyBinding withDevice(String id) {
        return copy(id == null ? "" : id, axisIndex, invertAxis, inMin, inMax, curve, forceKind, pointKeys);
    }

    public PeripheralTransmissionKeyBinding withAxis(int index, boolean invert) {
        return copy(deviceId, index, invert, inMin, inMax, curve, forceKind, pointKeys);
    }

    public PeripheralTransmissionKeyBinding withRange(double min, double max) {
        return copy(deviceId, axisIndex, invertAxis, min, max, curve, forceKind, pointKeys);
    }

    public PeripheralTransmissionKeyBinding withCurve(EditableCurve value) {
        // 曲线点数变化时按键表跟着截断/补齐，保证索引对应关系不漂移
        final EditableCurve c = value == null ? EditableCurve.DEFAULT : value;
        final int n = c.points().size();
        final List<PointKey> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(i < pointKeys.size() ? pointKeys.get(i) : PointKey.NONE);
        }
        return copy(deviceId, axisIndex, invertAxis, inMin, inMax, c, forceKind, keys);
    }

    public PeripheralTransmissionKeyBinding withForceKind(int value) {
        return copy(deviceId, axisIndex, invertAxis, inMin, inMax, curve, value, pointKeys);
    }

    /** 🆕 设置某个点的按键与时间（索引超出则补齐中间项） */
    public PeripheralTransmissionKeyBinding withPointKey(int index, long keyMask, int timeMs) {
        if (index < 0) {
            return this;
        }
        final List<PointKey> keys = new ArrayList<>(pointKeys);
        while (keys.size() <= index) {
            keys.add(PointKey.NONE);
        }
        keys.set(index, new PointKey(keyMask, timeMs));
        return copy(deviceId, axisIndex, invertAxis, inMin, inMax, curve, forceKind, keys);
    }

    /** 🆕 只改某个点的时间，保留其按键 */
    public PeripheralTransmissionKeyBinding withPointTime(int index, int timeMs) {
        final PointKey old = keyAt(index);
        return withPointKey(index, old.keyMask(), timeMs);
    }

    /** 🆕 只改某个点的按键，保留其时间 */
    public PeripheralTransmissionKeyBinding withPointKeyMask(int index, long keyMask) {
        return withPointKey(index, keyMask, keyAt(index).timeMs());
    }

    private static PeripheralTransmissionKeyBinding copy(String deviceId, int axisIndex, boolean invertAxis,
                                                         double inMin, double inMax, EditableCurve curve,
                                                         int forceKind, List<PointKey> pointKeys) {
        return new PeripheralTransmissionKeyBinding(deviceId, axisIndex, invertAxis, inMin, inMax, curve, forceKind, pointKeys);
    }

    // ==================== 持久化 ====================
    // 只存【固定化的参数】：设备、轴、输入范围、曲线、强制类型、每点的按键/时间。
    // 运行期状态（滑块位置、目标点、推进速度、超速标志）**不落盘** —— 与拉杆/栏杆"档位是临时值"的规则一致。

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
        // 🆕 每点的按键与时间（掩码用 long，与 gameinput 对齐）
        ListTag keyList = new ListTag();
        for (final PointKey k : pointKeys) {
            CompoundTag e = new CompoundTag();
            e.putLong("Mask", k.keyMask());
            e.putInt("Ms", k.timeMs());
            keyList.add(e);
        }
        tag.put("PointKeys", keyList);
        return tag;
    }

    public static PeripheralTransmissionKeyBinding load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return DEFAULT;
        }
        return new PeripheralTransmissionKeyBinding(
                tag.getString("Device"),
                tag.contains("Axis") ? tag.getInt("Axis") : 0,
                tag.getBoolean("Invert"),
                tag.contains("InMin") ? tag.getDouble("InMin") : 0.0,
                tag.contains("InMax") ? tag.getDouble("InMax") : 1.0,
                loadCurve(tag),
                tag.contains("ForceKind") ? tag.getInt("ForceKind") : 0,
                loadPointKeys(tag));
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

    /** 🆕 读每点的按键/时间（缺失 ⇒ 空表） */
    private static List<PointKey> loadPointKeys(CompoundTag tag) {
        if (!tag.contains("PointKeys", Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = tag.getList("PointKeys", Tag.TAG_COMPOUND);
        final List<PointKey> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag e = list.getCompound(i);
            out.add(new PointKey(e.getLong("Mask"), e.getInt("Ms")));
        }
        return out;
    }
}
