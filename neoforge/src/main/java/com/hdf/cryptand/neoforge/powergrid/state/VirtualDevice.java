package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.circuitsimulation.model.ElementBinding;
import com.hdf.cryptand.neoforge.powergrid.block.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 虚拟设备参数快照（2026-08-12 超长线路输电：未加载区块元件的持久参数）。
 * <p>
 * 超长线路跨多个区块时，远端区块未加载 → 其方块实体（BE）不存在（getBlockEntity
 * 返回 null）→ Cryptand 无法建模该设备 → 网络缺元件 → 输电中断。但【已加载
 * 导线】仍持有指向远端设备端子的网络节点 → 该设备位置在 blockTerminals 中。
 * <p>
 * 方案：设备 BE 每次成功建模时，把【电气参数快照】保存到这里（按 BlockPos）；
 * 构建时若该位置 BE 为 null（未加载区块）→ 用快照创建"虚拟复合元件"（R-L
 * 绕组/开关/源），使未加载区块的设备仍纳入网络、超长线路输电不中断。
 * 参数为最近一次加载时的值（未加载期间不刷新；区块加载后正常建模覆盖）。
 * <p>
 * 同时实现 {@link ElementBinding}（2026-08-12）：虚拟元件构造时绑定本快照 →
 * 计算时全局可调整参数（外部 {@link #setResistance} 等 → 置变动标记 → 绑定
 * 元件下一轮自动应用新参数 → 重解），无论实际 MC 模型/BE 是否更新。
 */
public final class VirtualDevice implements ElementBinding {

    /** 设备方块类简单名（虚拟建模时识别类型） */
    public final String deviceClass;
    /** 主线电阻（Ω）、电感（H）——可调（全局调整时直接改 + 置变动标记） */
    public volatile double resistance, inductance;
    /** 开关/灯闭合状态——可调 */
    public volatile boolean enabled;
    /** 源电压（V）、内阻（Ω）、是否电压源 */
    public final double voltage, sourceResistance;
    public final boolean isVoltageSource;

    /** 参数变动标记（绑定元件下一轮 {@code applyBinding} 应用后清除） */
    private volatile boolean dirty;

    public VirtualDevice(String deviceClass, double resistance, double inductance,
                         boolean enabled, double voltage, double sourceResistance,
                         boolean isVoltageSource) {
        this.deviceClass = deviceClass;
        this.resistance = resistance;
        this.inductance = inductance;
        this.enabled = enabled;
        this.voltage = voltage;
        this.sourceResistance = sourceResistance;
        this.isVoltageSource = isVoltageSource;
    }

    // ===== 全局参数调整（2026-08-12：计算时全局可调，不依赖 MC 模型更新） =====

    /** 全局调整主线电阻 → 置变动标记 → 绑定元件下一轮应用（重解） */
    public void setResistance(double r) {
        if (r > 0 && r != resistance) { resistance = r; dirty = true; }
    }

    /** 全局调整电感 → 置变动标记 */
    public void setInductance(double l) {
        if (l >= 0 && l != inductance) { inductance = l; dirty = true; }
    }

    /** 全局调整开关/使能状态 → 置变动标记 */
    public void setEnabled(boolean e) {
        if (e != enabled) { enabled = e; dirty = true; }
    }

    @Override public double boundResistance() { return resistance; }
    @Override public double boundInductance() { return inductance; }
    @Override public boolean boundEnabled() { return enabled; }
    @Override public double boundVoltage() { return voltage; }
    @Override public double boundSourceResistance() { return sourceResistance; }
    @Override public boolean boundIsVoltageSource() { return isVoltageSource; }
    @Override public boolean bindingDirty() { return dirty; }
    @Override public void bindingClearDirty() { dirty = false; }

    /** 从方块解析电气参数快照（反射读线圈/导线/源耦合字段） */
    public static VirtualDevice of(BlockEntity be) {
        if (be == null) return null;
        String cls = be.getClass().getSimpleName();
        // 有源设备/变压器：必须区块加载才能正确工作（虚拟无法反映真实负载/转速/
        // 激励 → 会产生无限功率）。未加载区块时不保存快照 → 构建时直接不输出
        // （临时跳过建模，非真断开），保证发电机这类必须加载区块才能供电。
        // 2026-08-13 用户架构：优先用设备模型注册表的 isSource() 接口化判断
        // （源设备覆写返回 true）；直接处理类（发电机/换向器/变压器/创意源等
        // 不在注册表）由类名匹配双保险兜底。
        if (Assemblers.isSource(be)
                || isPowerSourceClass(cls)) return null;
        try {
            // 主线电阻：coilWire/coil/wire/filament/switch 等（按字段顺序）
            Object w = DeviceWire.field(be, "coilWire");
            if (w == null) w = DeviceWire.field(be, "coil");
            if (w == null) w = DeviceWire.field(be, "wire");
            if (w == null) w = DeviceWire.field(be, "filament");
            if (w == null) w = DeviceWire.field(be, "fuseWire");
            if (w == null) w = DeviceWire.field(be, "switch1");
            DeviceWire dw = DeviceWire.of(w);
            double r = dw.hasResistance() && dw.resistance > 0 ? dw.resistance : 0;
            double l = dw.inductance > 0 ? dw.inductance : 0;
            boolean en = dw.enabled;
            // 电压源：sourceCoupling（电池等）
            Object sc = DeviceWire.field(be, "sourceCoupling");
            DeviceWire sdc = DeviceWire.of(sc);
            boolean vs = sdc.isVoltageSource;
            double v = vs ? Math.abs(sdc.voltage) : 0;
            double sr = vs && sdc.sourceResistance > 0 ? sdc.sourceResistance : 0;
            return new VirtualDevice(cls, r, l, en, v, sr, vs);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 功率源/变压器识别（2026-08-12）：这些设备虚拟建模会产生无限功率或错误
     * 拓扑 → 必须区块加载。返回 true 时 {@link #of} 不保存快照（不虚拟建模）。
     * <p>
     * 覆盖：Generator（发电机）、Battery（电池）、AC/DC/电流源（创意源）、
     * Solar（太阳能）、Transformer（变压器 4 端口互感，虚拟两端口建模错误）。
     * ⚠ 2026-08-30 审计 C12 根因：contains 子串 →【精确简单名白名单】——原
     * contains("battery"/"generator"/"voltage"/"creative"/"source" 等) 会误伤
     * 第三方 mod 负载设备（类名含这些子串）→ 区块未加载时不保存快照 → 不建模
     * → 超长线路输电中断。Assemblers.isSource() 已精确覆盖注册表设备（首选），
     * 本方法只兜底【官方直接处理类】的精确简单名。
     */
    private static final java.util.Set<String> SOURCE_SIMPLE = java.util.Set.of(
            "GeneratorBlockEntity", "CommutatorBlockEntity", "TransformerBlockEntity",
            "AcCreativeSourceBlockEntity", "CreativeSourceBlockEntity",
            "BatteryBlockEntity", "PotatoBatteryBlockEntity", "PortableBatteryBlockEntity",
            "SolarPanelBlockEntity", "DynamoBlockEntity", "AlternatorBlockEntity");

    private static boolean isPowerSourceClass(String cls) {
        if (cls == null) return false;
        return SOURCE_SIMPLE.contains(cls);
    }

    @Override
    public String toString() {
        return "VirtualDevice{" + deviceClass + " R=" + resistance + " L=" + inductance
                + " en=" + enabled + (isVoltageSource ? " V=" + voltage : "") + "}";
    }
}
