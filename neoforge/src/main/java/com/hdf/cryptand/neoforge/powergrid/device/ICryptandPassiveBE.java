/**
 * ===== 被动元件通用 BE 层（2026-09-13 用户：其他元件类似）=====
 *
 * 与电机同构的分层：
 *   ICryptandCircuitBe（BE 基类：消息/爆炸两级）
 *     └ ICryptandPassiveBE（通用被动元件：R/L/C 参数 + 损耗）
 *         ├ Resistor / Capacitor / Inductor（具体家族，继承原版 BE + implements 本接口）
 *
 * 具体设备直接 cryptandResistance() / cryptandLossPower() 使用，不必 import 工具类。
 */
package com.hdf.cryptand.neoforge.powergrid.device;

public interface ICryptandPassiveBE extends ICryptandCircuitBe {

    /** 等效电阻（Ω）：默认反射读原版 resistance()（具体设备可覆写） */
    default double cryptandResistance() {
        return cryptandCallNumber("resistance");
    }

    /** 电感（H）：默认反射读 inductance 字段/方法，取不到 0 */
    default double cryptandInductance() {
        double v = cryptandCallNumber("inductance");
        if (v > 0) return v;
        Object f = cryptandField("inductance");
        return (f instanceof Number n) ? n.doubleValue() : 0;
    }

    /** 电容（F）：默认反射读 capacitance 字段/方法，取不到 0 */
    default double cryptandCapacitance() {
        double v = cryptandCallNumber("capacitance");
        if (v > 0) return v;
        Object f = cryptandField("capacitance");
        return (f instanceof Number n) ? n.doubleValue() : 0;
    }

    /** 元件损耗（平均功率 W）：默认按自管设备电流算 I²R（纯电阻口径） */
    default double cryptandLossPower() {
        double i = cryptandDeviceCurrent();
        double r = cryptandResistance();
        return (Double.isFinite(i) && r > 0) ? i * i * r : 0;
    }

    /** 电阻家族 */
    interface Resistor extends ICryptandPassiveBE {
    }

    /** 电容家族 */
    interface Capacitor extends ICryptandPassiveBE {
    }

    /** 电感家族 */
    interface Inductor extends ICryptandPassiveBE {
    }
}
