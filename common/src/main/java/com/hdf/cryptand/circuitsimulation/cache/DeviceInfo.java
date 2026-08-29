package com.hdf.cryptand.circuitsimulation.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 虚拟电路设备登记（2026-08-22 用户需求：导出原理图作为核心功能扩展）。
 * <p>
 * 设备级信息（电路模拟器前端类型 + 显示名 + 参数）作为【虚拟电路数据】的
 * 一部分，由平台在【建网/参数同步】阶段写入（主线程缓存通道），核心导出器
 * 只读本数据生成原理图——【不检测/不读取任何实际 BlockEntity / Level】。
 * <p>
 * 一个 DeviceInfo 对应【一个设备方块】{@link #blockKey}（WirePoint key 的
 * 方块前缀，不含端子索引）。同一方块的全部端子分享同一 DeviceInfo；
 * 端子坐标/连接关系由虚拟电路拓扑（WirePoint/WireEdge）本身提供。
 *
 * @param blockKey 方块 key（如 "BBlockPos{x=1, y=2, z=3}" 或 "B(1,2,3)"，无 #端子）
 * @param type     电路模拟器前端元件类型 id（如 "resistor" / "fan" / "acsourc"）
 * @param name     显示名（可 null）
 * @param params   前端参数（键见前端元件定义，如 resistance/voltage/amplitude…）
 */
public record DeviceInfo(String blockKey, String type, String name,
                         Map<String, Double> params) {

    public DeviceInfo {
        if (blockKey == null || blockKey.isEmpty()) {
            throw new IllegalArgumentException("DeviceInfo blockKey must not be empty");
        }
        params = params == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** 便捷构造（空参数） */
    public static DeviceInfo of(String blockKey, String type, String name) {
        return new DeviceInfo(blockKey, type, name, Collections.emptyMap());
    }

    /** 便捷构造（默认名 = 类型） */
    public static DeviceInfo of(String blockKey, String type, Map<String, Double> params) {
        return new DeviceInfo(blockKey, type, type, params);
    }

    /** 便捷构造（完整参数） */
    public static DeviceInfo of(String blockKey, String type, String name,
                                Map<String, Double> params) {
        return new DeviceInfo(blockKey, type, name, params);
    }

    @Override
    public String toString() {
        return "DeviceInfo{block=" + blockKey + ", type=" + type + ", params=" + params + "}";
    }
}