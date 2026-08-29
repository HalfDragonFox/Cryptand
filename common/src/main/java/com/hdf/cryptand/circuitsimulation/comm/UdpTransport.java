package com.hdf.cryptand.circuitsimulation.comm;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * UDP 传输（2026-08-22 通信组件：无连接数据报，不可靠）。
 * <p>
 * 一帧 = 一个 datagram（载荷直接打包，帧小疑义由协议长度自校验）。两种角色：
 * <ul>
 *   <li><b>服务端</b> {@link #server(int)}：绑定本地端口，接收请求 → 回复到
 *       【最近一次收到请求的来源地址】（单客户端/轮询场景简化）；</li>
 *   <li><b>客户端</b> {@link #client(String,int)}：发送到目标 + 恒定来源，
 *       {@link #requestReply} 发送并等待一帧响应（单飞行，带超时）。</li>
 * </ul>
 * 不可靠：可能丢包/乱序，不保证交付（可靠语义需上层重试）。
 */
public final class UdpTransport implements CommTransport {

    private final DatagramSocket socket;
    private final InetSocketAddress target;
    private volatile CommReceiver receiver;
    private volatile InetSocketAddress lastRemote;
    private volatile Thread ioThread;
    private final Object replyLock = new Object();
    private volatile DatagramPacket pending;
    private static final int TIMEOUT_MS = 15000;

    private UdpTransport(DatagramSocket socket, InetSocketAddress target) {
        this.socket = socket;
        this.target = target;
    }

    /** 服务端：绑定本地端口（接收任意来源；回复最近来源） */
    public static UdpTransport server(int port) throws IOException {
        DatagramSocket s = new DatagramSocket(port);
        s.setSoTimeout(TIMEOUT_MS);
        return new UdpTransport(s, null);
    }

    /** 客户端：固定目标（发送/接收都经本 socket） */
    public static UdpTransport client(String host, int port) throws IOException {
        DatagramSocket s = new DatagramSocket();
        s.setSoTimeout(TIMEOUT_MS);
        return new UdpTransport(s, new InetSocketAddress(InetAddress.getByName(host), port));
    }

    @Override
    public String name() { return "udp"; }

    /** 本地端口（server(0) 自动分配后用） */
    public int localPort() {
        return socket.getLocalPort();
    }

    @Override
    public boolean reliable() { return false; }

    @Override
    public void open(CommReceiver receiver) {
        this.receiver = receiver;
        Thread t = new Thread(this::recvLoop, "comm-udp");
        t.setDaemon(true);
        ioThread = t;
        t.start();
    }

    private void recvLoop() {
        byte[] buf = new byte[65535];
        while (!socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p); // 阻塞直到一包 / 超时
                byte[] payload = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), payload, 0, p.getLength());
                boolean isClient = target != null; // 客户端只收响应；服务端收请求
                if (isClient) {
                    // 客户端：收到的帧即响应 → 喂给 requestReply 等待者
                    synchronized (replyLock) {
                        if (pending == null) {
                            pending = new DatagramPacket(payload, payload.length);
                            replyLock.notifyAll();
                        }
                    }
                } else {
                    // 服务端：记录来源 + 分发请求到共同端口接收器
                    lastRemote = (InetSocketAddress) p.getSocketAddress();
                    CommReceiver r = receiver;
                    if (r != null) {
                        try { r.onReceive(payload); } catch (Throwable ignored) { }
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    @Override
    public void send(byte[] payload) throws IOException {
        InetSocketAddress to = target != null ? target : lastRemote;
        if (to == null) throw new IOException("udp no target");
        socket.send(new DatagramPacket(payload, payload.length, to));
    }

    @Override
    public byte[] requestReply(byte[] payload) throws IOException {
        InetSocketAddress to = target;
        if (to == null) throw new IOException("udp client needs target");
        synchronized (replyLock) { pending = null; }
        socket.send(new DatagramPacket(payload, payload.length, to));
        synchronized (replyLock) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (pending == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new IOException("udp reply timeout");
                try { replyLock.wait(remain); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            DatagramPacket p = pending;
            pending = null;
            byte[] r = new byte[p.getLength()];
            System.arraycopy(p.getData(), p.getOffset(), r, 0, p.getLength());
            return r;
        }
    }

    @Override
    public void close() {
        socket.close();
        synchronized (replyLock) { pending = new DatagramPacket(new byte[0], 0); replyLock.notifyAll(); }
    }
}