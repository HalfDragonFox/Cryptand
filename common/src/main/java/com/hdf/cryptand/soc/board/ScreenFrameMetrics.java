package com.hdf.cryptand.soc.board;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ===== 屏幕帧性能指标（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户要求"性能优化 + 指标"。优化的前提是**先能量化**：一帧到底花在合成、打包还是发送上，
 * 脏矩形到底省了多少 —— 没有数字就只能凭感觉优化。</p>
 *
 * <p>线程安全（AtomicLong）：服务端合成在 tick 线程、客户端应用在渲染线程，指标可能被两端各自读写；
 * 查询（{@link #summary()}）永远是无锁快照，随时可打（日志/命令/无人化断言）。</p>
 *
 * <p>本类<b>只统计不做决策</b>：动 LOD/限帧/异步化之前先看这里的数字（这是"度量驱动"的那一半）。</p>
 */
public final class ScreenFrameMetrics {

    /** 服务端侧实例（合成 + 下发）。 */
    public static final ScreenFrameMetrics SERVER = new ScreenFrameMetrics("server");

    /** 客户端侧实例（接收 + 应用）。 */
    public static final ScreenFrameMetrics CLIENT = new ScreenFrameMetrics("client");

    private final String side;

    private final AtomicLong composeCalls = new AtomicLong();
    private final AtomicLong composeNanos = new AtomicLong();
    private final AtomicLong changedRows = new AtomicLong();

    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong fullFrames = new AtomicLong();
    private final AtomicLong rectBlocks = new AtomicLong();
    private final AtomicLong rectPixels = new AtomicLong();
    private final AtomicLong frameBytes = new AtomicLong();
    private final AtomicLong encodeNanos = new AtomicLong();
    private final AtomicLong lastLogNanos = new AtomicLong();
    /** 实际**上线**的帧体字节数（含压缩体）—— 与 frameBytes（压缩前参考值）对比即知压缩收益。 */
    private final AtomicLong wireBytes = new AtomicLong();
    private final AtomicLong wireSamples = new AtomicLong();

    private ScreenFrameMetrics(String side) {
        this.side = side;
    }

    /** 一次合成（读设备/字符源 → 图像缓冲），单位纳秒（不需要脏区数时的简写）。 */
    public void recordCompose(long nanos) {
        recordCompose(nanos, 0);
    }

    /** 一次合成（读设备/字符源 → 图像缓冲），单位纳秒。 */
    public void recordCompose(long nanos, int changedRowCount) {
        composeCalls.incrementAndGet();
        composeNanos.addAndGet(Math.max(0L, nanos));
        changedRows.addAndGet(Math.max(0, changedRowCount));
    }

    /**
     * 一帧（服务端发/客户端收）。
     *
     * @param full        是否全量帧
     * @param blockCount  矩形块数（矩形脏块改造后这个数字会随脏区形状变化）
     * @param pixelCount  本帧实际携带的像素数（紧凑打包后 = Σ 行数×列数）
     * @param bytes       本帧字节数（压缩后，若能拿到）
     * @param encodeNanos 打包/编码耗时（0 = 未测）
     */
    public void recordFrame(boolean full, int blockCount, long pixelCount, long bytes, long encodeNanos) {
        frames.incrementAndGet();
        if (full) {
            fullFrames.incrementAndGet();
        }
        rectBlocks.addAndGet(Math.max(0, blockCount));
        rectPixels.addAndGet(Math.max(0L, pixelCount));
        frameBytes.addAndGet(Math.max(0L, bytes));
        this.encodeNanos.addAndGet(Math.max(0L, encodeNanos));
    }

    public long frames() {
        return frames.get();
    }

    public long fullFrames() {
        return fullFrames.get();
    }

    public long deltaFrames() {
        return frames.get() - fullFrames.get();
    }

    public long rectBlocks() {
        return rectBlocks.get();
    }

    public long rectPixels() {
        return rectPixels.get();
    }

    public long frameBytes() {
        return frameBytes.get();
    }

    public long composeCalls() {
        return composeCalls.get();
    }

    public long composeNanos() {
        return composeNanos.get();
    }

    public long encodeNanos() {
        return encodeNanos.get();
    }

    /** 平均每帧块数（保留 2 位）。 */
    public double avgBlocksPerFrame() {
        final long f = frames.get();
        return f == 0L ? 0.0 : round2((double) rectBlocks.get() / f);
    }

    /** 平均每帧像素数（保留 2 位）—— 与"整幅像素数"对比即可看出矩形脏块的收益。 */
    public double avgPixelsPerFrame() {
        final long f = frames.get();
        return f == 0L ? 0.0 : round2((double) rectPixels.get() / f);
    }

    /** 平均每帧字节数（保留 2 位）—— 这是**压缩前**的参考值（像素数 × 4）。 */
    public double avgBytesPerFrame() {
        final long f = frames.get();
        return f == 0L ? 0.0 : round2((double) frameBytes.get() / f);
    }

    /**
     * 记录一次**实际上线**的帧体字节（含压缩体），由帧编码器在写出时调用。
     *
     * <p>为什么要单独一条：`recordFrame` 的 bytes 是"未压缩参考值"（像素×4），
     * 压缩到底省了多少只有这里知道（zlib 与 RLE 的差别能有百倍）。</p>
     */
    public void recordWireBytes(long bodyBytes) {
        wireBytes.addAndGet(Math.max(0L, bodyBytes));
        wireSamples.incrementAndGet();
    }

    public long wireBytes() {
        return wireBytes.get();
    }

    public long wireSamples() {
        return wireSamples.get();
    }

    /** 平均每帧**上线**字节（保留 2 位）；没记录过则 0。 */
    public double avgWireBytesPerFrame() {
        final long s = wireSamples.get();
        return s == 0L ? 0.0 : round2((double) wireBytes.get() / s);
    }

    /** 平均合成耗时（毫秒，保留 3 位）。 */
    public double avgComposeMs() {
        final long c = composeCalls.get();
        return c == 0L ? 0.0 : round3(composeNanos.get() / 1_000_000.0 / c);
    }

    /** 平均帧编码耗时（毫秒，保留 3 位）；未测则 0。 */
    public double avgEncodeMs() {
        final long f = frames.get();
        return f == 0L ? 0.0 : round3(encodeNanos.get() / 1_000_000.0 / f);
    }

    /** 一次可读快照（日志/命令/断言都用它，别各拼各的）。 */
    public String summary() {
        return "[" + side + "] 帧 " + frames.get() + "（全量 " + fullFrames.get() + " / 增量 " + deltaFrames() + "）"
                + " 平均块 " + avgBlocksPerFrame()
                + " 平均像素 " + avgPixelsPerFrame()
                + " 平均字节 " + avgBytesPerFrame() + "（压缩前）"
                // 只在真记录过上线字节时才显示：客户端不写帧（不走编码器），显示 0 会误导成"压缩没生效"
                + (wireSamples.get() > 0L ? " 上线平均 " + avgWireBytesPerFrame() + "B" : "")
                + " 合成 " + composeCalls.get() + " 次/" + avgComposeMs() + "ms"
                + " 编码 " + avgEncodeMs() + "ms";
    }

    /**
     * 到点才返回快照，否则 null —— **日志节流**：指标每帧都在涨，但日志不能每帧都打
     * （一帧一次会淹掉 latest.log，反而看不见真正的异常）。
     *
     * @param intervalMs 距上次打印的最小间隔（毫秒）；传 0 = 本次必打
     */
    public String summaryIfDue(long intervalMs) {
        final long now = System.nanoTime();
        final long last = lastLogNanos.get();
        if (last != 0L && now - last < Math.max(0L, intervalMs) * 1_000_000L) {
            return null;
        }
        if (!lastLogNanos.compareAndSet(last, now)) {
            return null;            // 并发下只让一个调用者负责打印
        }
        return summary();
    }

    /** 强制下次必打（测试/调试用）。 */
    public void resetLogThrottle() {
        lastLogNanos.set(0L);
    }

    /** 清零（测试用；真机不调）。 */
    public void reset() {
        composeCalls.set(0L);
        composeNanos.set(0L);
        changedRows.set(0L);
        frames.set(0L);
        fullFrames.set(0L);
        rectBlocks.set(0L);
        rectPixels.set(0L);
        frameBytes.set(0L);
        encodeNanos.set(0L);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
