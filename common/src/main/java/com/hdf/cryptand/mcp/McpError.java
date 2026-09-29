package com.hdf.cryptand.mcp;

import com.google.gson.JsonElement;

/**
 * MCP/JSON-RPC 协议错误（会被 {@link McpServer} 翻译成 JSON-RPC error 对象）。
 *
 * <p>工具实现里可以直接 {@code throw McpError.invalidParams("x 必须为正整数")}，
 * 客户端会收到标准错误对象而不是"内部错误"。</p>
 */
public final class McpError extends RuntimeException {

    private final int code;
    private final transient JsonElement data;

    public McpError(int code, String message) {
        this(code, message, null);
    }

    public McpError(int code, String message, JsonElement data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public JsonElement data() {
        return data;
    }

    // ==================== 常用构造 ====================

    public static McpError invalidParams(String message) {
        return new McpError(Mcp.E_INVALID_PARAMS, message);
    }

    public static McpError invalidRequest(String message) {
        return new McpError(Mcp.E_INVALID_REQUEST, message);
    }

    public static McpError methodNotFound(String method) {
        return new McpError(Mcp.E_METHOD_NOT_FOUND, "未知方法：" + method);
    }

    public static McpError internal(String message) {
        return new McpError(Mcp.E_INTERNAL, message);
    }

    public static McpError notInitialized() {
        return new McpError(Mcp.E_NOT_INITIALIZED, "服务器尚未初始化：请先发送 initialize");
    }

    public static McpError resourceNotFound(String uri) {
        return new McpError(Mcp.E_RESOURCE_NOT_FOUND, "资源不存在：" + uri);
    }
}
