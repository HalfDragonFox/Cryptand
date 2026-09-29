package com.hdf.cryptand.mcp.resource;

import java.util.List;

/**
 * 资源提供者（<b>平台实现</b>）：告诉框架"我这儿有哪些资源、怎么读"。
 *
 * <p>典型实现：把宿主产物目录暴露成资源 —— 截图（image/png，走 blob）、
 * 报告（text/markdown，走 text）。{@link #list()} 每次调用都重新枚举即可，
 * 框架不缓存（截图是随时新增的）。</p>
 */
public interface McpResourceProvider {

    /** 提供者标识（诊断用，如 "ai-auto"） */
    String id();

    /** 当前可用资源（每次调用重新枚举；不要缓存） */
    List<McpResource> list();

    /**
     * 读取资源。
     *
     * @return 内容；不属于本提供者或不认识的 uri 返回 {@code null}（由框架继续问下一个提供者）
     * @throws Exception 读取失败（框架翻译成 JSON-RPC 错误）
     */
    McpResourceContent read(String uri) throws Exception;

    /** 资源模板（可选，如 {@code cryptand://ai-auto/{name}}） */
    default List<ResourceTemplate> templates() {
        return List.of();
    }

    /** 资源模板（URI 模板 + 说明） */
    record ResourceTemplate(String uriTemplate, String name, String title, String description,
                            String mimeType) {

        public com.google.gson.JsonObject toJson() {
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("uriTemplate", uriTemplate);
            o.addProperty("name", name);
            if (title != null && !title.isBlank()) {
                o.addProperty("title", title);
            }
            if (description != null && !description.isBlank()) {
                o.addProperty("description", description);
            }
            if (mimeType != null && !mimeType.isBlank()) {
                o.addProperty("mimeType", mimeType);
            }
            return o;
        }
    }
}
