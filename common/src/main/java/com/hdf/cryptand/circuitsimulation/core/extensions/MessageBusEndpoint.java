package com.hdf.cryptand.circuitsimulation.core.extensions;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 进程内消息总线扩展（2026-08-30：核心扩展组件外部接口之消息——本地前端
 * （MC 适配层/测试）经本端点直连核心实例，不经网络）。提交消息入队，消费线程
 * 逐条处理（与 TCP/UDP 同协议）。纯 Java 零 MC 依赖。
 */
public class MessageBusEndpoint extends EndpointExtension {

    private final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private Thread worker;
    /** 最近一条核心→外部输出（测试/诊断轮询） */
    private volatile String lastOut;

    public MessageBusEndpoint() {
        super("message-bus");
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::consumeLoop, "cryptand-core-messagebus");
        worker.setDaemon(true);
        worker.start();
    }

    private void consumeLoop() {
        while (running) {
            try {
                String line = inbox.take();
                if (!running) break;
                onLineReceived(line);
            } catch (InterruptedException ie) {
                if (!running) break;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 本地前端提交命令（MC 适配/测试用；同 TCP/UDP 行协议） */
    public void post(String line) {
        if (line != null) inbox.offer(line);
    }

    /** 最近一条核心输出（测试/诊断轮询；null = 尚无） */
    public String lastOut() { return lastOut; }

    @Override
    protected void sendLine(String line) {
        this.lastOut = line;
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        inbox.clear();
    }
}
