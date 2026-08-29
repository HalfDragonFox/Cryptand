package com.hdf.cryptand.circuitsimulation.comm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP 传输（2026-08-22 通信组件：可靠流，length-prefix 帧）。
 * <p>
 * 两种角色：
 * <ul>
 *   <li><b>服务端</b> {@link #server(int)}：监听本地端口，接受第一条连接，接收
 *       CommHub 请求 → 回复用 {@link #send}；</li>
 *   <li><b>客户端</b> {@link #client(String,int)}：连接服务端，{@link #requestReply}
 *       发送请求并阻塞等待响应（单飞行）。</li>
 * </ul>
 * 帧 = 4 字节长度（大端）+ 载荷。
 */
public final class TcpTransport implements CommTransport {

    private final boolean serverMode;
    private final ServerSocket server;
    private volatile Socket socket;
    private volatile CommReceiver receiver;
    private volatile Thread ioThread;
    private final Object replyLock = new Object();
    private volatile byte[] pendingReply;
    private volatile boolean waitingForReply;
    private static final int TIMEOUT_MS = 15000;

    private TcpTransport(boolean serverMode, ServerSocket server, Socket socket) {
        this.serverMode = serverMode;
        this.server = server;
        this.socket = socket;
    }

    /** 服务端：监听本地端口（阻塞 accept 一条连接，接收线程处理） */
    public static TcpTransport server(int port) throws IOException {
        return new TcpTransport(true, new ServerSocket(port), null);
    }

    /** 客户端：连接到服务端 */
    public static TcpTransport client(String host, int port) throws IOException {
        Socket s = new Socket(host, port);
        s.setTcpNoDelay(true);
        return new TcpTransport(false, null, s);
    }

    @Override
    public String name() { return "tcp"; }

    /** 服务端实际监听端口（port=0 自动分配后用；客户端模式抛异常） */
    public int localPort() throws IOException {
        ServerSocket s = server;
        if (s == null) throw new IOException("not a server");
        return s.getLocalPort();
    }

    @Override
    public boolean reliable() { return true; }

    @Override
    public void open(CommReceiver receiver) {
        this.receiver = receiver;
        Thread t = new Thread(this::ioLoop, "comm-tcp-" + (serverMode ? "server" : "client"));
        t.setDaemon(true);
        ioThread = t;
        t.start();
    }

    /** IO 主循环：服务端 accept+读；客户端读 */
    private void ioLoop() {
        try {
            if (serverMode) {
                socket = server.accept(); // 阻塞直到第一条连接
            }
            socket.setTcpNoDelay(true);
            readLoop();
        } catch (Throwable ignored) {
        }
    }

    private void readLoop() throws IOException {
        Socket s = socket;
        if (s == null) return;
        DataInputStream in = new DataInputStream(s.getInputStream());
        while (!s.isClosed()) {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                break;
            }
            if (len < 0 || len > 1 << 24) break;
            byte[] payload = new byte[len];
            in.readFully(payload);
            // 客户端在等待响应（requestReply）→ 喂给等待者；否则（服务端）交给接收器
            synchronized (replyLock) {
                if (waitingForReply) {
                    pendingReply = payload;
                    replyLock.notifyAll();
                    continue;
                }
            }
            CommReceiver r = receiver;
            if (r != null) {
                try { r.onReceive(payload); } catch (Throwable ignored) { }
            }
        }
    }

    @Override
    public void send(byte[] payload) throws IOException {
        writeFrame(waitSocket(), payload);
    }

    @Override
    public byte[] requestReply(byte[] payload) throws IOException {
        writeFrame(waitSocket(), payload);
        synchronized (replyLock) { waitingForReply = true; }
        try {
            synchronized (replyLock) {
                long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                while (pendingReply == null) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) throw new IOException("tcp reply timeout");
                    try { replyLock.wait(remain); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
                byte[] r = pendingReply;
                pendingReply = null;
                return r;
            }
        } finally {
            synchronized (replyLock) { waitingForReply = false; replyLock.notifyAll(); }
        }
    }

    private Socket waitSocket() throws IOException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (socket == null || socket.isClosed()) {
            if (System.currentTimeMillis() > deadline) throw new IOException("tcp no socket");
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        return socket;
    }

    private static void writeFrame(Socket s, byte[] payload) throws IOException {
        if (s == null || s.isClosed()) throw new IOException("tcp socket closed");
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            throw new IOException("tcp closed");
        }
        if (len < 0 || len > 1 << 24) throw new IOException("bad tcp frame len " + len);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return payload;
    }

    @Override
    public void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
    }
}