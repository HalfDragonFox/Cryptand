package com.hdf.cryptand.soc.board;

/**
 * ===== 屏幕帧率限制器（common，纯 Java 零 MC，2026-09-28）=====
 *
 * <p>每台机器一份：服务器按这个上限回帧，默认 60 fps。它是"客户端拉取"模型的成本闸 ——
 * 拉得再急，服务器最多按 fps 回。</p>
 *
 * <p>口径：{@code fps <= 0} = 不限速（诊断/离线基准用）；时间源由调用方传入（纳秒），
 * 因此本类可以被离线闸门确定性地驱动，不读系统时钟。</p>
 */
public final class FrameRateLimiter {

    /** 默认上限：60 帧/秒 */
    public static final double DEFAULT_FPS = 60.0;

    private double fps;
    private long intervalNanos;
    private long nextAllowedNanos;
    private long dropped;
    private long sent;
    private boolean started;

    public FrameRateLimiter(double fps) {
        setFps(fps);
        this.nextAllowedNanos = 0L;
        this.started = false;
    }

    /** 允许则返回 true（并记一次发送）；未到点返回 false（并记一次丢弃）。 */
    public boolean tryAcquire(long nowNanos) {
        if (!started) {
            started = true;
            nextAllowedNanos = nowNanos + intervalNanos;
            sent++;
            return true;
        }
        if (fps <= 0.0 || nowNanos >= nextAllowedNanos) {
            nextAllowedNanos = nowNanos + intervalNanos;
            sent++;
            return true;
        }
        dropped++;
        return false;
    }

    /** 重置时间基准（退出重进/传送后调用一次，避免"上一轮遗留的 nextAllowed"延迟第一帧）。 */
    public void reset(long nowNanos) {
        started = false;
        nextAllowedNanos = nowNanos;
    }

    public double fps() {
        return fps;
    }

    public void setFps(double fps) {
        this.fps = fps;
        this.intervalNanos = fps <= 0.0 ? 0L : (long) (1_000_000_000.0 / fps);
    }

    public long intervalNanos() {
        return intervalNanos;
    }

    public long sent() {
        return sent;
    }

    public long dropped() {
        return dropped;
    }
}
