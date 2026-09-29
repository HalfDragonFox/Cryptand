/**
 * ===== 设备参数 → 虚拟电路设备登记 映射（2026-08-22 导出原理图核心扩展） =====
 *
 * 适配层职责：把主线程预同步的设备参数缓存（DeviceParamCache.Entry，不检测
 * BE —— sync 已把方块当前值同步为只读缓存）映射为【核心虚拟电路设备登记】
 * （engine.cache.DeviceInfo：前端元件类型 + 参数）。
 *
 * 这样核心导出器（CustomEdaExporter）直接读虚拟电路数据即可生成原理图：
 *   - 拓扑（WirePoint/WireEdge） → 端子坐标/导线连接
 *   - 设备登记（DeviceInfo）     → 前端元件类型 + 参数
 * 全程不读取 Level/BlockEntity。
 *
 * 前端类型 id（对应 test/js/components.js 的 COMPONENT_DEFS 键）。
 */

package com.hdf.cryptand.neoforge.powergrid.element;

import com.hdf.cryptand.circuitsimulation.cache.DeviceInfo;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DeviceParamMapper {

    private DeviceParamMapper() {
    }

    /** 无参数 / 无法识别 → null（不登记；导出回退通用端子 connector） */
    public static DeviceInfo toDeviceInfo(String blockKey, DeviceParamCache.Entry e) {
        if (blockKey == null || e == null) return null;
        String type;
        Map<String, Double> p = new LinkedHashMap<>();
        switch (e.kind) {
            case RESISTOR -> {
                type = "resistor";
                p.put("resistance", e.resistance);
            }
            case CAPACITOR -> {
                type = "capacitor";
                p.put("capacitance", e.capacitance);
            }
            case INDUCTOR, WINDING -> {
                type = "inductor";
                p.put("inductance", e.inductance);
            }
            case AC_VOLTAGE_SRC -> {
                type = "acsourc";
                p.put("amplitude", e.amplitude);
                p.put("frequency", e.frequencyHz);
            }
            case AC_CURRENT_SRC -> {
                type = "isource";
                p.put("amplitude", e.amplitude);
                p.put("frequency", e.frequencyHz);
            }
            case TRANSFORMER -> type = "transformer";
            case POWER_GAUGE -> type = "gauge";
            case PROGRAMMABLE -> type = "connector";
            default -> {
                type = otherType(e.deviceClass);
                if (type == null) return null;
            }
        }
        return DeviceInfo.of(blockKey, type, type, p);
    }

    /** 组装器设备按 deviceClass 类名片段 → 前端类型（best-effort；未识别 null） */
    private static String otherType(String deviceClass) {
        if (deviceClass == null) return null;
        String lower = deviceClass.toLowerCase();
        if (lower.contains("fan")) return "fan";
        if (lower.contains("heater") || lower.contains("burner")) return "heater";
        if (lower.contains("brushless")) return "bldc_motor";
        if (lower.contains("induction")) return "induction_motor";
        if (lower.contains("synchronous")) return "synchronous_motor";
        if (lower.contains("electromachine")) return "electromachine";
        if (lower.contains("dcdc")) return "dcdc";
        if (lower.contains("inverter")) return "inverter";
        if (lower.contains("rectifier")) return "rectifier";
        if (lower.contains("battery")) return "battery";
        if (lower.contains("generator")) return "generator";
        if (lower.contains("motor")) return "motor";
        if (lower.contains("transformer") || lower.contains("coil")) return "transformer";
        if (lower.contains("antenna")) return "antenna";
        if (lower.contains("speaker")) return "speaker";
        if (lower.contains("solar")) return "solar_panel";
        if (lower.contains("oscillator")) return "oscillator";
        if (lower.contains("vfd")) return "vfd";
        return null;
    }
}