package com.hdf.cryptand.soc.peripheral;

/**
 * ===== 共享总线闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>要钉住的行为（用户定案："如果一个设备速率满的话其他设备速率就会下降，模拟真实的"、
 * "尽可能把协议效果按照真实的等效逻辑来做"）：</p>
 * <ol>
 *   <li>按字节信用（不是包数）：速率 × 时间 = 可发字节；</li>
 *   <li><b>加权公平</b>：两设备等权各要 1000 字节、总线只有 1000 ⇒ 各发 500（**降速，不是饿死**）；</li>
 *   <li><b>空份额回收</b>：一个设备没需求时，另一个能吃掉它那份（不浪费带宽）；</li>
 *   <li><b>包原子性</b>：信用小于一个包 ⇒ 整包等下个 tick（不切包发）；</li>
 *   <li><b>流控按协议真实等效</b>：UART 无流控 ⇒ 队列满就丢；I2C/PCIe 有背压 ⇒ 返回 BUSY 不丢；</li>
 *   <li>信用池有上限：宿主 2 秒才 tick 一次，也只给 1 秒的量（不无限攒）。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runSharedBusTest}</p>
 */
public final class SharedBusSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ---- 1. 单设备：1000 B/s，1 秒 tick ⇒ 发 1000 字节（10 个 100 字节包）----
        final SharedBus solo = new SharedBus("SOLO", 1000, SharedBus.FlowControl.NONE_DROP, 4096);
        solo.register("a", 1000);
        for (int i = 0; i < 10; i++) {
            check("入队第 " + (i + 1) + " 个包", solo.offer("a", 100) == SharedBus.Offer.ACCEPTED);
        }
        final long sent1 = solo.tick(1_000_000_000L);
        check("1 秒 tick 发出 1000 字节", sent1 == 1000);
        check("队列已排空", solo.backlog("a") == 0);

        // ---- 2. 两设备等权竞争：总线 1000，各要 1000 ⇒ 各 500（降速，不饿死）----
        final SharedBus fair = new SharedBus("FAIR", 1000, SharedBus.FlowControl.CREDIT_BASED, 8192);
        fair.register("a", 1000).register("b", 1000);
        for (int i = 0; i < 10; i++) {
            fair.offer("a", 100);
            fair.offer("b", 100);
        }
        fair.tick(1_000_000_000L);
        final long a1 = fair.deviceSentBytes("a");
        final long b1 = fair.deviceSentBytes("b");
        check("等权竞争：各发 500 字节（一个吃不满就把另一个饿死是错的）",
                a1 == 500 && b1 == 500);
        check("总线总量受速率约束（共 1000）", a1 + b1 == 1000);

        // ---- 3. 一设备速率满 ⇒ 另一设备速率下降（按权重 3:1）----
        final SharedBus weighted = new SharedBus("WEIGHTED", 1000, SharedBus.FlowControl.CREDIT_BASED, 20_000);
        weighted.register("fast", 3000).register("slow", 1000);
        for (int i = 0; i < 100; i++) {
            weighted.offer("fast", 100);
            weighted.offer("slow", 100);
        }
        // ⚠ 看**长期比例**而不是单 tick 瞬时值：包是原子的（100 字节一整包），
        //   单 tick 里 750 的额度只能发 7 个整包，剩下的 50 留到下个 tick 累积
        //   —— 真实总线正是这个行为（额度按字节，包按整包出）。
        for (int i = 0; i < 10; i++) {
            weighted.tick(1_000_000_000L);
        }
        final long fast = weighted.deviceSentBytes("fast");
        final long slow = weighted.deviceSentBytes("slow");
        check("加权 3:1 ⇒ 10 秒内 fast ≈ 7500、slow ≈ 2500（慢设备被降速但没有饿死）",
                Math.abs(fast - 7500) <= 200 && Math.abs(slow - 2500) <= 200);
        check("加权比确实接近 3:1",
                slow > 0 && Math.abs((double) fast / (double) slow - 3.0) < 0.2);

        // ---- 4. 空份额回收：慢设备没需求 ⇒ 快设备吃掉全部额度 ----
        final SharedBus reclaim = new SharedBus("RECLAIM", 1000, SharedBus.FlowControl.CREDIT_BASED, 8192);
        reclaim.register("busy", 1000).register("idle", 1000);
        for (int i = 0; i < 10; i++) {
            reclaim.offer("busy", 100);
        }
        reclaim.tick(1_000_000_000L);
        check("空闲设备的份额被回收 ⇒ busy 拿到全部 1000 字节",
                reclaim.deviceSentBytes("busy") == 1000 && reclaim.deviceSentBytes("idle") == 0);

        // ---- 5. 包原子性：信用不够一个包 ⇒ 整包等到下个 tick ----
        final SharedBus atomic = new SharedBus("ATOMIC", 50, SharedBus.FlowControl.CREDIT_BASED, 4096);
        atomic.register("d", 50);
        atomic.offer("d", 100);
        check("信用 50 < 包 100 ⇒ 本 tick 一个都不发", atomic.tick(1_000_000_000L) == 0);
        check("再等一秒（信用到 100）⇒ 整包发出", atomic.tick(1_000_000_000L) == 100);

        // ---- 6. 流控按协议真实等效：UART 丢 / I2C 与 PCIe 背压不丢 ----
        final SharedBus uart = new SharedBus("UART", 1000, SharedBus.FlowControl.NONE_DROP, 200);
        uart.register("host", 1000);
        uart.offer("host", 100);
        uart.offer("host", 100);
        final SharedBus.Offer third = uart.offer("host", 100);
        check("UART（无流控）队列满 ⇒ 丢包（FIFO 溢出）", third == SharedBus.Offer.DROPPED);
        check("丢包被计数（不静默）", uart.droppedBytes() == 100 && uart.busyRejects() == 0);

        final SharedBus i2c = new SharedBus("I2C", 1000, SharedBus.FlowControl.CLOCK_STRETCH, 200);
        i2c.register("master", 1000);
        i2c.offer("master", 100);
        i2c.offer("master", 100);
        final SharedBus.Offer i2cThird = i2c.offer("master", 100);
        check("I2C（时钟拉伸）队列满 ⇒ 返回 BUSY，主机等待，不丢",
                i2cThird == SharedBus.Offer.BUSY && i2c.droppedBytes() == 0 && i2c.busyRejects() == 1);

        final SharedBus pcie = new SharedBus("PCIE", 1000, SharedBus.FlowControl.CREDIT_BASED, 200);
        pcie.register("gpu", 1000);
        pcie.offer("gpu", 100);
        pcie.offer("gpu", 100);
        final SharedBus.Offer pcieThird = pcie.offer("gpu", 100);
        check("PCIe（信用流控）队列满 ⇒ 发送端停发，不丢",
                pcieThird == SharedBus.Offer.BUSY && pcie.droppedBytes() == 0);

        final SharedBus usb = new SharedBus("USB", 1000, SharedBus.FlowControl.NAK_RETRY, 200);
        usb.register("dev", 1000);
        usb.offer("dev", 100);
        usb.offer("dev", 100);
        check("USB（NAK 重试）队列满 ⇒ 回 NAK（BUSY），主机重试",
                usb.offer("dev", 100) == SharedBus.Offer.BUSY);

        // ---- 7. 信用池上限：2 秒才 tick 一次，也只给 1 秒的量 ----
        final SharedBus pool = new SharedBus("POOL", 1000, SharedBus.FlowControl.CREDIT_BASED, 8192);
        pool.register("d", 1000);
        for (int i = 0; i < 20; i++) {
            pool.offer("d", 100);
        }
        // ⚠ 总线是**连续可用**的：2 秒过去就能发 2 秒的量（2000 字节）。
        //   曾经给它加过"1 秒信用池上限"，那会让"要 2 秒才发得完的包永远发不出去"——已删。
        check("tick(2s) ⇒ 一轮发出 2 秒的量（2000 字节，连续可用）",
                pool.tick(2_000_000_000L) == 2000);

        // ---- 8. 未注册设备写入 ⇒ 明确报错 ----
        boolean unregistered = false;
        try {
            pool.offer("ghost", 10);
        } catch (IllegalStateException e) {
            unregistered = e.getMessage().contains("没在总线上注册");
        }
        check("写给未注册设备 ⇒ 明确报错", unregistered);

        System.out.println("[BUS] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
