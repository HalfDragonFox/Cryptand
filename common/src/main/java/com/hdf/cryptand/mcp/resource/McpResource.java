package com.hdf.cryptand.mcp.resource;

import com.google.gson.JsonObject;

/**
 * 一个 MCP 资源（客户端可列举、可读取的"文件"式内容）。
 *
 * @param uri         资源标识（如 {@code cryptand://ai-auto/report/ldlib-download}、{@code file:///…}）
 * @param name        程序名（必需）
 * @param title       人类可读标题
 * @param description 说明
 * @param mimeType    MIME 类型（如 {@code text/markdown}、{@code image/png}）
 * @param size        字节数（未知为 null）
 */
public record McpResource(String uri, String name, String title, String description,
                          String mimeType, Long size) {

    public static McpResource of(String uri, String name, String mimeType) {
        return new McpResource(uri, name, name, null, mimeType, null);
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("uri", uri);
        o.addProperty("name", name == null || name.isBlank() ? uri : name);
        if (title != null && !title.isBlank()) {
            o.addProperty("title", title);
        }
        if (description != null && !description.isBlank()) {
            o.addProperty("description", description);
        }
        if (mimeType != null && !mimeType.isBlank()) {
            o.addProperty("mimeType", mimeType);
        }
        if (size != null) {
            o.addProperty("size", size);
        }
        return o;
    }
}
