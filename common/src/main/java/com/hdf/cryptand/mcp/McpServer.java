package com.hdf.cryptand.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hdf.cryptand.mcp.resource.McpResourceContent;
import com.hdf.cryptand.mcp.resource.McpResourceRegistry;
import com.hdf.cryptand.mcp.tool.McpTool;
import com.hdf.cryptand.mcp.tool.McpToolRegistry;
import com.hdf.cryptand.mcp.tool.McpToolResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===== MCP 服务端核心（协议实现，平台无关）=====
 *
 * <p>职责：JSON-RPC 消息校验 → MCP 生命周期门禁 → 方法分发 → 用 {@link McpExecutor}
 * 把工具执行投递到宿主线程。传输层（{@link com.hdf.cryptand.mcp.transport}）只负责搬字节。</p>
 *
 * <h3>支持的方法</h3>
 * <pre>
 * initialize / notifications/initialized / ping
 * tools/list / tools/call
 * resources/list / resources/read / resources/templates/list
 * logging/setLevel / notifications/cancelled（入站）
 * notifications/progress / notifications/message / *_list_changed（出站，经 McpSession）
 * </pre>
 *
 * <h3>线程模型</h3>
 * <p>{@link #handle} 可在任意线程调用；返回的 Future 在工具执行完成时完成。
 * HTTP 层可以安全地阻塞等待（有超时），因此"客户端看到的就是同步语义"。</p>
 */
public final class McpServer {

    private final McpServerInfo info;
    private final McpToolRegistry tools;
    private final McpResourceRegistry resources;
    private final McpExecutor executor;

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolErrors = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final Deque<JsonObject> recentCalls = new ArrayDeque<>();
    private final long startedAt = System.currentTimeMillis();

    /** 单次工具调用的等待上限（超时返回 JSON-RPC 错误，避免 HTTP 线程永久挂住） */
    private volatile long toolTimeoutMs = 60_000L;

    public McpServer(McpServerInfo info, McpExecutor executor) {
        this(info, new McpToolRegistry(), new McpResourceRegistry(), executor);
    }

    public McpServer(McpServerInfo info, McpToolRegistry tools, McpResourceRegistry resources,
                     McpExecutor executor) {
        this.info = info;
        this.tools = tools;
        this.resources = resources;
        this.executor = executor;
    }

    // ==================== 访问器 ====================

    public McpServerInfo info() {
        return info;
    }

    public McpToolRegistry tools() {
        return tools;
    }

    public McpResourceRegistry resources() {
        return resources;
    }

    public McpExecutor executor() {
        return executor;
    }

    public McpServer toolTimeoutMs(long ms) {
        this.toolTimeoutMs = Math.max(1000L, ms);
        return this;
    }

    public long toolTimeoutMs() {
        return toolTimeoutMs;
    }

    // ==================== 会话 ====================

    public McpSession createSession() {
        final McpSession session = McpSession.create();
        sessions.put(session.id(), session);
        return session;
    }

    public McpSession session(String id) {
        return id == null ? null : sessions.get(id);
    }

    /** 取已存在会话，或（allowCreate 时）新建 —— HTTP 层用 */
    public McpSession sessionOrCreate(String id, boolean allowCreate) {
        if (id != null) {
            final McpSession existing = sessions.get(id);
            if (existing != null) {
                return existing;
            }
        }
        return allowCreate ? createSession() : null;
    }

    public void closeSession(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    public Collection<McpSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    // ==================== 能力声明 ====================

    /** 服务端能力（initialize 响应里 capabilities 的内容） */
    public JsonObject capabilities() {
        final JsonObject caps = new JsonObject();
        final JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", true);
        caps.add("tools", toolsCap);
        final JsonObject resCap = new JsonObject();
        resCap.addProperty("subscribe", false);
        resCap.addProperty("listChanged", true);
        caps.add("resources", resCap);
        caps.add("logging", new JsonObject());
        return caps;
    }

    // ==================== 核心：处理一条消息 ====================

    /**
     * 处理一条 JSON-RPC 消息（单条或数组）。
     *
     * @return 响应（请求）或 {@code null}（通知/响应，无需回写）；数组请求返回 JSON 数组
     */
    public CompletableFuture<JsonElement> handle(JsonElement message, McpSession session) {
        if (message == null || message.isJsonNull()) {
            return CompletableFuture.completedFuture(null);
        }
        if (message.isJsonArray()) {
            return handleBatch(message.getAsJsonArray(), session);
        }
        if (!message.isJsonObject()) {
            return CompletableFuture.completedFuture(
                    JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "消息必须是 JSON 对象", null));
        }
        return dispatch(message.getAsJsonObject(), session);
    }

    private CompletableFuture<JsonElement> handleBatch(JsonArray batch, McpSession session) {
        if (batch.size() == 0) {
            return CompletableFuture.completedFuture(
                    JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "空批量请求", null));
        }
        final List<CompletableFuture<JsonElement>> futures = new ArrayList<>();
        for (JsonElement item : batch) {
            futures.add(handle(item, session));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v -> {
            final JsonArray out = new JsonArray();
            for (CompletableFuture<JsonElement> f : futures) {
                final JsonElement r = f.join();
                if (r != null) {
                    out.add(r);
                }
            }
            return out.size() == 0 ? null : out;
        });
    }

    private CompletableFuture<JsonElement> dispatch(JsonObject msg, McpSession session) {
        final String method = JsonRpc.methodOf(msg);
        final JsonElement id = JsonRpc.idOf(msg);
        final JsonObject params = JsonRpc.paramsOf(msg);
        final boolean wantsResponse = msg.has("id");
        requests.incrementAndGet();

        try {
            JsonRpc.requireVersion(msg);

            // ---- 生命周期前置：这三条不受"必须先 initialize"约束 ----
            if (Mcp.M_INITIALIZE.equals(method)) {
                return completed(JsonRpc.result(id, initialize(params, session, msg)));
            }
            if (Mcp.M_INITIALIZED.equals(method)) {
                session.markInitialized();
                // 会话日志放在这里：initialize 响应到达前不往流里塞无关消息（规范只允许 ping/logging 先行）
                session.log("debug", "mcp", "会话就绪：" + session.clientName()
                        + "（协议 " + session.protocolVersion() + "）");
                return completed(null);
            }
            if (Mcp.M_PING.equals(method)) {
                final JsonObject result = new JsonObject();
                return completed(JsonRpc.result(id, result));
            }

            // ---- 初始化门禁（规范：初始化前的其它请求应报错）----
            if (!session.initialized()) {
                if (!wantsResponse) {
                    return completed(null);            // 通知：静默忽略
                }
                throw McpError.notInitialized();
            }

            switch (method) {
                case Mcp.M_TOOLS_LIST:
                    return completed(JsonRpc.result(id, toolsList(params)));

                case Mcp.M_TOOLS_CALL:
                    return toolsCall(id, params, session);

                case Mcp.M_RESOURCES_LIST: {
                    final McpResourceRegistry.Page page = resources.page(
                            JsonRpc.str(params, "cursor", null), pageSize(params));
                    final JsonObject result = new JsonObject();
                    result.add("resources", page.resources());
                    if (page.nextCursor() != null) {
                        result.addProperty("nextCursor", page.nextCursor());
                    }
                    return completed(JsonRpc.result(id, result));
                }

                case Mcp.M_RESOURCES_TEMPLATES_LIST: {
                    final JsonObject result = new JsonObject();
                    result.add("resourceTemplates", resources.templates());
                    return completed(JsonRpc.result(id, result));
                }

                case Mcp.M_RESOURCES_READ:
                    return completed(readResource(id, params));

                case Mcp.M_LOGGING_SET_LEVEL: {
                    final String level = JsonRpc.str(params, "level", "");
                    if (!Mcp.LOG_LEVELS.contains(level)) {
                        throw McpError.invalidParams("非法日志级别：" + level
                                + "（合法值：" + String.join("/", Mcp.LOG_LEVELS) + "）");
                    }
                    session.logLevel(level);
                    session.log("info", "mcp", "日志级别已设为 " + level);
                    return completed(JsonRpc.result(id, new JsonObject()));
                }

                case Mcp.M_NOTIFY_CANCELLED:
                    // 取消通知：框架不中断宿主线程上的任务（工具通常很快），仅记录
                    return completed(null);

                default:
                    break;
            }

            if (!wantsResponse) {
                return completed(null);                // 未知通知：规范要求忽略
            }
            throw McpError.methodNotFound(method);

        } catch (McpError e) {
            return completed(wantsResponse ? JsonRpc.error(id, e) : null);
        } catch (Throwable t) {
            return completed(wantsResponse
                    ? JsonRpc.error(id, Mcp.E_INTERNAL, "内部错误：" + t, null) : null);
        }
    }

    // ==================== initialize ====================

    private JsonObject initialize(JsonObject params, McpSession session, JsonObject msg) {
        final String requested = JsonRpc.str(params, "protocolVersion", Mcp.VERSION_LATEST);
        final String agreed = Mcp.VERSIONS.contains(requested) ? requested : Mcp.VERSION_LATEST;
        session.protocolVersion(agreed);
        if (params.has("clientInfo") && params.get("clientInfo").isJsonObject()) {
            session.clientInfo(params.getAsJsonObject("clientInfo"));
        }
        if (params.has("capabilities") && params.get("capabilities").isJsonObject()) {
            session.clientCapabilities(params.getAsJsonObject("capabilities"));
        }

        final JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", agreed);
        result.add("capabilities", capabilities());
        result.add("serverInfo", info.toJson());
        if (info.instructions() != null && !info.instructions().isBlank()) {
            result.addProperty("instructions", info.instructions());
        }
        return result;
    }

    // ==================== tools ====================

    private int pageSize(JsonObject params) {
        final JsonElement limit = params.get("limit");
        return limit == null || limit.isJsonNull() ? 0 : limit.getAsInt();
    }

    private JsonObject toolsList(JsonObject params) {
        final McpToolRegistry.Page page = tools.page(JsonRpc.str(params, "cursor", null), pageSize(params));
        final JsonObject result = new JsonObject();
        result.add("tools", page.tools());
        if (page.nextCursor() != null) {
            result.addProperty("nextCursor", page.nextCursor());
        }
        return result;
    }

    private CompletableFuture<JsonElement> toolsCall(JsonElement id, JsonObject params, McpSession session) {
        final String name = JsonRpc.str(params, "name", "");
        final McpTool tool = tools.get(name);
        if (tool == null) {
            return completed(JsonRpc.error(id, Mcp.E_INVALID_PARAMS,
                    "未知工具：" + name + "（用 tools/list 查看可用工具）", null));
        }
        final JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        final JsonElement progressToken = progressTokenOf(params);
        final long started = System.currentTimeMillis();
        toolCalls.incrementAndGet();
        if (progressToken != null) {
            session.progress(progressToken, 0, null, "开始执行 " + name);
        }

        final CompletableFuture<McpToolResult> work = executor.submit(() -> {
            final McpToolResult r = tool.handler().call(args);
            return r == null ? McpToolResult.text("OK") : r;
        });

        return work.orTimeout(toolTimeoutMs, TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    final long took = System.currentTimeMillis() - started;
                    if (ex != null) {
                        toolErrors.incrementAndGet();
                        final Throwable cause = unwrap(ex);
                        record(name, false, took, String.valueOf(cause));
                        if (progressToken != null) {
                            session.progress(progressToken, 1, 1d, "失败：" + cause);
                        }
                        if (cause instanceof McpError me) {
                            return (JsonElement) JsonRpc.error(id, me);
                        }
                        return JsonRpc.error(id, Mcp.E_INTERNAL,
                                "工具 " + name + " 执行失败：" + cause, null);
                    }
                    if (result.isError()) {
                        toolErrors.incrementAndGet();
                    }
                    record(name, !result.isError(), took, null);
                    if (progressToken != null) {
                        session.progress(progressToken, 1, 1d, (result.isError() ? "失败" : "完成") + "：" + name);
                    }
                    return (JsonElement) JsonRpc.result(id, result.toJson());
                });
    }

    private JsonElement progressTokenOf(JsonObject params) {
        final JsonElement meta = params.get("_meta");
        if (meta == null || !meta.isJsonObject()) {
            return null;
        }
        final JsonElement token = meta.getAsJsonObject().get("progressToken");
        return token == null || token.isJsonNull() ? null : token;
    }

    private void record(String tool, boolean ok, long tookMs, String error) {
        final JsonObject o = new JsonObject();
        o.addProperty("tool", tool);
        o.addProperty("ok", ok);
        o.addProperty("tookMs", tookMs);
        o.addProperty("at", System.currentTimeMillis());
        if (error != null) {
            o.addProperty("error", error.length() > 300 ? error.substring(0, 300) : error);
        }
        synchronized (recentCalls) {
            recentCalls.addLast(o);
            while (recentCalls.size() > 30) {
                recentCalls.removeFirst();
            }
        }
    }

    // ==================== resources ====================

    private JsonObject readResource(JsonElement id, JsonObject params) {
        final String uri = JsonRpc.str(params, "uri", "");
        if (uri.isBlank()) {
            return JsonRpc.error(id, McpError.invalidParams("缺少 uri"));
        }
        try {
            final McpResourceContent content = resources.read(uri);
            if (content == null) {
                return JsonRpc.error(id, McpError.resourceNotFound(uri));
            }
            final JsonArray contents = new JsonArray();
            contents.add(content.toJson());
            final JsonObject result = new JsonObject();
            result.add("contents", contents);
            return JsonRpc.result(id, result);
        } catch (McpError e) {
            return JsonRpc.error(id, e);
        } catch (Throwable t) {
            return JsonRpc.error(id, Mcp.E_INTERNAL, "读取资源失败：" + t, null);
        }
    }

    // ==================== 诊断 ====================

    /** 运行统计（平台侧诊断工具用） */
    public JsonObject stats() {
        final JsonObject o = new JsonObject();
        o.addProperty("server", info.name());
        o.addProperty("version", info.version());
        o.addProperty("protocolVersion", Mcp.VERSION_LATEST);
        o.addProperty("uptimeMs", System.currentTimeMillis() - startedAt);
        o.addProperty("sessions", sessions.size());
        o.addProperty("tools", tools.size());
        o.addProperty("resourceProviders", resources.providers().size());
        o.addProperty("resources", resources.list().size());
        o.addProperty("requests", requests.get());
        o.addProperty("toolCalls", toolCalls.get());
        o.addProperty("toolErrors", toolErrors.get());
        synchronized (recentCalls) {
            final JsonArray arr = new JsonArray();
            for (JsonObject c : recentCalls) {
                arr.add(c.deepCopy());
            }
            o.add("recentCalls", arr);
        }
        return o;
    }

    // ==================== 辅助 ====================

    private static CompletableFuture<JsonElement> completed(JsonElement value) {
        return CompletableFuture.completedFuture(value);
    }

    private static Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || t instanceof ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
