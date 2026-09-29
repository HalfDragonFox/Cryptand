package com.hdf.cryptand.mcp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.hdf.cryptand.mcp.JsonRpc;
import com.hdf.cryptand.mcp.Mcp;
import com.hdf.cryptand.mcp.McpServer;
import com.hdf.cryptand.mcp.McpSession;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ===== Streamable HTTP 传输（MCP 2025-03-26 / 2025-06-18 标准传输）=====
 *
 * <p>单一端点（默认 {@code /mcp}）：</p>
 * <pre>
 * POST   /mcp   请求 → application/json 响应（纯通知则 202 Accepted）
 * GET    /mcp   打开 SSE 流，接收服务端主动消息（progress/logging/…）
 * DELETE /mcp   终止会话（204）
 * GET    /sse   旧版（2024-11-05）HTTP+SSE：先发 endpoint 事件
 * POST   /message?sessionId=…  旧版消息投递（202 → 响应走 SSE 流）
 * </pre>
 *
 * <p>会话经 {@code Mcp-Session-Id} 头传递；{@code MCP-Protocol-Version} 头做版本校验
 * （缺失时宽容放行，兼容老客户端）。纯 JDK 实现（{@code com.sun.net.httpserver}），
 * 零第三方依赖 —— 所以放在 common，NeoForge / Fabric / 独立进程都能直接复用。</p>
 *
 * <p><b>安全</b>：默认只绑 {@code 127.0.0.1}；绑非回环地址需显式 {@code allowRemote}，
 * 并建议同时设置 {@code token}（Bearer 校验）。</p>
 */
public final class HttpMcpTransport implements McpTransport {

    private final McpServer server;
    private final HttpMcpConfig config;
    private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();

    private HttpServer http;
    private ExecutorService pool;
    private volatile boolean running;
    private volatile int boundPort = -1;
    private volatile Consumer<String> logger = message -> System.err.println("[mcp/http] " + message);

    public HttpMcpTransport(McpServer server, HttpMcpConfig config) {
        this.server = server;
        this.config = config == null ? new HttpMcpConfig() : config;
    }

