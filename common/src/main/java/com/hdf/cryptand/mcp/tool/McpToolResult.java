package com.hdf.cryptand.mcp.tool;

import com.google.gson.JsonObject;

/**
 * 一次工具调用返回（MCP {@code tools/call} 的 result）。
 *
 * <p>三种内容形态可组合：</p>
 * <ul>
 *   <li>{@link #text} —— 纯文本（最常见：模型直接读）；</li>
 *   <li>{@link #structured} —— 结构化 JSON（同时给 {@code structuredContent} 与文本回退，
 *       老客户端也能看到内容）；</li>
 *   <li>{@link #image} —— base64 图片（截图给多模态模型看，真正的"AI 看画面"通道）。</li>
 * </ul>
 *
 * <p>{@link #error} 表示<b>工具执行失败</b>（{@code isError:true}）—— 注意与"协议错误"
 * 不同：协议错误走 JSON-RPC error 对象。</p>
 */
public final class McpToolResult {

    private final com.google.gson.JsonArray content = new com.google.gson.JsonArray();
    private JsonObject structured;
    private boolean error;
    private JsonObject meta;

    private McpToolResult() {
    }

    public static McpToolResult create() {
        return new McpToolResult();
    }

    public static McpToolResult text(String text) {
        return create().addText(text);
    }

    /** 结构化结果：structuredContent + 文本回退（缩进 JSON） */
    public static McpToolResult structured(JsonObject data) {
        final McpToolResult r = create();
        r.structured = data;
        r.addText(data == null ? "{}" : data.toString());
        return r;
    }

    public static McpToolResult error(String message) {
        final McpToolResult r = create();
        r.error = true;
        r.addText(message == null ? "工具执行失败" : message);
        return r;
    }

    /** 图片（PNG 等）：data 为 base64 */
    public static McpToolResult image(byte[] bytes, String mimeType) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", com.hdf.cryptand.mcp.Mcp.C_IMAGE);
        o.addProperty("data", java.util.Base64.getEncoder().encodeToString(bytes));
        o.addProperty("mimeType", mimeType == null ? "image/png" : mimeType);
        return create().add(o);
    }

    // ==================== 组合 ====================

    public McpToolResult addText(String text) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", com.hdf.cryptand.mcp.Mcp.C_TEXT);
        o.addProperty("text", text == null ? "" : text);
        return add(o);
    }

    public McpToolResult add(JsonObject contentItem) {
        content.add(contentItem);
        return this;
    }

    public McpToolResult structuredContent(JsonObject data) {
        this.structured = data;
        return this;
    }

    public McpToolResult meta(JsonObject meta) {
        this.meta = meta;
        return this;
    }

    public McpToolResult asError() {
        this.error = true;
        return this;
    }

    public boolean isError() {
        return error;
    }

    // ==================== 输出 ====================

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.add("content", content);
        if (structured != null) {
            o.add("structuredContent", structured);
        }
        if (error) {
            o.addProperty("isError", true);
        }
        if (meta != null) {
            o.add("_meta", meta);
        }
        return o;
    }

    public com.google.gson.JsonArray content() {
        return content;
    }
}
