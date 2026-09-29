package com.hdf.cryptand.soc.board;

/**
 * ===== 屏幕帧指标离线闸门（common，纯 Java 零 MC）=====
 *
 * <p>{@code ./gradlew :common:runScreenFrameMetricsTest}</p>
 */
public final class ScreenFrameMetricsSelfTest {

    private static int passed;
    private static int failed;

    private ScreenFrameMetricsSelfTest() {
    }

    public static void main(String[] args) {
        final ScreenFrameMetrics m = ScreenFrameMetrics.SERVER;
        m.reset();
        check("新实例：0 帧", m.frames() == 0L && m.deltaFrames() == 0L);
        check("新实例：平均值不除零", m.avgPixelsPerFrame() == 0.0 && m.avgComposeMs() == 0.0);

        m.recordCompose(2_000_000L, 16);        // 2ms, 16 行
        m.recordCompose(4_000_000L, 8);         // 4ms, 8 行
        check("合成次数", m.composeCalls() == 2L);
        check("平均合成 3ms", m.avgComposeMs() == 3.0);

        m.recordFrame(true, 1, 400L * 256L, 4096L, 1_000_000L);
        check("帧数：1 全量", m.frames() == 1L && m.fullFrames() == 1L && m.deltaFrames() == 0L);
        m.recordFrame(false, 3, 96L, 256L, 500_000L);
        check("帧数：1 全量 + 1 增量", m.frames() == 2L && m.fullFrames() == 1L && m.deltaFrames() == 1L);
        check("平均块数 (1+3)/2 = 2", m.avgBlocksPerFrame() == 2.0);
        check("平均像素 (102400+96)/2 = 51248", m.avgPixelsPerFrame() == 51248.0);
        check("平均字节 (4096+256)/2 = 2176", m.avgBytesPerFrame() == 2176.0);
        check("平均编码 (1.0+0.5)/2 = 0.75ms", m.avgEncodeMs() == 0.75);

        final String s = m.summary();
        check("摘要含帧数与全量/增量", s.contains("帧 2") && s.contains("全量 1") && s.contains("增量 1"));
        check("摘要含合成与编码耗时", s.contains("合成 2 次") && s.contains("编码 0.75ms"));

        m.reset();
        check("重置后归零", m.frames() == 0L && m.composeCalls() == 0L && m.summary().contains("帧 0"));

        // 负数输入不允许污染统计（防御：调用方传错单位/负值时不至于把均值带偏）
        m.recordCompose(-5L, -3);
        m.recordFrame(false, -1, -100L, -7L, -9L);
        check("负值被夹到 0", m.composeNanos() == 0L && m.rectBlocks() == 0L
                && m.rectPixels() == 0L && m.frameBytes() == 0L && m.encodeNanos() == 0L);

        // 上线字节（压缩后）：独立采样，不能和"压缩前参考值"混在一起（否则平均会被两种口径污染）
        m.recordWireBytes(1024L);
        m.recordWireBytes(2048L);
        check("上线字节累计 = 3072", m.wireBytes() == 3072L);
        check("上线采样 = 2", m.wireSamples() == 2L);
        check("平均上线字节 = 1536", m.avgWireBytesPerFrame() == 1536.0);
        check("摘要含上线平均", m.summary().contains("上线平均"));
        m.recordWireBytes(-5L);                     // 负值夹 0（但不吞掉这次采样计数）
        check("上线字节负值被夹 0，采样仍 +1", m.wireBytes() == 3072L && m.wireSamples() == 3L);

        System.out.println("[metrics] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