    /** 日志出口（默认 stderr；宿主可接到自己的日志系统） */
    public HttpMcpTransport log(Consumer<String> sink) {
        this.logger = sink == null ? message -> {
        } : sink;
        return this;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public int port() {
        return boundPort;
    }

    @Override
    public String endpoint() {
        return "http://" + (config.loopback() ? config.host : config.host) + ":"
                + (boundPort < 0 ? config.port : boundPort) + config.path;
    }

    @Override
    public boolean running() {
        return running;
    }

    // ==================== 生命周期 ====================

    @Override
    public void start() throws IOException {
        if (running) {
            return;
        }
        if (!config.loopback() && !config.allowRemote) {
            throw new IOException("拒绝监听非回环地址 " + config.host
                    + "：MCP 能操作游戏世界，需显式允许（allowRemote）");
        }
        final HttpServer created = HttpServer.create(
                new InetSocketAddress(config.host, config.port), 64);
        pool = config.threads > 0
                ? Executors.newFixedThreadPool(config.threads, HttpMcpTransport::daemon)
                : Executors.newCachedThreadPool(HttpMcpTransport::daemon);
        created.setExecutor(pool);
        created.createContext(config.path, this::handleStreamable);
        if (config.legacySse) {
            created.createContext(config.ssePath, ex -> handleLegacy(ex, true));
            created.createContext(config.messagePath, ex -> handleLegacy(ex, false));
        }
        created.start();
        this.http = created;
        this.boundPort = created.getAddress().getPort();
        this.running = true;
        logger.accept("已启动 " + endpoint()
                + (config.legacySse ? "（兼容旧版 " + config.ssePath + "）" : "")
                + (config.token.isEmpty() ? "" : "（Bearer 校验已启用）"));
    }

    @Override
    public void stop() {
        running = false;
        for (HttpSession s : sessions.values()) {
            s.closed = true;
            s.wakeup();
        }
        sessions.clear();
        final HttpServer server = this.http;
        if (server != null) {
            try {
                server.stop(0);
            } catch (Throwable ignored) {
            }
        }
        final ExecutorService p = this.pool;
        if (p != null) {
            p.shutdownNow();
        }
        this.http = null;
        this.pool = null;
        boundPort = -1;
        logger.accept("已停止");
    }

    private static Thread daemon(Runnable r) {
        final Thread t = new Thread(r, "mcp-http");
        t.setDaemon(true);
        return t;
    }

    // ==================== 会话 ====================

    private HttpSession createSession() {
        sweepIdle();
        final McpSession mcp = server.createSession();
        final HttpSession session = new HttpSession(mcp);
        sessions.put(mcp.id(), session);
        logger.accept("新会话 " + mcp.id() + "（客户端：" + mcp.clientName() + "）");
        return session;
    }

    /** 清理长时间无活动的会话（客户端异常退出时不会永久占位） */
    private void sweepIdle() {
        final long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            final HttpSession s = e.getValue();
            if (s.closed || now - s.lastSeen > 6 * 60 * 60 * 1000L) {
                server.closeSession(e.getKey());
                return true;
            }
            return false;
        });
    }

    // ==================== Streamable HTTP ====================

    private void handleStreamable(HttpExchange ex) {
        try {
            if (!authorize(ex)) {
                return;
            }
            if (!originOk(ex)) {
                return;
            }
            cors(ex);
            final String method = ex.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
            switch (method) {
                case "OPTIONS" -> sendEmpty(ex, 204);
                case "POST" -> handlePost(ex);
                case "GET" -> handleGetStream(ex, false);
                case "DELETE" -> handleDelete(ex);
                default -> sendJson(ex, 405,
                        JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "不支持的方法：" + method, null));
            }
        } catch (Throwable t) {
            fail(ex, t);
        } finally {
            ex.close();
        }
    }

    private void handlePost(HttpExchange ex) throws IOException {
        if (!acceptOk(ex)) {
            sendJson(ex, 406, JsonRpc.error(null, Mcp.E_INVALID_REQUEST,
                    "Accept 必须包含 application/json 或 text/event-stream", null));
            return;
        }
        final JsonElement message = readMessage(ex);
        if (message == null) {
            return;                                    // 已写错误响应
        }
        final String sessionHeader = header(ex, Mcp.H_SESSION_ID);
        final boolean initialize = isInitialize(message);

        HttpSession session;
        if (sessionHeader != null && !sessionHeader.isBlank()) {
            session = sessions.get(sessionHeader);
            if (session == null) {
                sendJson(ex, 404, JsonRpc.error(idOf(message), Mcp.E_INVALID_REQUEST,
                        "会话不存在或已过期：请重新 initialize", null));
                return;
            }
        } else if (initialize) {
            session = createSession();
        } else {
            sendJson(ex, 400, JsonRpc.error(idOf(message), Mcp.E_INVALID_REQUEST,
                    "缺少 " + Mcp.H_SESSION_ID + " 头：请先 initialize", null));
            return;
        }
        session.touch();

        final String protocolHeader = header(ex, Mcp.H_PROTOCOL_VERSION);
        if (protocolHeader != null && !protocolHeader.isBlank()
                && !Mcp.VERSIONS.contains(protocolHeader)) {
            sendJson(ex, 400, JsonRpc.error(idOf(message), Mcp.E_INVALID_REQUEST,
                    "不支持的协议版本：" + protocolHeader + "（支持 " + Mcp.VERSIONS + "）", null));
            return;
        }

        JsonElement response;
        try {
            response = server.handle(message, session.mcp)
                    .get(config.requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            sendJson(ex, 504, JsonRpc.error(idOf(message), Mcp.E_INTERNAL, "工具执行超时", null));
            return;
        } catch (Throwable t) {
            sendJson(ex, 500, JsonRpc.error(idOf(message), Mcp.E_INTERNAL,
                    "处理请求失败：" + rootCause(t), null));
            return;
        }

        if (response == null) {
            // 全部是通知/响应：规范要求 202 Accepted（无 body）
            ex.getResponseHeaders().set(Mcp.H_SESSION_ID, session.mcp.id());
            sendEmpty(ex, 202);
            return;
        }
        final Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", Mcp.CT_JSON + "; charset=utf-8");
        headers.set(Mcp.H_SESSION_ID, session.mcp.id());
        headers.set(Mcp.H_PROTOCOL_VERSION, session.mcp.protocolVersion());
        sendBody(ex, 200, response.toString());
    }

    /** GET /mcp：SSE 流（服务端主动消息：progress / logging / list_changed） */
    private void handleGetStream(HttpExchange ex, boolean dummy) throws IOException {
        final String sessionHeader = header(ex, Mcp.H_SESSION_ID);
        final HttpSession session = sessionHeader == null ? null : sessions.get(sessionHeader);
        if (session == null) {
            sendJson(ex, 400, JsonRpc.error(null, Mcp.E_INVALID_REQUEST,
                    "GET 流需要有效 " + Mcp.H_SESSION_ID + " 头", null));
            return;
        }
        session.touch();
        streamLoop(ex, session, null);
    }

    private void handleDelete(HttpExchange ex) {
        final String sessionHeader = header(ex, Mcp.H_SESSION_ID);
        if (sessionHeader != null) {
            final HttpSession session = sessions.remove(sessionHeader);
            if (session != null) {
                session.closed = true;
                session.wakeup();
                server.closeSession(sessionHeader);
                logger.accept("会话已终止 " + sessionHeader);
            }
        }
        sendEmpty(ex, 204);
    }

    // ==================== 旧版 HTTP+SSE（2024-11-05）====================

    private void handleLegacy(HttpExchange ex, boolean sse) {
        try {
            if (!authorize(ex)) {
                return;
            }
            if (!originOk(ex)) {
                return;
            }
            cors(ex);
            if (sse) {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "SSE 端点只接受 GET", null));
                    return;
                }
                final HttpSession session = createSession();
                streamLoop(ex, session, config.messagePath + "?sessionId=" + session.mcp.id());
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "消息端点只接受 POST", null));
                return;
            }
            final String sessionId = query(ex, "sessionId");
            final HttpSession session = sessionId == null ? null : sessions.get(sessionId);
            if (session == null) {
                sendJson(ex, 400, JsonRpc.error(null, Mcp.E_INVALID_REQUEST,
                        "未知 sessionId（请先连接 " + config.ssePath + "）", null));
                return;
            }
            final JsonElement message = readMessage(ex);
            if (message == null) {
                return;
            }
            session.touch();
            try {
                server.handle(message, session.mcp).whenComplete((response, error) -> {
                    if (response != null) {
                        session.enqueue(response.toString());
                    } else if (error != null) {
                        session.enqueue(JsonRpc.error(null, Mcp.E_INTERNAL,
                                String.valueOf(rootCause(error)), null).toString());
                    }
                });
            } catch (Throwable t) {
                session.enqueue(JsonRpc.error(null, Mcp.E_INTERNAL,
                        String.valueOf(rootCause(t)), null).toString());
            }
            sendEmpty(ex, 202);                        // 响应走 SSE 流
        } catch (Throwable t) {
            fail(ex, t);
        } finally {
            ex.close();
        }
    }

    // ==================== SSE 流循环 ====================

    /**
     * 写 SSE 流：先发可选的首个事件（旧版 endpoint），随后持续推送会话 outbox。
     * 空闲时发注释行保活（规范：客户端忽略注释）。
     */
    private void streamLoop(HttpExchange ex, HttpSession session, String firstEventData) throws IOException {
        final Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", Mcp.CT_SSE + "; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("Connection", "keep-alive");
        headers.set(Mcp.H_SESSION_ID, session.mcp.id());
        ex.sendResponseHeaders(200, 0);
        final OutputStream out = ex.getResponseBody();
        try {
            if (firstEventData != null) {
                writeSse(out, "endpoint", firstEventData, -1);
            }
            while (running && !session.closed) {
                final String message = session.outbox.poll(15, TimeUnit.SECONDS);
                if (message == null) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                writeSse(out, "message", message, session.eventId.incrementAndGet());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开：正常结束
        } finally {
            session.closed = true;
            server.closeSession(session.mcp.id());
            sessions.remove(session.mcp.id(), session);
        }
    }

    private void writeSse(OutputStream out, String event, String data, long id) throws IOException {
        final StringBuilder sb = new StringBuilder();
        if (id >= 0) {
            sb.append("id: ").append(id).append('\n');
        }
        if (event != null && !event.isEmpty()) {
            sb.append("event: ").append(event).append('\n');
        }
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ==================== HTTP 小工具 ====================

    private JsonElement readMessage(HttpExchange ex) throws IOException {
        final String contentType = header(ex, "Content-Type");
        if (contentType != null && !contentType.isBlank()
                && !contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
            sendJson(ex, 415, JsonRpc.error(null, Mcp.E_INVALID_REQUEST,
                    "只接受 application/json 请求体", null));
            return null;
        }
        final byte[] body = readAll(ex.getRequestBody());
        if (body == null) {
            sendJson(ex, 413, JsonRpc.error(null, Mcp.E_PARSE, "请求体过大", null));
            return null;
        }
        if (body.length == 0) {
            sendJson(ex, 400, JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "空请求体", null));
            return null;
        }
        try {
            final JsonElement parsed = JsonRpc.parse(new String(body, StandardCharsets.UTF_8));
            if (parsed == null) {
                sendJson(ex, 400, JsonRpc.error(null, Mcp.E_INVALID_REQUEST, "空消息", null));
            }
            return parsed;
        } catch (JsonSyntaxException e) {
            sendJson(ex, 400, JsonRpc.error(null, Mcp.E_PARSE, "JSON 解析失败：" + e.getMessage(), null));
            return null;
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > config.maxBodyBytes) {
                return null;
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private boolean authorize(HttpExchange ex) throws IOException {
        if (config.token == null || config.token.isEmpty()) {
            return true;
        }
        final String auth = header(ex, "Authorization");
        if (auth != null && auth.equals("Bearer " + config.token)) {
            return true;
        }
        final String q = query(ex, "token");
        if (q != null && q.equals(config.token)) {
            return true;
        }
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        sendJson(ex, 401, JsonRpc.error(null, Mcp.E_INVALID_REQUEST,
                "未授权：需要 Authorization: Bearer <token>", null));
        return false;
    }

    private boolean acceptOk(HttpExchange ex) {
        final String accept = header(ex, "Accept");
        if (accept == null || accept.isBlank()) {
            return true;                               // 宽容：不强制
        }
        final String lower = accept.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("application/json") || lower.contains("text/event-stream")
                || lower.contains("*/*");
    }

    /**
     * Origin 校验（MCP 规范 MUST：防 DNS rebinding）。
     *
     * <p>非浏览器客户端不带 {@code Origin} → 放行；浏览器只允许本机来源
     * （或与服务端同主机）。拒绝时返回 403，且不下发 CORS 头。</p>
     */
    private boolean originOk(HttpExchange ex) throws IOException {
        if (!config.originCheck) {
            return true;
        }
        final String origin = header(ex, "Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        final String host = hostOf(origin);
        final boolean local = host != null
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host));
        final boolean sameHost = !config.loopback() && host != null && host.equalsIgnoreCase(config.host);
        if (local || sameHost) {
            return true;
        }
        sendJson(ex, 403, JsonRpc.error(null, Mcp.E_INVALID_REQUEST,
                "Origin 不被允许：" + origin, null));
        return false;
    }

    private static String hostOf(String origin) {
        try {
            return java.net.URI.create(origin).getHost();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** CORS：回显已通过 Origin 校验的来源（不用 *，避免跨站读取） */
    private void cors(HttpExchange ex) {
        if (!config.cors) {
            return;
        }
        final Headers headers = ex.getResponseHeaders();
        final String origin = header(ex, "Origin");
        if (origin != null && !origin.isBlank()) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers",
                "Content-Type, Authorization, " + Mcp.H_SESSION_ID + ", "
                        + Mcp.H_PROTOCOL_VERSION + ", " + Mcp.H_LAST_EVENT_ID);
        headers.set("Access-Control-Expose-Headers", Mcp.H_SESSION_ID);
        headers.set("Access-Control-Max-Age", "86400");
    }

    private static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    private static boolean isInitialize(JsonElement message) {
        if (message == null || !message.isJsonObject()) {
            return false;
        }
        return Mcp.M_INITIALIZE.equals(JsonRpc.methodOf(message.getAsJsonObject()));
    }

    private static JsonElement idOf(JsonElement message) {
        if (message != null && message.isJsonObject()) {
            final JsonObject o = message.getAsJsonObject();
            if (o.has("id")) {
                return o.get("id");
            }
        }
        return null;
    }

    private static String query(HttpExchange ex, String key) {
        final String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String pair : raw.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange ex, int status, JsonObject body) {
        try {
            sendBody(ex, status, body.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void sendBody(HttpExchange ex, int status, String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", Mcp.CT_JSON + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange ex, int status) {
        try {
            ex.sendResponseHeaders(status, -1);
        } catch (Throwable ignored) {
        }
    }

    private void fail(HttpExchange ex, Throwable t) {
        logger.accept("请求处理异常：" + t);
        try {
            sendJson(ex, 500, JsonRpc.error(null, Mcp.E_INTERNAL,
                    "服务器内部错误：" + rootCause(t), null));
        } catch (Throwable ignored) {
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    // ==================== 会话对象 ====================

    /** HTTP 会话：MCP 会话 + SSE 出站队列 */
    private final class HttpSession {

        final McpSession mcp;
        final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
        final AtomicLong eventId = new AtomicLong();
        volatile boolean closed;
        volatile long lastSeen = System.currentTimeMillis();

        HttpSession(McpSession mcp) {
            this.mcp = mcp;
            // 服务端主动通知（progress/logging/list_changed）→ 该会话的 SSE 队列
            mcp.notifier(notification -> outbox.offer(notification.toString()));
        }

        void touch() {
            lastSeen = System.currentTimeMillis();
        }

        void enqueue(String json) {
            outbox.offer(json);
        }

        void wakeup() {
            outbox.offer("");
        }
    }
}
