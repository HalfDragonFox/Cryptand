package com.hdf.cryptand.circuitsimulation.core.extensions;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * UDP 外部接口扩展（2026-08-30：核心扩展组件外部接口之一）。行协议按 UDP 包
 * 传输（每包一条命令；回发写回最后发送方地址）。纯 Java 零 MC 依赖。
 */
public class UdpEndpointExtension extends EndpointExtension {

    private final int port;
    private volatile DatagramSocket socket;
    private volatile boolean running;
    private Thread recvThread;
    private volatile InetAddress lastAddr;
    private volatile int lastPort;
    private static final int MAX_PACKET = 65507;

    public UdpEndpointExtension(int port) {
        super("udp-" + port);
        this.port = port;
    }

    public int port() { return port; }

    @Override
    public void start() {
        if (running) return;
        try {
            socket = new DatagramSocket(port);
            running = true;
            recvThread = new Thread(this::recvLoop, "cryptand-core-udp-" + port);
            recvThread.setDaemon(true);
            recvThread.start();
        } catch (java.io.IOException e) {
            running = false;
        }
    }

    private void recvLoop() {
        byte[] buf = new byte[MAX_PACKET];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                lastAddr = p.getAddress();
                lastPort = p.getPort();
                String line = new String(p.getData(), 0, p.getLength(),
                        StandardCharsets.UTF_8);
                onLineReceived(line);
            } catch (java.io.IOException e) {
                if (running) break;
            }
        }
    }

    @Override
    protected void sendLine(String line) {
        try {
            if (socket != null && lastAddr != null) {
                byte[] data = line.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, lastAddr, lastPort));
            }
        } catch (java.io.IOException ignored) {
        }
    }

    @Override
    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }
}
