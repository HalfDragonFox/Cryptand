package com.hdf.cryptand.mcp.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.hdf.cryptand.mcp.JsonRpc;
import com.hdf.cryptand.mcp.Mcp;
import com.hdf.cryptand.mcp.McpServer;
import com.hdf.cryptand.mcp.McpSession;

/**
 * stdio 传输（MCP 经典的"本地子进程"模式）。
 *
 * <p>协议：stdin 每行一条 JSON-RPC 消息（NDJSON），stdout 每行一条响应。
 * <b>日志绝不能写 stdout</b>（会污染协议流）—— 需要日志时写 stderr。</p>
 *
 * <p>用途：把 Cryptand 的 MCP 能力以独立进程方式接入（如无 GUI 的工具进程，
 * 或客户端用 {@code command} 直接拉起）。MC 内嵌场景请用
 * {@link HttpMcpTransport}（游戏进程不能作为别人的子进程）。</p>
 */
public final class StdioTransport implements McpTransport {

    private final McpServer server;
    private final McpSession session;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private Thread reader;
    private final OutputStream out = System.out;

    public StdioTransport(McpServer server) {
        this.server = server;
        this.session = server.createSession();
        this.session.notifier(notification -> writeLine(notification.toString()));
    }

    @Override
    public String name() {
        return "stdio";
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        stopRequested.set(false);
        reader = new Thread(this::loop, "mcp-stdio");
        reader.setDaemon(true);
        reader.start();
    }

    private void loop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (!stopRequested.get() && (line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (Throwable ex) {
            System.err.println("[mcp/stdio] 读取失败：" + ex);
        } finally {
            running.set(false);
        }
    }

    private void handleLine(String line) {
        JsonElement message;
        try {
            message = JsonRpc.parse(line);
        } catch (JsonSyntaxException ex) {
            writeLine(JsonRpc.error(null, Mcp.E_PARSE, "JSON 解析失败：" + ex.getMessage(), null).toString());
            return;
        }
        try {
            server.handle(message, session).whenComplete((response, error) -> {
                if (error != null) {
                    writeLine(JsonRpc.error(null, Mcp.E_INTERNAL, String.valueOf(error), null).toString());
                    return;
                }
                if (response != null) {
                    writeLine(response.toString());
                }
            });
        } catch (Throwable ex) {
            writeLine(JsonRpc.error(null, Mcp.E_INTERNAL, String.valueOf(ex), null).toString());
        }
    }

    private synchronized void writeLine(String json) {
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stop() {
        stopRequested.set(true);
        running.set(false);
    }

    @Override
    public boolean running() {
        return running.get();
    }

    @Override
    public String endpoint() {
        return "stdio（NDJSON）";
    }
}
