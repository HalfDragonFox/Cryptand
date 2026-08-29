package com.hdf.cryptand.circuitsimulation.comm;

/**
 * 通信端点（2026-08-22 通信组件：一种传输 + 共享编解码）。
 * <p>
 * 封装单一传输（InProcess / TCP / UDP）为 CommHub 的一个接入点：
 * {@link #start()} 打开传输并把接收帧交给枢纽统一转发。
 */
public final class CommEndpoint {

    private final String name;
    private final CommTransport transport;
    private final CommCodec codec;
    private final CommHub hub;

    CommEndpoint(String name, CommTransport transport, CommCodec codec, CommHub hub) {
        this.name = name;
        this.transport = transport;
        this.codec = codec;
        this.hub = hub;
    }

    public String name() { return name; }

    public CommTransport transport() { return transport; }

    public CommCodec codec() { return codec; }

    /** 启动传输接收线程 */
    void start() {
        try {
            transport.open(payload -> hub.onFrame(this, payload));
        } catch (Throwable ignored) {
        }
    }

    public void close() {
        try { transport.close(); } catch (Throwable ignored) { }
    }

    @Override
    public String toString() {
        return "CommEndpoint{" + name + ", " + transport.name() + "}";
    }
}