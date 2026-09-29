package com.hdf.cryptand.soc.board;

/**
 * ===== 请求令牌桶（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户定案：「服务器可以设置**单个请求速率上限**以及**客户端请求速率上限**，
 * 总之可以参考我们**外设船舵（这一类）**的设计」。</p>
 *
 * <p>设计照搬项目既有的限流范式（`disk-iops-throttle-design-2026-09-25.md` 的令牌桶）：</p>
 * <ul>
 *   <li><b>令牌桶</b>：`burst` 允许短促爆发（真实设备都有队列深度，一刀切间隔会让正常突发被误伤）；
 *       补充按**经过的时间**算，不依赖 tick 回调（可被任何线程安全地驱动）。</li>
 *   <li><b>给定时钟、绝不 sleep</b>：项目铁律是主线程纯同步不阻塞 ——
 *       所以这里只回答"放不放行"和"还差多久"（{@link #waitNanosFor}），
 *       要不要等、怎么等由调用方决定（服务端帧请求的选择是：直接丢弃，客户端下一 tick 会再来）。</li>
 *   <li><b>空闲回补但不超过 burst</b>：挂机一小时后回来不能一次刷出一小时的量。</li>
 *   <li>时钟回退（`System.nanoTime` 理论上单调，但假时钟/测试会造）⇒ 不补令牌，不倒扣。</li>
 * </ul>
 *
 * <p>⚠ "无限制"用**不装桶**表达，不要用速率 0 或极大值 —— 构造期对非法值明确抛错，
 * 避免"配错了但看起来在工作"。</p>
 */
public final class RequestTokenBucket {

    private final double ratePerSecond;
    private final double burst;
    private double tokens;
    private long lastNanos;
    private boolean started;

    /**
     * @param ratePerSecond 每秒补充的令牌数（必须 &gt; 0）
     * @param burst         桶容量 / 最大突发（必须 ≥ 1）
     */
    public RequestTokenBucket(double ratePerSecond, double burst) {
        if (!(ratePerSecond > 0.0) || Double.isNaN(ratePerSecond) || Double.isInfinite(ratePerSecond)) {
            throw new IllegalArgumentException("速率必须为正的有限值：" + ratePerSecond
                    + "（无限制请不要装桶，而不是配 0）");
        }
        if (!(burst >= 1.0) || Double.isNaN(burst) || Double.isInfinite(burst)) {
            throw new IllegalArgumentException("突发额度必须 ≥ 1：" + burst);
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.tokens = burst;            // 初始满桶：刚上线的客户端第一个请求不该被卡
    }

    /** 放行则消费一个令牌并返回 true。 */
    public boolean tryAcquire(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * 还差多少纳秒才有下一个令牌（诊断用；有令牌则 0）。
     * 调用方**不要**拿它 sleep（主线程铁律），服务端的选择是丢弃。
     */
    public long waitNanosFor(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            return 0L;
        }
        final double missing = 1.0 - tokens;
        return (long) Math.ceil(missing / ratePerSecond * 1_000_000_000.0);
    }

    /** 当前可用令牌数（诊断/观测；不含小数误差校正）。 */
    public double available(long nowNanos) {
        refill(nowNanos);
        return tokens;
    }

    public double ratePerSecond() {
        return ratePerSecond;
    }

    public double burst() {
        return burst;
    }

    private void refill(long nowNanos) {
        if (!started) {
            started = true;
            lastNanos = nowNanos;
            return;
        }
        final long dt = nowNanos - lastNanos;
        if (dt <= 0L) {
            lastNanos = nowNanos;               // 时钟回退/停住：不补也不倒扣
            return;
        }
        lastNanos = nowNanos;
        tokens = Math.min(burst, tokens + (dt / 1_000_000_000.0) * ratePerSecond);
    }
}
