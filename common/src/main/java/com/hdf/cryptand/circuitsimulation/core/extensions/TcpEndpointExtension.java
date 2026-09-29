package com.hdf.cryptand.circuitsimulation.core.extensions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TCP 外部接口扩展（2026-08-30 用户：核心扩展组件包括外部接口 TCP，统一交互
 * 接口，可接入核心实例实现前端接入——EDA 前端经 TCP 连接核心实例）。
 * <p>
 * 行协议（见 {@link EndpointExtension}）：每行一条命令；核心结果/事件经
 * {@link #sendLine} 写回全部已连接客户端。多客户端并发支持（每连接一线程）。
 * 纯 Java（零 MC 依赖），可跨程序（独立进程 EDA 前端连本端口）。
 */
public class TcpEndpointExtension extends EndpointExtension {

    private final int port;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Socket, PrintWriter> writers = new ConcurrentHashMap<>();
    private Thread acceptThread;

    public TcpEndpointExtension(int port) {
        super("tcp-" + port);
        this.port = port;
    }

    /** 监听端口 */
    public int port() { return port; }

    /** 当前连接数（诊断） */
    public int clientCount() { return clients.size(); }

    @Override
    public void start() {
        if (running) return;
        try {
            server = new ServerSocket(port);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "cryptand-core-tcp-" + port);
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            running = false;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                clients.add(s);
                Thread t = new Thread(() -> handleClient(s), "cryptand-core-tcp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) break;
            }
        }
    }

    private void handleClient(Socket s) {
        try {
            PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    s.getOutputStream(), StandardCharsets.UTF_8), true);
            writers.put(s, w);
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = r.readLine()) != null) {
                onLineReceived(line);
            }
        } catch (IOException ignored) {
        } finally {
            writers.remove(s);
            clients.remove(s);
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    protected void sendLine(String line) {
        for (PrintWriter w : writers.values()) {
            try {
                w.println(line);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Socket s : clients) {
            try { s.close(); } catch (IOException ignored) { }
        }
        clients.clear();
        writers.clear();
    }
}
