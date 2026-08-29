package com.hdf.cryptand.circuitsimulation.export;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原理图导出结果（2026-08-22 核心导出扩展：统一结果，可序列化为通信响应的
 * Map —— 全部字段为基础类型/String，经 ProtoCommCodec 传输安全）。
 */
public final class SchematicExportResult {

    public final boolean ok;
    public final SchematicFormat format;
    /** 导出内容（JSON 文本 / 原理图文本；失败 null） */
    public final String content;
    /** 建议文件名（失败 null） */
    public final String fileName;
    /** 导出元件数（失败 0） */
    public final int componentCount;
    /** 导出网络数（失败 0） */
    public final int networkCount;
    /** 失败原因（成功 null） */
    public final String error;

    private SchematicExportResult(boolean ok, SchematicFormat format, String content,
                                  String fileName, int componentCount, int networkCount,
                                  String error) {
        this.ok = ok;
        this.format = format;
        this.content = content;
        this.fileName = fileName;
        this.componentCount = componentCount;
        this.networkCount = networkCount;
        this.error = error;
    }

    public static SchematicExportResult ok(SchematicFormat format, String content,
                                           String fileName, int componentCount,
                                           int networkCount) {
        return new SchematicExportResult(true, format, content, fileName,
                componentCount, networkCount, null);
    }

    public static SchematicExportResult fail(SchematicFormat format, String error) {
        return new SchematicExportResult(false, format, null, null, 0, 0, error);
    }

    /** 序列化为通信响应 Map（基础类型，传输安全） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("format", format == null ? "?" : format.id());
        if (content != null) m.put("content", content);
        if (fileName != null) m.put("fileName", fileName);
        m.put("components", componentCount);
        m.put("networks", networkCount);
        if (error != null) m.put("error", error);
        return m;
    }

    @Override
    public String toString() {
        return "SchematicExportResult{ok=" + ok + ", format="
                + (format == null ? "?" : format.id())
                + ", components=" + componentCount + ", networks=" + networkCount
                + (error != null ? ", err=" + error : "") + "}";
    }
}