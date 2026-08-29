package com.hdf.cryptand.simserver;

/**
 * 独立内核仿真服务端（不依赖 Minecraft）。
 *
 * <p>把 Cryptand 自研电力引擎（common 模块 {@code com.hdf.cryptand.circuitsimulation}，纯 Java）
 * 编译成一个可独立运行的 HTTP 服务，替代「必须启动 MC 才能仿真」的流程：
 * <ul>
 *   <li>GET  /health   → { ok, mod, kernel, port }（连接检测，供前端「连接」按钮）</li>
 *   <li>POST /simulate → 收前端网表 JSON，调 {@link KernelSimulator} 用内核求解，
 *       返回节点电压 / 元件导线电流功率温度能量 / 探针波形。</li>
 * </ul>
 *
 * <p>与模组内 SimHttpServer 协议完全一致，前端无需改动；仅用于验证内核仿真正确性。
 *
 * <p>用法：{@code java -cp "out;lib\gson-2.11.0.jar" com.hdf.cryptand.simserver.KernelServer [port]}
 * <br>端口默认 12787，可命令行参数指定或用 {@code -Dcryptand.simport=port}。
 */

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class KernelServer {

    private static final int DEFAULT_PORT = 12787;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (System.getProperty("cryptand.simport") != null) {
            port = Integer.getInteger("cryptand.simport", DEFAULT_PORT);
        }
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) { }
        }
        // 监听地址：默认 0.0.0.0（局域网多用户可访问）；可 -Dcryptand.simport.host=127.0.0.1 限制本机
        String host = System.getProperty("cryptand.simport.host", "0.0.0.0");

        final int finalPort = port;
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cryptand-kernel-server");
            t.setDaemon(true);
            return t;
        });

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/health", ex -> handleHealth(ex, finalPort));
            server.createContext("/simulate", KernelServer::handleSimulate);
            server.setExecutor(executor);
            server.start();
            System.out.println("[KernelServer] 独立内核仿真服务已启动: http://" + host + ":" + port + "/ (health/simulate)");
            System.out.println("[KernelServer] 内核: Network + RealMnaSolver/ComplexMnaSolver (com.hdf.cryptand.circuitsimulation)");
            System.out.println("[KernelServer] 就绪。Ctrl+C 退出。");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[KernelServer] 正在停止 ...");
                server.stop(0);
                executor.shutdownNow();
            }));

            // 主线程挂起等待
            Thread.currentThread().join();
        } catch (IOException e) {
            System.err.println("[KernelServer] 启动失败: " + e);
            System.exit(1);
        } catch (InterruptedException e) {
            // 关闭
        }
    }

    private static void handleHealth(HttpExchange ex, int port) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("mod", "Cryptand-standalone");
        o.addProperty("kernel", "Network + RealMnaSolver/ComplexMnaSolver");
        o.addProperty("port", port);
        respond(ex, 200, o.toString());
    }

    private static void handleSimulate(HttpExchange ex) throws IOException {
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
        respond(ex, 200, res.toString());
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
