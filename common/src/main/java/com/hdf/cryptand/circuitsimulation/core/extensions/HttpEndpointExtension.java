package com.hdf.cryptand.circuitsimulation.core.extensions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 外部接口扩展（2026-08-30 补充，参考 .ai_cache/architecture 的
 * SimHttpServer(12787)——EDA 前端经 HTTP 互通；本扩展把同一行协议经 HTTP
 * 暴露（POST /command，body = 命令，响应 = 输出行）。
 * <p>
 * 纯 JDK HttpServer（零 MC 依赖，可跨程序）；与 TCP/UDP/消息总线端点同协议，
 * 统一交互接口（EndpointExtension）。EDA 画布 / 任意 HTTP 客户端可接入。
 */
public class HttpEndpointExtension extends EndpointExtension {

    private final int port;
    private volatile HttpServer server;
    private volatile boolean running;
    /** HTTP 请求内收集核心输出（sendLine 写此缓冲，请求结束时回发） */
    private final ThreadLocal<StringBuilder> responseBuf = new ThreadLocal<>();

    public HttpEndpointExtension(int port) {
        super("http-" + port);
        this.port = port;
    }

    public int port() { return port; }

    @Override
    public void start() {
        if (running) return;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/command", this::handleCommand);
            server.createContext("/health", this::handleHealth);
            server.createContext("/", this::handleInfo);
            server.setExecutor(null); // 默认 executor（每请求线程）
            server.start();
            running = true;
        } catch (IOException e) {
            running = false;
        }
    }

    /** POST /command：body = 一条行协议命令；响应 = 核心输出行（sendLine 缓冲） */
    private void handleCommand(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "POST only\n");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            StringBuilder buf = new StringBuilder();
            responseBuf.set(buf);
            try {
                onLineReceived(body);
            } finally {
                responseBuf.remove();
            }
            respond(ex, 200, buf.length() == 0 ? "ok\n" : buf.toString());
        } catch (Throwable t) {
            respond(ex, 500, "err " + t + "\n");
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "ok " + id() + "\n");
    }

    private void handleInfo(HttpExchange ex) throws IOException {
        respond(ex, 200, "cryptand core http endpoint " + id()
                + "\nusage: POST /command {line}\n");
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    protected void sendLine(String line) {
        StringBuilder buf = responseBuf.get();
        if (buf != null) {
            buf.append(line).append('\n');
        }
    }

    @Override
    public void stop() {
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
