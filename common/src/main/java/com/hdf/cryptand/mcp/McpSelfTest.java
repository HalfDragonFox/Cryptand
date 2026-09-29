package com.hdf.cryptand.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hdf.cryptand.mcp.resource.McpResource;
import com.hdf.cryptand.mcp.resource.McpResourceContent;
import com.hdf.cryptand.mcp.resource.McpResourceProvider;
import com.hdf.cryptand.mcp.tool.McpToolResult;
import com.hdf.cryptand.mcp.tool.ToolSchemas;
import com.hdf.cryptand.mcp.transport.HttpMcpConfig;
import com.hdf.cryptand.mcp.transport.HttpMcpTransport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ===== MCP 框架自测（纯 Java 零 MC）=====
 *
 * <p>验证的是<b>协议行为</b>，不是"看起来像 MCP"：</p>
 * <ul>
 *   <li>JSON-RPC 2.0 结构与错误码；</li>
 *   <li>生命周期门禁（未 initialize 的请求被拒）与版本协商（不支持的版本回落到最新）；</li>
 *   <li>tools/list 分页与 JSON Schema（老式参数表翻译）；tools/call 的成功/失败/未知工具/异常翻译；</li>
 *   <li>resources/list + resources/read（文本与二进制 blob）；</li>
 *   <li>执行调度（工具在宿主线程执行，调用线程阻塞等待）；</li>
 *   <li><b>真实 HTTP 端到端</b>：Streamable HTTP（会话头、202 Accepted、404/400 边界）与旧版 HTTP+SSE。</li>
 * </ul>
 *
 * <p>运行：{@code gradlew :common:runMcpTest}（或直接 java 运行本类）。</p>
 */
public final class McpSelfTest {

