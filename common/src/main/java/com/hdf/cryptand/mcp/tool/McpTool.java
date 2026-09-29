package com.hdf.cryptand.mcp.tool;

import com.google.gson.JsonObject;

/**
 * 一个 MCP 工具（{@code tools/list} 的一项 + 可执行实现）。
 *
 * @param name       工具名（客户端用它调用，建议 snake_case）
 * @param title      人类可读标题（可空）
 * @param description 说明（模型据此决定何时用；写清"什么时候用/不要用"效果最好）
 * @param inputSchema 参数 schema（标准 JSON Schema，type=object；必须存在，可为空对象）
 * @param annotations 提示（readOnlyHint / destructiveHint / idempotentHint / openWorldHint / title）
 * @param group      分组标签（仅服务端用于归类展示，不发给客户端）
 * @param handler    实现
 */
public record McpTool(String name, String title, String description, JsonObject inputSchema,
                      JsonObject annotations, String group, McpToolHandler handler) {

    public McpTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (inputSchema == null) {
            inputSchema = new JsonObject();
        }
        if (description == null) {
            description = "";
        }
    }

    /** tools/list 里的一项 */
    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (title != null && !title.isBlank()) {
            o.addProperty("title", title);
        }
        o.addProperty("description", description);
        o.add("inputSchema", inputSchema);
        if (annotations != null && annotations.size() > 0) {
            o.add("annotations", annotations);
        }
        return o;
    }

    /** 与另一个工具合并注解（MCP 约定：后注册的同名工具整体替换，这里保留链式风格） */
    public McpTool withAnnotations(JsonObject annotations) {
        return new McpTool(name, title, description, inputSchema, annotations, group, handler);
    }

    public McpTool withGroup(String group) {
        return new McpTool(name, title, description, inputSchema, annotations, group, handler);
    }
}
