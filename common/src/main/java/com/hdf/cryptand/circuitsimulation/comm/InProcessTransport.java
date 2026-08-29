package com.hdf.cryptand.circuitsimulation.comm;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内消息传输（2026-08-22 通信组件：同 JVM 对象投递，可靠）。
 * <p>
 * 通过 {@link #pair()} 创建互连的一对（client/server）。发送 = 同步投递到对端
 * 接收回调；{@link #requestReply} 单飞行：发送后阻塞等待对端回帧（同步栈内回调
 * 设置待响应，返回后 wait 立即满足）。线程安全由调用方串行保证。
 */
public final class InProcessTransport implements CommTransport {

    private volatile InProcessTransport peer;
    private volatile CommTransport.CommReceiver receiver;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object replyLock = new Object();
    private volatile byte[] pendingReply;
    private volatile boolean waitingForReply;
    private static final long TIMEOUT_MS = 10000;

    /** 创建互连的一对（[0]=server端, [1]=client端） */
    public static InProcessTransport[] pair() {
        InProcessTransport a = new InProcessTransport();
        InProcessTransport b = new InProcessTransport();
        a.peer = b;
        b.peer = a;
        return new InProcessTransport[]{a, b};
    }

    InProcessTransport() {
    }

    @Override
    public String name() { return "in-process"; }

    @Override
    public boolean reliable() { return true; }

    @Override
    public void open(CommTransport.CommReceiver r) {
        this.receiver = r;
    }

    @Override
    public void send(byte[] payload) {
        if (closed.get()) return;
        InProcessTransport p = peer;
        if (p != null) p.deliver(payload); // 投递给对端
    }

    /** 对端投递到本端：若本端正在等待响应（requestReply）→ 喂给等待者；否则交给接收器 */
    void deliver(byte[] payload) {
        synchronized (replyLock) {
            if (waitingForReply) {
                pendingReply = payload;
                replyLock.notifyAll();
                return;
            }
        }
        CommTransport.CommReceiver r = receiver;
        if (r != null) {
            try { r.onReceive(payload); } catch (Throwable ignored) { }
        }
    }

    @Override
    public byte[] requestReply(byte[] payload) {
        if (closed.get()) throw new IllegalStateException("transport closed");
        synchronized (replyLock) {
            pendingReply = null;
            waitingForReply = true;
        }
        try {
            send(payload); // → 对端处理并回帧 → 对端.send → 本端.deliver（喂等待者）
            synchronized (replyLock) {
                long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                while (pendingReply == null) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) throw new IllegalStateException("in-process reply timeout");
                    try { replyLock.wait(remain); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (replyLock) {
                pendingReply = new byte[0]; // 唤醒等待者
                replyLock.notifyAll();
            }
        }
    }
}