package com.hdf.cryptand.circuitsimulation.export;

/**
 * 原理图导出格式（2026-08-22 用户需求：导出原理图作为核心功能的扩展功能，
 * 可选择导出格式——【默认自定义 EDA(test 前端电路图 JSON)】，目前仅支持它，
 * 其他格式作为预留选项存在，选择时返回"暂不支持"提示）。
 * <p>
 * 选择通过导出请求的 format 字段（id）完成；未提供/未知 → 默认 CUSTOM_EDA。
 */
public enum SchematicFormat {

    /** 自定义 EDA：导出为 test/ 前端可读的电路图 JSON（默认、当前唯一支持） */
    CUSTOM_EDA("custom-eda", "自定义EDA(test前端)", true),

    /** 预留：Markdown + Mermaid 原理图（暂不支持） */
    MARKDOWN("markdown", "Markdown+Mermaid原理图", false),

    /** 预留：SPICE 网表（暂不支持） */
    SPICE_NETLIST("spice", "SPICE网表", false);

    private final String id;
    private final String displayName;
    private final boolean supported;

    SchematicFormat(String id, String displayName, boolean supported) {
        this.id = id;
        this.displayName = displayName;
        this.supported = supported;
    }

    /** 稳定 id（跨通信/配置文件使用） */
    public String id() { return id; }

    /** 中文显示名（诊断/日志） */
    public String displayName() { return displayName; }

    /** 当前是否已支持（false = 预留，选择返回暂不支持提示） */
    public boolean isSupported() { return supported; }

    /** id → 格式；null/空/未知 → 默认 CUSTOM_EDA */
    public static SchematicFormat fromId(String id) {
        if (id != null) {
            for (SchematicFormat f : values()) {
                if (f.id.equalsIgnoreCase(id.trim())) return f;
            }
        }
        return CUSTOM_EDA;
    }
}