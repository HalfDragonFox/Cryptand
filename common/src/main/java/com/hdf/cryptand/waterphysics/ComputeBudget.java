package com.hdf.cryptand.waterphysics;

/**
 * 每 tick「发放」的运算额度 —— 对齐本仓库其它子包的心跳预算模式
 * （见 cryptandsable 的 {@code SableMessages.Heartbeat(serverTick, advanceSteps, budgetNanos)}：
 * 主线程每 tick 发一次心跳，心跳里带着这一 tick 允许推进多少）。
 *
 * <p><b>语义是「重置」而不是「累加」</b>：心跳来时无论上一 tick 还剩多少，都直接设成配置值。
 * 这样积压永远不会雪崩式爆发 —— 每 tick 最多消耗 {@code perTick} 次运算，超出的部分这一 tick 就不做了。
 *
 * <p>纯 Java，离线闸门见 {@code WaterphysicsSelfTest#testComputeBudget}。
 */
public final class ComputeBudget {

    private int remaining;

    /** 心跳：每 tick 调用一次，把额度重置为 {@code amount}（与上 tick 剩多少无关）。 */
    public void grant(final int amount) {
        remaining = Math.max(0, amount);
    }

    public int remaining() {
        return remaining;
    }

    public boolean exhausted() {
        return remaining <= 0;
    }

    /**
     * 按 region 数把剩余额度均分成「每份」—— 不扣减，只算每份多大。
     *
     * <p>★ 均分后至少 1：否则排在后面的 region 拿到 {@code quota == 0}，
     * {@link SpreadSolver#step} 会立刻返回 0，而调用方把「0 次转移」当成
     * 「这块水已经稳定」—— 那块水就再也不动了（实机「水有时流有时不流」）。
     *
     * @param regions 本轮待分摊的 region 数（&lt;= 0 按 1 处理）
     * @return 每份的转移次数；额度已空返回 0
     */
    public int share(final int regions) {
        if (remaining <= 0) {
            return 0;
        }
        return Math.max(1, remaining / Math.max(1, regions));
    }

    /**
     * 取用额度。
     *
     * @return 实际取到的数量（可能少于 want）
     */
    public int take(final int want) {
        final int taken = Math.min(Math.max(0, want), remaining);
        remaining -= taken;
        return taken;
    }
}
