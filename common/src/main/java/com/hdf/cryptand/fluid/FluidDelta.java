package com.hdf.cryptand.fluid;

import java.util.Arrays;

/**
 * 引擎的<b>唯一输出</b>：变化集 —— 「这一格的新量是多少」。
 *
 * <p>只记录变了的格（相对传进来的 {@link FluidBodyView}），值是<b>绝对新量</b>而不是增量：
 * 写回侧按世界坐标直接覆盖，因此与写回顺序、与「同一格被谁改过」都无关
 * （旧实现的跨区 {@code addLevelDelta} 增量语义在这里不存在了）。
 *
 * <p>同一格被多次记录时以最后一次为准（按目标聚合）。
 *
 * <p><b>本步是不是算完了</b>：{@link #unfinished()} —— 分片迭代（带预算）时，预算用尽或打满
 * sweep 护栏都意味着「这一步没走遍整片」，这不是不动点。驱动层的收敛判据必须同时看
 * 「{@code step} 返回 0」<b>且</b>「本标记为 false」，否则一轮预算给 0 就会把水永久冻住。
 *
 * <p><b>按目标聚合用开放寻址表（2026-09-29 改）</b>：原来这里是
 * {@code HashMap<Long,Integer>}，而本引擎每次搬运只改两格、一个运动中/写回中的片每步会产生
 * 几百到几千条变化 —— 每条都要 {@code Long} 装箱 + {@code HashMap.Node} 分配（大片写回时
 * 几十 KB/步的垃圾，且 flush 阶段实测占单步两成）。现在坐标直接散列进 {@code long[]}，
 * 零装箱、零 Node，容量只增不减（与 {@link FluidEngine.Scratch} 同一条复用纪律）。
 */
public final class FluidDelta {

    /** 插入序的坐标与值（{@link #size()} 的第 k 条）。 */
    private long[] keys = new long[16];
    private int[] values = new int[16];
    private int count;

    /** 开放寻址表：槽 → 插入序号 + 1（0 = 空槽），槽数恒为 keys 容量的 2 倍（负载 ≤ 0.5）。 */
    private long[] slotKey = new long[32];
    private int[] slotVal = new int[32];
    private int mask = 31;

    /** 本步被预算/护栏打断（没算完）⇒ 返回 0 不代表整片动不了了。 */
    private boolean unfinished;

    /** 记录「这一格最终是多少」。同一格重复记录时覆盖。 */
    public void set(final long packed, final int newAmount) {
        int h = hash(packed) & mask;
        while (true) {
            final int v = slotVal[h];
            if (v == 0) {
                break;
            }
            if (slotKey[h] == packed) {
                values[v - 1] = newAmount;                  // 同一格：只留最后一次（绝对新量）
                return;
            }
            h = (h + 1) & mask;
        }
        if (count == keys.length) {
            grow();
            h = hash(packed) & mask;
            while (slotVal[h] != 0) {
                if (slotKey[h] == packed) {
                    values[slotVal[h] - 1] = newAmount;
                    return;
                }
                h = (h + 1) & mask;
            }
        }
        keys[count] = packed;
        values[count] = newAmount;
        slotKey[h] = packed;
        slotVal[h] = count + 1;
        count++;
    }

    /** keys/values 与槽表一起翻倍（负载始终 ≤ 0.5 ⇒ 线性探测平均 1.5 次以内）。 */
    private void grow() {
        final int grown = count << 1;
        keys = Arrays.copyOf(keys, grown);
        values = Arrays.copyOf(values, grown);
        final int slots = grown << 1;
        slotKey = new long[slots];
        slotVal = new int[slots];
        mask = slots - 1;
        for (int i = 0; i < count; i++) {
            int h = hash(keys[i]) & mask;
            while (slotVal[h] != 0) {
                h = (h + 1) & mask;
            }
            slotKey[h] = keys[i];
            slotVal[h] = i + 1;
        }
    }

    /** 坐标 → 插入序号；没记过返回 -1。 */
    private int slot(final long packed) {
        int h = hash(packed) & mask;
        while (true) {
            final int v = slotVal[h];
            if (v == 0) {
                return -1;
            }
            if (slotKey[h] == packed) {
                return v - 1;
            }
            h = (h + 1) & mask;
        }
    }

    private static int hash(final long key) {
        long z = key * 0x9E3779B97F4A7C15L;
        z ^= z >>> 32;
        return (int) z;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** 第 k 条变化（插入序）的格坐标（{@link FluidBodyView#pack} 打包）。 */
    public long packed(final int k) {
        return keys[k];
    }

    /** 第 k 条变化的最终量。 */
    public int amount(final int k) {
        return values[k];
    }

    public int x(final int k) {
        return FluidBodyView.unpackX(keys[k]);
    }

    public int y(final int k) {
        return FluidBodyView.unpackY(keys[k]);
    }

    public int z(final int k) {
        return FluidBodyView.unpackZ(keys[k]);
    }

    /** 这一格的新量；没变返回 null。 */
    public Integer newAmountAt(final long packed) {
        final int at = slot(packed);
        return at < 0 ? null : values[at];
    }

    /** 这一格的新量；没变返回 {@code fallback}。 */
    public int newAmountAt(final long packed, final int fallback) {
        final int at = slot(packed);
        return at < 0 ? fallback : values[at];
    }

    /**
     * 置「未完成」标记：本步因预算/护栏中断，返回 0 <b>不</b>代表不动点。
     *
     * <p>由 {@link FluidEngine#step(FluidBodyView, FluidDelta, int)} 在预算 ≤ 0 或本轮搬运量
     * 打满预算时调用（引擎内部已不做 sweep 循环，没有「打满护栏」这条路）；不限预算的重载
     * 永远不会置它。
     */
    public void markUnfinished() {
        unfinished = true;
    }

    /** 本步是不是没算完（默认 false）⇒ 驱动层不得据此判收敛。 */
    public boolean unfinished() {
        return unfinished;
    }

    /** 清空变化集与「未完成」标记（{@link FluidEngine#step} 每次入口都会调，复用不串味）。 */
    public void clear() {
        count = 0;
        Arrays.fill(slotVal, 0);                            // 槽表清空 = 逻辑清空（keys/values 不必动）
        unfinished = false;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("FluidDelta(").append(count)
                .append(unfinished ? ",unfinished" : "").append(')');
        for (int k = 0; k < count; k++) {
            sb.append(' ').append(x(k)).append('/').append(y(k)).append('/').append(z(k))
                    .append('=').append(values[k]);
        }
        return sb.toString();
    }
}
