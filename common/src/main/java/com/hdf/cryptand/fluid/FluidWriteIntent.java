package com.hdf.cryptand.fluid;

import com.hdf.cryptand.waterphysics.FluidWritePlan;

/**
 * 写意图换算：{@link FluidDelta}（引擎输出的<b>绝对新量</b>）→ {@link FluidWritePlan}
 * （写回侧的<b>绝对值</b>语义）—— 纯 Java，闸门见 {@code FluidEngineSelfTest#testDeltaWriteIntent}。
 *
 * <p><b>为什么必须是绝对值（2026-09-29 改）</b>：新路径的调度单位是 region，而计算单位是<b>跨 region
 * 的一整片水</b> —— 同一片会被它跨到的每个 region 各采一次、各算一步、各写一次。若写回记「新量 −
 * 装配时的量」的增量、落地再按「侧表当前值 + 增量」，那么第二份 plan 落地时侧表已含第一份的结果
 * ⇒ 同一次搬运被算两遍；而 {@code FluidApplier.applyLevelDelta} 的 {@code max(0, …)} 会把源格扣过头的
 * 那部分静默吞掉 ⇒ <b>净产水</b>（实机 8 单位 → 14 单位、水位 8↔1 来回跳、settled 恒 0）。
 * 绝对写是<b>幂等</b>的：同一片被算 N 遍、写 N 遍，落地的都是同一个新量，重复无害。
 *
 * <p><b>片内格与边界格一视同仁</b>：两者都记「新量 − 装配时的量」。片内格是「本轮算出的新水位相对
 * 采集基线的差值」，边界格是「本轮转移出去 / 收进来的量」，写回侧都按同一个加法落地 ⇒ 与写回顺序、
 * 与同一格被谁改过都无关。
 *
 * <p><b>按目标位置聚合</b>：{@link FluidDelta} 自己对同一格只留最后一次记录（绝对新量），因此每个
 * 变化格在本函数里最多产出<b>一条</b>增量；{@link FluidWritePlan} 只负责按顺序排好，不再合并。
 *
 * <p><b>不漏格</b>：引擎只会输出它读到的格（片内格 + 边界格），本函数逐个回填；数量对不上说明
 * 引擎的输出里出现了片外的坐标（水会凭空消失），直接抛 —— 这是不变量断言，不是容错分支。
 */
public final class FluidWriteIntent {

    /** 「这一格没有被引擎改过」的哨兵：水位合法域是 0..8，负数不可能出现。 */
    private static final int NO_CHANGE = -1;

    private FluidWriteIntent() {
    }

    /**
     * 把一步的变化集换算成写意图。
     *
     * @param delta 引擎本步的输出（绝对新量，只含变了的格）
     * @param body  本步喂给引擎的同一个片（用来取「装配时的量」当增量基线）
     * @param out   写意图出口（只追加水位增量，不碰方块段）
     * @return 写出的增量条数（= 变化集条数，除非某格的增量恰好为 0）
     * @throws IllegalStateException 变化集里出现了既不在片内也不在边界的坐标
     */
    public static int emit(final FluidDelta delta, final FluidBodyView body, final FluidWritePlan out) {
        if (delta == null || body == null || out == null) {
            throw new IllegalArgumentException("delta / body / out 都不能为 null");
        }
        final int changed = delta.size();
        if (changed == 0) {
            return 0;                                       // 真的不动了：一条写意图都不产出
        }
        int emitted = 0;
        for (int i = 0; i < body.size(); i++) {
            emitted += one(delta, body.packed(i), body.amount(i), out);
        }
        for (int j = 0; j < body.borderSize(); j++) {
            emitted += one(delta, body.borderPacked(j), body.borderAmount(j), out);
        }
        if (emitted != changed) {
            throw new IllegalStateException("变化集里有 " + (changed - emitted)
                    + " 格既不在片内也不在边界：引擎只许输出它读到的格（否则这部分水位没有写回路径）");
        }
        return emitted;
    }

    /** 某一格：被改过就写一条增量，返回 1；没变返回 0。 */
    private static int one(final FluidDelta delta, final long packed, final int oldAmount,
                           final FluidWritePlan out) {
        final int now = delta.newAmountAt(packed, NO_CHANGE);
        if (now == NO_CHANGE) {
            return 0;
        }
        // ★ 绝对新量（幂等）：同一片被多个 region 各算一遍也不会双计；
        //   原来的「新量 − 装配量」再按「侧表当前值 + 增量」落地会把同一次搬运算两遍（产水）。
        out.addLevel(packed, now);
        return 1;
    }
}
