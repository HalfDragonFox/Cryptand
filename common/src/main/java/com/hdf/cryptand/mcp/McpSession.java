package com.hdf.cryptand.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 一次 MCP 会话（一个客户端连接）。
 *
 * <p>保存协议协商结果与客户端信息，并提供服务端主动通知的便捷方法
 * （{@link #progress} / {@link #log}）。会话对象由传输层创建，
 * HTTP 下与 {@code Mcp-Session-Id} 一一对应；stdio 下整个进程一个会话。</p>
 */
public final class McpSession {

    private final String id;
    private final long createdAt = System.currentTimeMillis();

    private volatile String protocolVersion = Mcp.VERSION_LATEST;
    private volatile boolean initialized;
    private volatile JsonObject clientInfo = new JsonObject();
    private volatile JsonObject clientCapabilities = new JsonObject();
    private volatile String logLevel = "info";
    private volatile McpNotifier notifier = McpNotifier.NOOP;

    public McpSession(String id) {
        this.id = id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id;
    }

    public static McpSession create() {
        return new McpSession(java.util.UUID.randomUUID().toString());
    }

    public String id() {
        return id;
    }

    public long createdAt() {
        return createdAt;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    public void protocolVersion(String version) {
        this.protocolVersion = version;
    }

    public boolean initialized() {
        return initialized;
    }

    public void markInitialized() {
        this.initialized = true;
    }

    public JsonObject clientInfo() {
        return clientInfo;
    }

    public void clientInfo(JsonObject info) {
        this.clientInfo = info == null ? new JsonObject() : info;
    }

    public JsonObject clientCapabilities() {
        return clientCapabilities;
    }

    public void clientCapabilities(JsonObject caps) {
        this.clientCapabilities = caps == null ? new JsonObject() : caps;
    }

    public String logLevel() {
        return logLevel;
    }

    public void logLevel(String level) {
        this.logLevel = level == null ? "info" : level;
    }

    /** 客户端名（诊断用，如 "claude-ai" / "cline"） */
    public String clientName() {
        return JsonRpc.str(clientInfo, "name", "unknown");
    }

    public McpNotifier notifier() {
        return notifier;
    }

    public void notifier(McpNotifier notifier) {
        this.notifier = notifier == null ? McpNotifier.NOOP : notifier;
    }

    // ==================== 服务端主动通知 ====================

    /** 进度通知（客户端在请求 _meta.progressToken 时才会收到） */
    public void progress(JsonElement progressToken, double progress, Double total, String message) {
        if (progressToken == null || progressToken.isJsonNull()) {
            return;
        }
        final JsonObject params = new JsonObject();
        params.add("progressToken", progressToken);
        params.addProperty("progress", progress);
        if (total != null) {
            params.addProperty("total", total);
        }
        if (message != null && !message.isBlank()) {
            params.addProperty("message", message);
        }
        notifier.notify(JsonRpc.notification(Mcp.M_NOTIFY_PROGRESS, params));
    }

    /** 日志通知（遵循客户端设定的日志级别） */
    public void log(String level, String logger, String message) {
        final JsonObject params = new JsonObject();
        params.addProperty("level", level == null ? "info" : level);
        if (logger != null && !logger.isBlank()) {
            params.addProperty("logger", logger);
        }
        params.addProperty("data", message == null ? "" : message);
        notifier.notify(JsonRpc.notification(Mcp.M_NOTIFY_MESSAGE, params));
    }

    /** 工具清单变化（客户端可据此重新 tools/list） */
    public void toolsChanged() {
        notifier.notify(JsonRpc.notification(Mcp.M_TOOLS_LIST_CHANGED, null));
    }

    /** 资源清单变化 */
    public void resourcesChanged() {
        notifier.notify(JsonRpc.notification(Mcp.M_RESOURCES_LIST_CHANGED, null));
    }

    public JsonObject describe() {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("protocolVersion", protocolVersion);
        o.addProperty("initialized", initialized);
        o.addProperty("client", clientName());
        final JsonArray caps = new JsonArray();
        for (String key : clientCapabilities.keySet()) {
            caps.add(key);
        }
        o.add("clientCapabilities", caps);
        o.addProperty("ageMs", System.currentTimeMillis() - createdAt);
        return o;
    }
}
