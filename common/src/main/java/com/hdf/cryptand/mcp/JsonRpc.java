package com.hdf.cryptand.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * JSON-RPC 2.0 编解码（MCP 的消息层）。
 *
 * <p>MCP 对 JSON-RPC 的要求：请求必须带 {@code jsonrpc:"2.0"} 与 {@code method}，
 * 需要响应时带 {@code id}；通知（notification）<b>不带 id</b>；响应必须带
 * {@code result} 或 {@code error}（二者互斥）。{@code id} 可以是字符串或数字。</p>
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    private JsonRpc() {
    }

    // ==================== 构造 ====================

    public static JsonObject request(String method, JsonElement id, JsonObject params) {
        final JsonObject o = base(method);
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        if (params != null) {
            o.add("params", params);
        }
        return o;
    }

    public static JsonObject request(String method, long id, JsonObject params) {
        return request(method, new com.google.gson.JsonPrimitive(id), params);
    }

    public static JsonObject notification(String method, JsonObject params) {
        final JsonObject o = base(method);
        if (params != null) {
            o.add("params", params);
        }
        return o;
    }

    public static JsonObject result(JsonElement id, JsonElement result) {
        final JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", VERSION);
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.add("result", result == null ? new JsonObject() : result);
        return o;
    }

    public static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
        final JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "" : message);
        if (data != null) {
            err.add("data", data);
        }
        final JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", VERSION);
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.add("error", err);
        return o;
    }

    public static JsonObject error(JsonElement id, McpError e) {
        return error(id, e.code(), e.getMessage(), e.data());
    }

    private static JsonObject base(String method) {
        final JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", VERSION);
        o.addProperty("method", method);
        return o;
    }

    // ==================== 解析 ====================

    /**
     * 解析一条消息文本。
     *
     * @return JSON 对象，或 {@code null}（空输入）
     * @throws JsonSyntaxException 非法 JSON
     */
    public static JsonElement parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final JsonElement el = JsonParser.parseString(text);
        if (el.isJsonArray()) {
            // 批量：MCP 2025-06-18 已移除 batch，但为兼容老客户端仍接受（逐条处理）
            return el;
        }
        if (!el.isJsonObject()) {
            throw new JsonSyntaxException("消息必须是 JSON 对象");
        }
        return el;
    }

    /** 消息是否为"请求"（需要响应） */
    public static boolean isRequest(JsonElement msg) {
        return msg != null && msg.isJsonObject()
                && msg.getAsJsonObject().has("method")
                && msg.getAsJsonObject().has("id");
    }

    /** 消息是否为"通知"（不需要响应） */
    public static boolean isNotification(JsonElement msg) {
        return msg != null && msg.isJsonObject()
                && msg.getAsJsonObject().has("method")
                && !msg.getAsJsonObject().has("id");
    }

    /** 消息是否为"响应/错误"（客户端对服务端请求的回复） */
    public static boolean isResponse(JsonElement msg) {
        return msg != null && msg.isJsonObject()
                && !msg.getAsJsonObject().has("method")
                && (msg.getAsJsonObject().has("result") || msg.getAsJsonObject().has("error"));
    }

    public static String methodOf(JsonObject msg) {
        final JsonElement m = msg.get("method");
        return m == null || m.isJsonNull() ? "" : m.getAsString();
    }

    public static JsonElement idOf(JsonObject msg) {
        return msg.get("id");
    }

    /** params（缺失时返回空对象，避免到处判空） */
    public static JsonObject paramsOf(JsonObject msg) {
        final JsonElement p = msg.get("params");
        return p != null && p.isJsonObject() ? p.getAsJsonObject() : new JsonObject();
    }

    /** 校验 jsonrpc 字段；不合格抛 {@link McpError#invalidRequest} */
    public static void requireVersion(JsonObject msg) {
        final JsonElement v = msg.get("jsonrpc");
        if (v == null || v.isJsonNull() || !VERSION.equals(v.getAsString())) {
            throw McpError.invalidRequest("jsonrpc 必须是 \"2.0\"");
        }
    }

    // ==================== 小工具 ====================

    public static String str(JsonObject o, String key, String def) {
        final JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static int num(JsonObject o, String key, int def) {
        final JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        final JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    /** 单行 JSON（SSE 的 data 只能单行） */
    public static String oneLine(JsonElement el) {
        return el == null ? "null" : el.toString();
    }

    public static JsonArray array(JsonElement... items) {
        final JsonArray a = new JsonArray();
        for (JsonElement e : items) {
            a.add(e);
        }
        return a;
    }
}
