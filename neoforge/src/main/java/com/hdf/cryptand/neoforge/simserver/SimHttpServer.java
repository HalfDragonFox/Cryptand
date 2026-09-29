package com.hdf.cryptand.neoforge.simserver;

/**
 * 本地 HTTP 仿真接口（测试用）：让 test/ 前端在 MC 启动后直接调用模组内核仿真。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /health   → { ok, mod, kernel, port }（连接检测）</li>
 *   <li>POST /simulate → 收前端网表 JSON，调 {@link KernelSimulator} 用内核求解，
 *       返回节点电压/元件导线结果/波形。</li>
 * </ul>
 *
 * <p>端口：系统属性 <code>-Dcryptand.simport=12787</code>（默认 12787），只监听
 * 127.0.0.1（不回环外开放）。服务随 ServerStartedEvent 启动、ServerStoppingEvent 停止。
 *
 * <p>实现：JDK 自带 com.sun.net.httpserver，无额外依赖；内核求解器为纯数据，
 * 在 HTTP 工作线程上运行安全（不触碰 Minecraft 世界对象）。
 */

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hdf.cryptand.circuitsimulation.core.SimulationCoreRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class SimHttpServer {

    private static final SimHttpServer INSTANCE = new SimHttpServer();

    public static SimHttpServer get() { return INSTANCE; }

    private static final int DEFAULT_PORT = 12787;

    private HttpServer server;
    private ExecutorService executor;
    private volatile int port = DEFAULT_PORT;

    // ===== 会话管理（2026-08-22 用户架构：多 EDA 客户端隔离运行 + 离线清缓存） =====
    // 每个 EDA 客户端会话映射一个独立 SimulationCore 实例（SimulationCoreRegistry）；
    // 会话离线（/close 显式释放 或 心跳超时）→ 释放该核心实例（stop + 清缓存）。
    /** 会话最后活动时间（ms）；每次 /simulate /health 更新 */
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    /** 心跳超时：超过此时长无任何请求 → 判定离线 → 释放核心清缓存 */
    private static final long SESSION_TIMEOUT_MS = 5 * 60_000L;
    /** 超时扫描间隔 */
    private static final long SWEEP_INTERVAL_MS = 60_000L;
    private ScheduledExecutorService sweeper;

    private SimHttpServer() {}

    public boolean isRunning() { return server != null; }

    public synchronized void start() {
        if (server != null) return;
        try {
            port = Integer.getInteger("cryptand.simport", DEFAULT_PORT);
            String host = System.getProperty("cryptand.simport.host", "0.0.0.0");   // 默认局域网可访问；可用 127.0.0.1 限制本机
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/health", this::handleHealth);
            server.createContext("/simulate", this::handleSimulate);
            server.createContext("/close", this::handleClose);
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "cryptand-simserver");
                t.setDaemon(true);
                // 2026-08-17：低优先级——大电路仿真不抢占 MC 渲染/Server 线程 CPU，
                // 避免页面"卡住"（实际是 MC 掉帧 + 前端等待大响应）
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            // 会话超时扫描：定期释放离线 EDA 客户端的核心实例（清缓存）
            sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cryptand-session-sweeper");
                t.setDaemon(true);
                return t;
            });
            sweeper.scheduleWithFixedDelay(this::sweepSessions,
                    SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
            org.apache.logging.log4j.LogManager.getLogger("Cryptand")
                    .info("[SimServer] 内核仿真接口已启动: http://127.0.0.1:{}/ (health/simulate/close)", port);
        } catch (IOException e) {
            org.apache.logging.log4j.LogManager.getLogger("Cryptand")
                    .error("[SimServer] 启动失败: {}", e.toString());
            server = null;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        // 全部会话核心实例释放（stop + 清缓存）
        try {
            SimulationCoreRegistry.releaseAll();
        } catch (Throwable ignored) {
        }
        sessions.clear();
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("mod", "Cryptand");
        o.addProperty("kernel", "Network + RealMnaSolver/ComplexMnaSolver");
        o.addProperty("port", port);
        respond(ex, 200, o.toString());
    }

    private void handleSimulate(HttpExchange ex) throws IOException {
        // CORS 预检：浏览器跨端口 POST+JSON 会先发 OPTIONS，必须返回 200 + CORS 头
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 200, "{}");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method not allowed\"}");
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        JsonObject req;
        try {
            req = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Throwable t) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad json\"}");
            return;
        }
        JsonObject res = KernelSimulator.simulate(req);
        // 会话跟踪：请求带 session（无 → "default"）；EDA 无状态每次全量提交，
        // 但会话核心实例（SimulationCoreRegistry）作为生命周期载体——离线时
        // 释放销毁其注册表/缓存，与其他客户端隔离。
        try {
            String session = sessionOf(req);
            sessions.put(session, System.currentTimeMillis());
            SimulationCoreRegistry.acquire(session);
            res.addProperty("session", session);
        } catch (Throwable ignored) {
        }
        respond(ex, 200, res.toString());
    }

    /** 请求中的会话 id（无 → "default"） */
    private static String sessionOf(JsonObject req) {
        try {
            if (req != null && req.has("session")
                    && !req.get("session").isJsonNull()) {
                String s = req.get("session").getAsString();
                if (s != null && !s.isBlank()) return s.trim();
            }
        } catch (Throwable ignored) {
        }
        return "default";
    }

    /** /close：显式释放会话核心实例（前端关闭/刷新时调用）→ 清缓存 */
    private void handleClose(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 200, "{}");
            return;
        }
        String session = "default";
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            JsonObject o = JsonParser.parseString(
                    new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            session = sessionOf(o);
        } catch (Throwable ignored) {
        }
        sessions.remove(session);
        SimulationCoreRegistry.release(session); // 释放核心实例：stop + 清缓存
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("released", session);
        respond(ex, 200, res.toString());
    }

    /** 心跳超时扫描：无请求超时的会话 → 释放核心实例（清缓存） */
    private void sweepSessions() {
        try {
            long now = System.currentTimeMillis();
            List<String> dead = new ArrayList<>();
            for (var en : sessions.entrySet()) {
                if (now - en.getValue() >= SESSION_TIMEOUT_MS) dead.add(en.getKey());
            }
            for (String s : dead) {
                sessions.remove(s);
                SimulationCoreRegistry.release(s);
                org.apache.logging.log4j.LogManager.getLogger("Cryptand")
                        .info("[SimServer] session offline (timeout), released core: {}", s);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void respond(HttpExchange ex, int code, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }
}
