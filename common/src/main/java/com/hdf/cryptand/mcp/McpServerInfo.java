package com.hdf.cryptand.mcp;

import com.google.gson.JsonObject;

/**
 * 服务端自述信息（initialize 响应里的 {@code serverInfo} + {@code instructions}）。
 *
 * @param name         程序名（如 {@code cryptand-aiauto}）
 * @param title        人类可读标题
 * @param version      版本号
 * @param instructions 给模型的使用说明（MCP 会在客户端注入；可为空）
 */
public record McpServerInfo(String name, String title, String version, String instructions) {

    public static McpServerInfo of(String name, String version) {
        return new McpServerInfo(name, name, version, null);
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (title != null && !title.isBlank()) {
            o.addProperty("title", title);
        }
        o.addProperty("version", version);
        return o;
    }

    public McpServerInfo withInstructions(String text) {
        return new McpServerInfo(name, title, version, text);
    }
}
