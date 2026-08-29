package com.hdf.cryptand.circuitsimulation.export;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原理图导出请求（2026-08-22 用户需求：通过发送导出请求（带网络）直接导出
 * 虚拟电路，而不是检测实际 BE 模型）。
 * <p>
 * 请求【携带目标网络】（worldName + 可选 networkKey）→ 核心导出器直接读取
 * 该网络世界实例（NetworkWorld）里的【虚拟电路数据】（WirePoint/WireEdge/
 * DeviceInfo）生成原理图——全程零 MC 依赖，不检测 Level/BlockEntity。
 * <ul>
 *   <li>worldName：目标实例名（路由到 NetworkWorldManager）；</li>
 *   <li>networkKey：可空。null → 导出该世界全部网络；指定 → 仅导出该网络；</li>
 *   <li>format：导出格式（默认 {@link SchematicFormat#CUSTOM_EDA}）；</li>
 *   <li>options：扩展选项（如文件名建议）。</li>
 * </ul>
 */
public record SchematicExportRequest(String worldName, String networkKey,
                                     SchematicFormat format, Map<String, Object> options) {

    /** 便捷构造（默认 CUSTOM_EDA） */
    public static SchematicExportRequest of(String worldName, String networkKey) {
        return new SchematicExportRequest(worldName, networkKey,
                SchematicFormat.CUSTOM_EDA, Collections.emptyMap());
    }

    public SchematicExportRequest {
        if (worldName == null) worldName = "";
        if (format == null) format = SchematicFormat.CUSTOM_EDA;
        if (options == null) options = Collections.emptyMap();
    }

    /** 复制请求并指定格式（链式） */
    public SchematicExportRequest withFormat(SchematicFormat f) {
        return new SchematicExportRequest(worldName, networkKey, f, options);
    }

    /** 复制请求并指定网络（链式） */
    public SchematicExportRequest withNetworkKey(String key) {
        return new SchematicExportRequest(worldName, key, format, options);
    }

    /** 扩展选项读取（字符串） */
    public String option(String k, String dft) {
        Object v = options.get(k);
        return v == null ? dft : String.valueOf(v);
    }
}