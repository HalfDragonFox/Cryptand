/**
 * ===== PowerGrid wire/耦合对象 → 统一电气参数 =====
 *
 * PowerGrid 每个设备的电气参数都通过字段暴露为以下类型之一：
 *   - ElectricWire        → 电阻（getResistance）
 *   - SwitchedWire        → 电阻 + 开关状态（getState）
 *   - LRSeriesWire        → 电阻 + 电感（inductance 字段）
 *   - VoltageSourceCoupling → 电压 + 内阻（DC 电压源）
 *   - TransformerCoupling → 匝数比（getRatio）
 *
 * 设备模型类统一用它解析，避免每类重复反射。
 * 另提供反射读字段工具 {@link #field}。
 */

package com.hdf.cryptand.neoforge.powergrid.device;

import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

import java.lang.reflect.Field;

public final class DeviceWire {

    /** 电阻（Ω）；&lt;0 = 未解析到 wire */
    public double resistance = -1;
    /** 电感（H）；0 = 非电感 */
    public double inductance = 0;
    /** 开关闭合状态（SwitchedWire）；非开关恒 true */
    public boolean enabled = true;
    /** 是否 DC 电压源（VoltageSourceCoupling） */
    public boolean isVoltageSource = false;
    /** 电压源电压（V） */
    public double voltage = 0;
    /** 电压源内阻（Ω） */
    public double sourceResistance = 0;
    /** 变压器匝数比（TransformerCoupling，>0） */
    public double ratio = 0;

    public boolean hasResistance() { return resistance >= 0; }

    /** 解析任意 PowerGrid wire/耦合对象 */
    public static DeviceWire of(Object wire) {
        DeviceWire dw = new DeviceWire();
        if (wire == null) return dw;
        if (wire instanceof TransformerCoupling tc) {
            Object r = field(tc, "ratio");
            if (r instanceof Number n) dw.ratio = n.doubleValue();
            return dw;
        }
        if (wire instanceof VoltageSourceCoupling vs) {
            dw.isVoltageSource = vs.isSource();
            dw.voltage = vs.getVoltage();
            dw.sourceResistance = vs.getResistance();
            return dw;
        }
        if (wire instanceof SwitchedWire sw) {
            dw.enabled = sw.getState();
            dw.resistance = sw.getResistance();
        } else if (wire instanceof ElectricWire ew) {
            dw.resistance = ew.getResistance();
        }
        if (wire instanceof LRSeriesWire lr) {
            Object lo = field(lr, "inductance");
            if (lo instanceof Number n) dw.inductance = n.doubleValue();
        }
        return dw;
    }

    /** 反射读取字段（含父类），失败返回 null */
    public static Object field(Object target, String name) {
        if (target == null) return null;
        Field f = null;
        Class<?> c = target.getClass();
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射读 ElectricWire 字段的电阻（Ω）；<=0 = 未解析到有效电阻。
     *  <p>关键：MNA 里的元件电阻必须与 PowerGrid 实际内部 wire 完全一致。
     *  电流表内部 wire（字段 "wire"）= 0.05Ω 低阻；电压表（"connection"）=
     *  量程电阻（2kV 档 20MΩ）；功率表 "series"=0.05Ω / "shunt"=20MΩ。
     *  若用兜底值（如 1e4）建模，求解电路与实际不一致 → 写回电压在低阻内部
     *  wire 上产生虚假巨大电流 → 测量方块过热爆炸。 */
    public static double wireResistance(Object target, String name) {
        Object w = field(target, name);
        if (w instanceof ElectricWire ew) {
            double r = ew.getResistance();
            if (Double.isFinite(r) && r > 0) return r;
        }
        return -1;
    }

    /** 反射调用无参方法，失败返回 null */
    public static Object call(Object target, String name) {
        if (target == null) return null;
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private DeviceWire() {}
}
