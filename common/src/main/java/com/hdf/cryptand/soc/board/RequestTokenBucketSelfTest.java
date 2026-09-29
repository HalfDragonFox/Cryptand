package com.hdf.cryptand.soc.board;

/**
 * ===== 请求令牌桶闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用**假时钟**推进（不 sleep），把限流语义钉死：速率上限、突发上限、
 * 空闲回补不超过 burst、时钟回退不补、非法配置明确抛错。</p>
 *
 * <p>{@code ./gradlew :common:runRequestTokenBucketTest}</p>
 */
public final class RequestTokenBucketSelfTest {

    private static final long MS = 1_000_000L;

    private static int passed;
    private static int failed;

    private RequestTokenBucketSelfTest() {
    }

    public static void main(String[] args) {
        // 速率 50/s、burst 4：初始满桶 ⇒ 前 4 个放行，第 5 个拒绝
        final RequestTokenBucket bucket = new RequestTokenBucket(50.0, 4.0);
        long t = 1_000 * MS;
        int allowed = 0;
        for (int i = 0; i < 4; i++) {
            if (bucket.tryAcquire(t)) {
                allowed++;
            }
        }
        check("初始满桶：burst=4 ⇒ 连续 4 个放行", allowed == 4);
        check("第 5 个立即拒绝（速率 50/s 还没到 1 个令牌的时间）", !bucket.tryAcquire(t));

        // 50/s ⇒ 每 20ms 补 1 个
        check("无令牌时 waitNanosFor ≈ 20ms（实得 " + bucket.waitNanosFor(t) / MS + "ms）",
                Math.abs(bucket.waitNanosFor(t) - 20 * MS) <= 1 * MS);
        t += 20 * MS;
        check("过 20ms ⇒ 补 1 个，放行", bucket.tryAcquire(t));
        check("同一时刻再要 ⇒ 拒绝", !bucket.tryAcquire(t));

        // 长时间空闲回补但不超过 burst
        t += 10_000 * MS;                       // 挂机 10 秒
        check("空闲 10s ⇒ 回补到 burst（不超过 4）", Math.abs(bucket.available(t) - 4.0) < 1e-6);
        int burstNow = 0;
        for (int i = 0; i < 10; i++) {
            if (bucket.tryAcquire(t)) {
                burstNow++;
            }
        }
        check("回补后最多也只能突发 4 个", burstNow == 4);

        // 时钟回退：不补不倒扣
        final RequestTokenBucket back = new RequestTokenBucket(100.0, 2.0);
        long tb = 5_000 * MS;
        check("回退前先消耗 1 个", back.tryAcquire(tb));
        tb -= 1_000 * MS;                       // 时钟跳回 1 秒前
        check("时钟回退 ⇒ 不补令牌", !back.tryAcquire(tb) || back.available(tb) <= 1.0);
        check("时钟回退不抛异常且可用数不为负", back.available(tb) >= 0.0);

        // 速率边界：1/s burst=1 ⇒ 严格 1 秒 1 个
        final RequestTokenBucket strict = new RequestTokenBucket(1.0, 1.0);
        long ts = 0L;
        ts += MS;                               // 让哨兵初始化
        check("严格限流：第 1 个放行", strict.tryAcquire(ts));
        check("严格限流：第 2 个拒绝", !strict.tryAcquire(ts));
        ts += 999 * MS;
        check("严格限流：差 1ms 仍拒绝", !strict.tryAcquire(ts));
        ts += 1 * MS;
        check("严格限流：满 1s 放行", strict.tryAcquire(ts));

        // 非法配置：明确抛错（"无限制"不该用 0 表达）
        check("速率 0 ⇒ 构造抛错", throwsOn(() -> new RequestTokenBucket(0.0, 4.0)));
        check("速率负 ⇒ 构造抛错", throwsOn(() -> new RequestTokenBucket(-1.0, 4.0)));
        check("速率 NaN ⇒ 构造抛错", throwsOn(() -> new RequestTokenBucket(Double.NaN, 4.0)));
        check("burst<1 ⇒ 构造抛错", throwsOn(() -> new RequestTokenBucket(10.0, 0.5)));

        System.out.println("[token-bucket] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
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