    private static final StringBuilder LOG = new StringBuilder();
    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cryptand MCP Self Test (common/mcp, 纯 Java 零 MC) ===");
        testJsonRpc();
        testSchemaTranslation();
        testLifecycle();
        testTools();
        testResources();
        testExecutor();
        testHttpTransport();
        testLegacySse();
        System.out.println();
        System.out.print(LOG);
        System.out.println();
        System.out.println("=== 结果：PASS " + PASSED.get() + " / FAIL " + FAILED.get() + " ===");
        System.exit(FAILED.get() == 0 ? 0 : 1);
    }

    // ==================== 1. JSON-RPC 层 ====================

    private static void testJsonRpc() {
        final JsonObject req = JsonRpc.request(Mcp.M_TOOLS_LIST, 7, new JsonObject());
        check("JSON-RPC 请求含 jsonrpc/method/id", "2.0".equals(req.get("jsonrpc").getAsString())
                && "tools/list".equals(req.get("method").getAsString())
                && req.get("id").getAsInt() == 7, req.toString());
        check("isRequest 判定", JsonRpc.isRequest(req));

        final JsonObject note = JsonRpc.notification(Mcp.M_INITIALIZED, null);
        check("通知不带 id", !note.has("id") && JsonRpc.isNotification(note), note.toString());

        final JsonObject err = JsonRpc.error(new com.google.gson.JsonPrimitive(3), McpError.methodNotFound("x"));
        check("错误对象结构", err.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_METHOD_NOT_FOUND
                && err.getAsJsonObject("error").has("message"), err.toString());

        final JsonObject ok = JsonRpc.result(new com.google.gson.JsonPrimitive("a"), new JsonObject());
        check("响应同时带 id 与 result", ok.has("id") && ok.has("result") && !ok.has("error"), ok.toString());
    }

    // ==================== 2. 老式参数表 → JSON Schema ====================

    private static void testSchemaTranslation() {
        final JsonObject legacy = new JsonObject();
        legacy.addProperty("x", "int/string（支持 ~）");
        legacy.addProperty("block", "string（简名 stone）");
        legacy.addProperty("lines", "int（默认 20）");
        legacy.addProperty("blocks", "两种写法都收：[{...}] 或紧凑映射");
        final JsonObject schema = ToolSchemas.fromLegacyPairs(legacy);

        check("schema 根为 object", "object".equals(schema.get("type").getAsString()), schema.toString());
        final JsonObject props = schema.getAsJsonObject("properties");
        final JsonObject x = props.getAsJsonObject("x");
        check("多类型 → anyOf(integer,string)", x.has("anyOf")
                        && x.getAsJsonArray("anyOf").size() == 2
                        && x.getAsJsonArray("anyOf").get(0).getAsJsonObject().get("type").getAsString().equals("integer"),
                x.toString());
        check("单类型 → type=integer", "integer".equals(
                props.getAsJsonObject("lines").get("type").getAsString()), props.getAsJsonObject("lines").toString());
        check("说明保留原文", props.getAsJsonObject("block").get("description").getAsString().contains("简名"),
                props.getAsJsonObject("block").toString());
        check("无法识别类型时保持宽松（不猜类型）",
                !props.getAsJsonObject("blocks").has("type")
                        && !props.getAsJsonObject("blocks").has("anyOf"),
                props.getAsJsonObject("blocks").toString());
        check("工具名合法性校验", ToolSchemas.validToolName("place_block")
                && !ToolSchemas.validToolName("放置 方块"));
    }

    // ==================== 3. 生命周期 ====================

    private static void testLifecycle() throws Exception {
        final McpServer server = newServer();
        final McpSession session = server.createSession();

        // 初始化前：请求被拒（MCP 规范要求报错），通知被静默忽略
        final JsonObject early = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        check("未初始化 → -32003", early.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_NOT_INITIALIZED,
                early.toString());
        check("未初始化 → 通知被静默忽略", rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/other\"}") == null);

        // 版本协商：客户端请求的版本不在支持列表 → 回最新
        final JsonObject init = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"1999-01-01\",\"capabilities\":{},\"clientInfo\":{\"name\":\"selftest\",\"version\":\"1\"}}}");
        final JsonObject result = init.getAsJsonObject("result");
        check("版本协商回落最新", Mcp.VERSION_LATEST.equals(result.get("protocolVersion").getAsString()),
                result.toString());
        check("返回 capabilities（tools+resources+logging）",
                result.getAsJsonObject("capabilities").has("tools")
                        && result.getAsJsonObject("capabilities").has("resources")
                        && result.getAsJsonObject("capabilities").has("logging"), result.toString());
        check("返回 serverInfo", result.getAsJsonObject("serverInfo").has("name"), result.toString());
        check("客户端信息已记录", "selftest".equals(session.clientName()), session.clientName());

        // 支持的版本原样回显
        final McpSession old = server.createSession();
        final JsonObject init2 = rpc(server, old,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
        check("支持版本回显", "2024-11-05".equals(
                init2.getAsJsonObject("result").get("protocolVersion").getAsString()), init2.toString());

        rpc(server, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        check("初始化完成标记", session.initialized());

        final JsonObject ping = rpc(server, session, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}");
        check("ping → 空结果", ping.has("result") && ping.getAsJsonObject("result").size() == 0, ping.toString());

        final JsonObject unknown = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/whatever\"}");
        check("未知方法 → -32601", unknown.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_METHOD_NOT_FOUND,
                unknown.toString());

        final JsonObject badVersion = rpc(server, session, "{\"jsonrpc\":\"1.0\",\"id\":6,\"method\":\"ping\"}");
        check("jsonrpc 版本非法 → -32600",
                badVersion.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_INVALID_REQUEST,
                badVersion.toString());
    }

    // ==================== 4. tools ====================

    private static void testTools() throws Exception {
        final McpServer server = newServer();
        final McpSession session = initialized(server);

        final JsonObject list = rpc(server, session, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        final JsonArray tools = list.getAsJsonObject("result").getAsJsonArray("tools");
        check("tools/list 返回全部工具", tools.size() == 4, "size=" + tools.size());
        final JsonObject echo = findTool(tools, "echo");
        check("工具带 inputSchema(object)",
                echo != null && "object".equals(echo.getAsJsonObject("inputSchema").get("type").getAsString()),
                String.valueOf(echo));
        check("工具带 readOnlyHint 注解",
                echo != null && echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean(),
                String.valueOf(echo));
        check("工具顺序稳定且可被搜索", tools.get(0).getAsJsonObject().get("name").getAsString().equals("echo"),
                tools.get(0).toString());

        // 分页：limit=2 → nextCursor → 第二页
        final JsonObject page1 = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{\"limit\":2}}");
        final JsonObject r1 = page1.getAsJsonObject("result");
        check("分页第一页 2 条 + nextCursor", r1.getAsJsonArray("tools").size() == 2 && r1.has("nextCursor"),
                r1.toString());
        final JsonObject page2 = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"limit\":2,\"cursor\":\""
                        + r1.get("nextCursor").getAsString() + "\"}}");
        check("分页第二页 2 条且无 nextCursor",
                page2.getAsJsonObject("result").getAsJsonArray("tools").size() == 2
                        && !page2.getAsJsonObject("result").has("nextCursor"), page2.toString());

        // tools/call 成功
        final JsonObject call = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"你好\"}}}");
        final JsonObject callResult = call.getAsJsonObject("result");
        check("tools/call 返回 content[0].text",
                callResult.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString().equals("text"),
                callResult.toString());
        check("tools/call 返回 structuredContent",
                callResult.has("structuredContent")
                        && callResult.getAsJsonObject("structuredContent").get("echo").getAsString().equals("你好"),
                callResult.toString());

        // 工具内部失败 → isError（不是协议错误）
        final JsonObject failing = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"explode\",\"arguments\":{}}}");
        check("工具内部失败 → isError=true 且无 error 对象",
                failing.has("result")
                        && failing.getAsJsonObject("result").get("isError").getAsBoolean()
                        && !failing.has("error"), failing.toString());

        // 工具抛 McpError → 协议错误，码透传
        final JsonObject badArgs = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"strict\",\"arguments\":{}}}");
        check("工具抛 McpError → -32602 透传",
                badArgs.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_INVALID_PARAMS,
                badArgs.toString());

        // 未知工具
        final JsonObject unknown = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}");
        check("未知工具 → -32602", unknown.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_INVALID_PARAMS,
                unknown.toString());

        // 工具抛普通异常 → -32603
        final JsonObject boom = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"boom\"}}");
        check("工具抛异常 → -32603", boom.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_INTERNAL,
                boom.toString());

        // 批量请求（老客户端；2025-06-18 已移除但保留兼容）
        final JsonElement batch = server.handle(JsonParser.parseString(
                        "[{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"},"
                                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"), session)
                .get(5, TimeUnit.SECONDS);
        check("批量：只回请求、不回通知", batch != null && batch.isJsonArray()
                && batch.getAsJsonArray().size() == 1, String.valueOf(batch));

        final JsonObject stats = server.stats();
        check("统计记录调用与错误", stats.get("toolCalls").getAsInt() >= 4
                && stats.get("toolErrors").getAsInt() >= 2, stats.toString());
    }

    // ==================== 5. resources ====================

    private static void testResources() throws Exception {
        final McpServer server = newServer();
        final McpSession session = initialized(server);

        final JsonObject list = rpc(server, session, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        final JsonArray items = list.getAsJsonObject("result").getAsJsonArray("resources");
        check("resources/list 列出资源", items.size() == 2, "size=" + items.size());

        final JsonObject read = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{\"uri\":\"test://note\"}}");
        check("resources/read 文本内容",
                read.getAsJsonObject("result").getAsJsonArray("contents").get(0)
                        .getAsJsonObject().get("text").getAsString().equals("hello"),
                read.toString());

        final JsonObject readBlob = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\"test://pixel\"}}");
        check("resources/read 二进制 → base64 blob",
                readBlob.getAsJsonObject("result").getAsJsonArray("contents").get(0)
                        .getAsJsonObject().has("blob"), readBlob.toString());

        final JsonObject missing = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"test://nope\"}}");
        check("资源不存在 → -32002",
                missing.getAsJsonObject("error").get("code").getAsInt() == Mcp.E_RESOURCE_NOT_FOUND,
                missing.toString());

        final JsonObject templates = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/templates/list\"}");
        check("资源模板列表", templates.getAsJsonObject("result")
                .getAsJsonArray("resourceTemplates").size() == 1, templates.toString());
    }

    // ==================== 6. 执行调度 ====================

    private static void testExecutor() throws Exception {
        final ExecutorService host = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "fake-host-thread");
            t.setDaemon(true);
            return t;
        });
        final AtomicReference<String> ranOn = new AtomicReference<>();
        final McpExecutor executor = host::execute;
        final McpServer server = new McpServer(
                McpServerInfo.of("cryptand-selftest", "0.0.1"), executor);
        server.tools().register("whoami", "返回执行线程名", ToolSchemas.object(), args -> {
            ranOn.set(Thread.currentThread().getName());
            return McpToolResult.text(Thread.currentThread().getName());
        });
        final McpSession session = initialized(server);
        final long callerThread = Thread.currentThread().threadId();
        final JsonObject call = rpc(server, session,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"whoami\"}}");
        check("工具在宿主线程执行（非调用线程）",
                "fake-host-thread".equals(ranOn.get())
                        && !"fake-host-thread".equals(Thread.currentThread().getName()),
                "ranOn=" + ranOn.get() + " caller=" + Thread.currentThread().getName());
        check("调用线程拿到结果（同步语义）",
                call.getAsJsonObject("result").getAsJsonArray("content").get(0)
                        .getAsJsonObject().get("text").getAsString().equals("fake-host-thread"),
                call.toString());
        host.shutdownNow();
    }

    // ==================== 7. Streamable HTTP 端到端 ====================

    private static void testHttpTransport() throws Exception {
        final McpServer server = newServer();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final HttpMcpTransport transport = new HttpMcpTransport(server,
                new HttpMcpConfig().port(0)).log(logs::add);
        transport.start();
        final String base = "http://127.0.0.1:" + transport.port();
        final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        try {
            check("HTTP 已启动且端口有效", transport.running() && transport.port() > 0,
                    "port=" + transport.port());

            // initialize（无会话头）→ 200 + Mcp-Session-Id
            final HttpResponse<String> init = post(client, base + "/mcp", null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"http-selftest\",\"version\":\"1\"}}}");
            check("HTTP initialize → 200", init.statusCode() == 200, "status=" + init.statusCode());
            final String sessionId = init.headers().firstValue("Mcp-Session-Id").orElse(null);
            check("HTTP 下发 Mcp-Session-Id", sessionId != null && !sessionId.isBlank(), String.valueOf(sessionId));
            check("HTTP 响应 Content-Type 为 JSON",
                    init.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                    init.headers().firstValue("Content-Type").orElse(""));
            final JsonElement initResult = JsonParser.parseString(init.body()).getAsJsonObject().get("result");
            check("HTTP initialize 返回 serverInfo",
                    initResult.getAsJsonObject().has("serverInfo"), init.body());

            // 通知（带会话头）→ 202 Accepted，无 body
            final HttpResponse<String> note = post(client, base + "/mcp", sessionId,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            check("HTTP 通知 → 202 Accepted", note.statusCode() == 202, "status=" + note.statusCode());
            check("HTTP 202 无 body", note.body().isEmpty(), note.body());

            // tools/list
            final HttpResponse<String> list = post(client, base + "/mcp", sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            check("HTTP tools/list 200 且含工具",
                    list.statusCode() == 200
                            && JsonParser.parseString(list.body()).getAsJsonObject()
                            .getAsJsonObject("result").getAsJsonArray("tools").size() == 4,
                    list.body());

            // tools/call
            final HttpResponse<String> call = post(client, base + "/mcp", sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                            + "{\"name\":\"echo\",\"arguments\":{\"text\":\"via-http\"}}}");
            check("HTTP tools/call 返回结果",
                    call.statusCode() == 200 && call.body().contains("via-http"), call.body());

            // resources/read 走 HTTP
            final HttpResponse<String> res = post(client, base + "/mcp", sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"test://note\"}}");
            check("HTTP resources/read", res.statusCode() == 200 && res.body().contains("hello"), res.body());

            // 错误会话 → 404
            final HttpResponse<String> stale = post(client, base + "/mcp", "not-a-session",
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}");
            check("HTTP 未知会话 → 404", stale.statusCode() == 404, "status=" + stale.statusCode());

            // 缺会话头且非 initialize → 400
            final HttpResponse<String> noSession = post(client, base + "/mcp", null,
                    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}");
            check("HTTP 缺会话头 → 400", noSession.statusCode() == 400, "status=" + noSession.statusCode());

            // 非法 JSON → 400 + parse error
            final HttpResponse<String> broken = post(client, base + "/mcp", sessionId, "{not json");
            check("HTTP 非法 JSON → 400 且 -32700",
                    broken.statusCode() == 400 && broken.body().contains("-32700"),
                    broken.statusCode() + " " + broken.body());

            // 协议版本头不支持 → 400
            final HttpResponse<String> badVersion = postWithVersion(client, base + "/mcp", sessionId, "1999-01-01",
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");
            check("HTTP 不支持版本头 → 400", badVersion.statusCode() == 400,
                    badVersion.statusCode() + " " + badVersion.body());

            // GET 流：无会话 → 400
            final HttpResponse<String> badStream = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/mcp")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("HTTP GET 无会话 → 400", badStream.statusCode() == 400, "status=" + badStream.statusCode());

            // Origin 校验（规范 MUST，防 DNS rebinding）：跨站来源 → 403
            final HttpResponse<String> crossOrigin = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/mcp"))
                    .header("Origin", "http://evil.example")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check("HTTP 跨站 Origin → 403", crossOrigin.statusCode() == 403,
                    "status=" + crossOrigin.statusCode());
            final HttpResponse<String> localOrigin = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/mcp"))
                    .header("Origin", "http://localhost:6274")
                    .header("Content-Type", "application/json")
                    .header(Mcp.H_SESSION_ID, sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"ping\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check("HTTP 本机 Origin → 200 且回显 CORS",
                    localOrigin.statusCode() == 200
                            && localOrigin.headers().firstValue("Access-Control-Allow-Origin")
                            .orElse("").equals("http://localhost:6274"),
                    localOrigin.statusCode() + " "
                            + localOrigin.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

            // DELETE → 204，会话失效
            final HttpResponse<String> delete = client.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .header(Mcp.H_SESSION_ID, sessionId).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            check("HTTP DELETE → 204", delete.statusCode() == 204, "status=" + delete.statusCode());
            final HttpResponse<String> afterDelete = post(client, base + "/mcp", sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}");
            check("HTTP 会话终止后 → 404", afterDelete.statusCode() == 404,
                    "status=" + afterDelete.statusCode());
        } finally {
            transport.stop();
        }
        check("HTTP 已停止", !transport.running());
        check("传输日志可用（诊断）", !logs.isEmpty(), "logs=" + logs.size());
    }

    // ==================== 8. 旧版 HTTP+SSE ====================

    private static void testLegacySse() throws Exception {
        final McpServer server = newServer();
        final HttpMcpTransport transport = new HttpMcpTransport(server,
                new HttpMcpConfig().port(0)).log(null);
        transport.start();
        final String base = "http://127.0.0.1:" + transport.port();
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            final HttpResponse<InputStream> sse = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/sse")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            check("旧版 /sse → 200 text/event-stream",
                    sse.statusCode() == 200
                            && sse.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
                    sse.statusCode() + " " + sse.headers().firstValue("Content-Type").orElse(""));
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sse.body(), StandardCharsets.UTF_8));

            final String endpointEvent = awaitEvent(reader);
            check("旧版首事件为 endpoint", endpointEvent.contains("event: endpoint")
                    && endpointEvent.contains(config_messagePath()), endpointEvent.replace("\n", " | "));
            final String sessionId = extractSessionId(endpointEvent);
            check("旧版 endpoint 带 sessionId", sessionId != null && !sessionId.isBlank(), String.valueOf(sessionId));

            // 通过 /message 投递 initialize，响应应从 SSE 流回来
            final CompletableFuture<String> incoming = CompletableFuture.supplyAsync(() -> {
                try {
                    // 服务端可能在流上先发通知（logging 等），跳过不匹配的事件再取响应
                    return awaitEventMatching(reader, "\"id\":11", 10);
                } catch (Exception ex) {
                    return "ERROR " + ex;
                }
            });
            final HttpResponse<String> post = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/message?sessionId=" + sessionId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"initialize\",\"params\":"
                                    + "{\"protocolVersion\":\"2024-11-05\"}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check("旧版 /message → 202", post.statusCode() == 202, "status=" + post.statusCode());
            final String responseEvent = incoming.get(10, TimeUnit.SECONDS);
            check("旧版响应经 SSE 返回（id=11 + 版本 2024-11-05）",
                    responseEvent.contains("\"id\":11") && responseEvent.contains("2024-11-05"),
                    responseEvent.replace("\n", " | "));
        } finally {
            transport.stop();
        }
    }

    private static String config_messagePath() {
        return "/message";
    }

    private static String awaitEvent(BufferedReader reader) throws Exception {
        final StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (sb.length() > 0) {
                    break;
                }
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 读事件直到命中 needle（跳过服务端主动通知），最多 maxEvents 个事件 */
    private static String awaitEventMatching(BufferedReader reader, String needle, int maxEvents)
            throws Exception {
        for (int i = 0; i < maxEvents; i++) {
            final String event = awaitEvent(reader);
            if (event.isEmpty()) {
                break;
            }
            if (event.contains(needle)) {
                return event;
            }
        }
        return "";
    }

    private static String extractSessionId(String event) {
        final int at = event.indexOf("sessionId=");
        if (at < 0) {
            return null;
        }
        final String tail = event.substring(at + "sessionId=".length());
        final int end = tail.indexOf('\n');
        return (end < 0 ? tail : tail.substring(0, end)).trim();
    }

    // ==================== 夹具 ====================

    /** 标准夹具：4 个工具（echo / explode / strict / boom）+ 2 个资源 + 1 个资源模板 */
    private static McpServer newServer() {
        final McpServer server = new McpServer(
                McpServerInfo.of("cryptand-selftest", "0.0.1").withInstructions("自测用服务器"),
                McpExecutor.DIRECT);

        server.tools().register("echo", "回显参数（只读）",
                ToolSchemas.builder().str("text", "要回显的文本").build(),
                ToolSchemas.annotations(true, false, true, false),
                args -> {
                    final JsonObject o = new JsonObject();
                    o.addProperty("echo", JsonRpc.str(args, "text", ""));
                    return McpToolResult.structured(o);
                });

        server.tools().register("explode", "总是失败（返回 isError）",
                ToolSchemas.object(), ToolSchemas.annotations(false, true, false, false),
                args -> McpToolResult.error("ERR 故意失败"));

        server.tools().register("strict", "参数校验失败（抛 McpError）",
                ToolSchemas.object(), ToolSchemas.annotations(false, false, false, false),
                args -> {
                    throw McpError.invalidParams("strict 需要 text 参数");
                });

        server.tools().register("boom", "抛未捕获异常",
                ToolSchemas.object(), args -> {
                    throw new IllegalStateException("boom");
                });

        server.resources().addProvider(new McpResourceProvider() {
            @Override
            public String id() {
                return "selftest";
            }

            @Override
            public List<McpResource> list() {
                return List.of(McpResource.of("test://note", "note", "text/plain"),
                        McpResource.of("test://pixel", "pixel", "image/png"));
            }

            @Override
            public McpResourceContent read(String uri) {
                return switch (uri) {
                    case "test://note" -> McpResourceContent.text(uri, "text/plain", "hello");
                    case "test://pixel" -> McpResourceContent.blob(uri, "image/png", new byte[]{1, 2, 3});
                    default -> null;
                };
            }

            @Override
            public List<ResourceTemplate> templates() {
                return List.of(new ResourceTemplate("test://{name}", "test", "测试资源", "示例", "text/plain"));
            }
        });
        return server;
    }

    private static McpSession initialized(McpServer server) throws Exception {
        final McpSession session = server.createSession();
        rpc(server, session, "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"selftest\",\"version\":\"1\"}}}");
        rpc(server, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return session;
    }

    private static JsonObject rpc(McpServer server, McpSession session, String json) throws Exception {
        final JsonElement response = server.handle(JsonParser.parseString(json), session)
                .get(5, TimeUnit.SECONDS);
        if (response == null || response.isJsonNull()) {
            return null;
        }
        return response.isJsonArray() ? null : response.getAsJsonObject();
    }

    private static JsonObject findTool(JsonArray tools, String name) {
        for (JsonElement e : tools) {
            if (name.equals(e.getAsJsonObject().get("name").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    private static HttpResponse<String> post(HttpClient client, String url, String sessionId, String body)
            throws Exception {
        return postWithVersion(client, url, sessionId, null, body);
    }

    private static HttpResponse<String> postWithVersion(HttpClient client, String url, String sessionId,
                                                       String protocolVersion, String body) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header(Mcp.H_SESSION_ID, sessionId);
        }
        if (protocolVersion != null) {
            builder.header(Mcp.H_PROTOCOL_VERSION, protocolVersion);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            PASSED.incrementAndGet();
            LOG.append("  [PASS] ").append(name).append('\n');
        } else {
            FAILED.incrementAndGet();
            LOG.append("  [FAIL] ").append(name).append("  →  ").append(detail).append('\n');
        }
    }
}
