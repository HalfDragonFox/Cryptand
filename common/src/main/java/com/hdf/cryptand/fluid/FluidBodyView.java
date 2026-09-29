package com.hdf.cryptand.fluid;

/**
 * 引擎的<b>唯一输入</b>：一整片液体（片内格 + 一圈边界格）。
 *
 * <p><b>引擎不认识 region / chunk / 方块</b>：它只看见「格 → 世界坐标」的邻接表。
 * 坐标是世界坐标，片可以任意跨越区块边界（这是新引擎存在的理由）。
 *
 * <p><b>片内格</b>（{@code 0 .. size()-1}）：这一片液体能到达的格 —— 既有液体格
 * （{@code amount > 0}），也有能容纳液体的空格（{@code amount == 0}，压力场要用它算水面）。
 * 片 = 加载范围内同种液体的整个连通体，<b>不设格数上限</b>。
 *
 * <p><b>边界格</b>（{@code 0 .. borderSize()-1}）：与片内格相邻、但不在片内的格
 * （片外的空气/固体/别的液体）。它用来回答两件事：
 * <ul>
 *   <li>能不能流出去（边界格能容纳本液体）；</li>
 *   <li>流出去多少（{@code borderAmount} 是它当前的水量，用于算空余容量）。</li>
 * </ul>
 * <b>没有边界格 = 未加载</b>：那一侧的水一步都不许出去（守恒的前提）。
 * 边界格的水量变化会记进 {@link FluidDelta}（它下一轮就会被拼进新片）。
 *
 * <p>实现方（转接类 / 离线 {@link ArrayBody}）只需保证「读到的量就是世界里的量」；
 * 引擎不改这个视图，所有搬运都在引擎内部副本上做。
 */
public interface FluidBodyView {

    /**
     * 片内格数。
     *
     * <p><b>契约</b>：片内格坐标<b>互不相同</b>（同一坐标出现两次是畸形输入 ——
     * 引擎按坐标建邻接，重复格在哈希路里会被后者覆盖、在稠密盒路里会漏格）。
     */
    int size();

    /** 第 i 个片内格的世界坐标（{@link #pack} 打包）。 */
    long packed(int i);

    /** 第 i 个片内格的介质种类。 */
    FluidKind kind(int i);

    /** 第 i 个片内格的液体量（0 = 空容器格）。 */
    int amount(int i);

    /** 第 i 个片内格能容纳的液体量（0 = 装不下 ⇒ 不是容器）。 */
    int capacity(int i);

    /**
     * <b>批量取数快路</b>：把 {@code [from, to)} 的片内格一次写进引擎的数组。
     *
     * <p>引擎每步都要把整片读进自己的数组。逐格读是每格 5 次接口调用
     * （{@code packed / kind / amount / capacity / source}），而实现方内部本来就是一排连续数组
     * ⇒ 一次 {@code arraycopy} 就够。默认实现按逐格读走（语义等价），实现方可覆盖。
     *
     * <p><b>语义</b>：{@code dst[dstFrom + k]} 必须等于第 {@code from + k} 格的对应值
     * （{@code pos} 是打包坐标）。取数和消毒是两件事 —— 引擎仍然自己做 null 检查、容量消毒与
     * 水位 clamp，所以这里照原样填、不许提前改数。
     *
     * @param fluid 本片液体（{@link #capacity(int)} 的口径）
     */
    default void copyCells(final int from, final int to, final FluidKind fluid, final long[] pos,
                           final FluidKind[] kind, final int[] amount, final int[] cap,
                           final boolean[] source, final int dstFrom) {
        for (int i = from; i < to; i++) {
            final int d = dstFrom + i - from;
            pos[d] = packed(i);
            kind[d] = this.kind(i);
            amount[d] = this.amount(i);
            cap[d] = capacity(i);
            source[d] = this.source(i);
        }
    }

    /**
     * <b>批量取数快路（边界格）</b>：把 {@code [from, to)} 的边界格一次写进引擎的数组。
     *
     * <p>边界格没有上报容量这条路（引擎按 {@link #borderKind(int)} 自己算），所以这里只取
     * 坐标 / 种类 / 水量三样。默认实现逐格读，语义与 {@link #copyCells} 同一条。
     */
    default void copyBorders(final int from, final int to, final long[] pos, final FluidKind[] kind,
                             final int[] amount, final int dstFrom) {
        for (int j = from; j < to; j++) {
            final int d = dstFrom + j - from;
            pos[d] = borderPacked(j);
            kind[d] = borderKind(j);
            amount[d] = borderAmount(j);
        }
    }

    /**
     * 第 i 个片内格是不是<b>恒定水源</b>（海洋/河流：让出 1 单位后立刻补回满格）。
     *
     * <p>默认 false。这是守恒唯一的例外：水源所在的片总量会增长（凭空产水）。
     *
     * <p><b>水源的三条语义</b>（用户 2026-09-29 定案，实现在 {@link FluidEngine}）：
     * <ol>
     *   <li><b>位置永不被搬运</b>：整体塌落不算它（它上方的水由它撑住）—— 水落走时它留在原处；</li>
     *   <li><b>水位始终保持满格</b>：入口就补满、让出即补（源源不断往外输送）；</li>
     *   <li><b>供水方向 = 下 + 四水平（5 个），不给正上方</b>：源格沿 +Y 的<b>直接</b>供水关掉；
     *       <b>其它格</b>的压力顶升（连通器抬升、水柱上顶、链式推挤向上）不受影响。</li>
     * </ol>
     */
    default boolean source(final int i) {
        return false;
    }

    /** 边界格数；0 = 这一片四周都是未加载。 */
    default int borderSize() {
        return 0;
    }

    /** 第 i 个边界格的世界坐标。 */
    default long borderPacked(final int i) {
        throw new UnsupportedOperationException("borderSize() > 0 时必须实现 borderPacked(i)");
    }

    /** 第 i 个边界格的介质种类（容量按该 kind 对液体算出）。 */
    default FluidKind borderKind(final int i) {
        throw new UnsupportedOperationException("borderSize() > 0 时必须实现 borderKind(i)");
    }

    /** 第 i 个边界格当前的液体量。 */
    default int borderAmount(final int i) {
        throw new UnsupportedOperationException("borderSize() > 0 时必须实现 borderAmount(i)");
    }

    // ---------- 世界坐标打包（引擎内部索引的唯一形式） ----------

    /**
     * 把世界坐标打包成一个 long（与 Minecraft 的 {@code BlockPos.asLong} 同形：
     * x 26 位 | z 26 位 | y 12 位），转接类可以直接透传，不必再换算。
     */
    static long pack(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int unpackX(final long packed) {
        return (int) (packed >> 38);
    }

    static int unpackY(final long packed) {
        return (int) (packed << 52 >> 52);
    }

    static int unpackZ(final long packed) {
        return (int) (packed << 26 >> 38);
    }
}
