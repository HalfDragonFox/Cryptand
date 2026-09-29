package com.hdf.cryptand.fluid;

import com.hdf.cryptand.core.frame.SectionCursor;

/**
 * 单 region 采集数据 → 引擎片（{@link FluidBodyView}）的纯 Java 装配。
 *
 * <p><b>为什么在 common</b>：这一段的全部内容都是纯数据规则（坐标换算、种类映射、容量、
 * 边界取舍），拿掉 Minecraft 照样成立 ⇒ 下沉到这里，neoforge 侧只留「从 RegionSnapshot 取数」
 * 的翻译层（{@code FluidBodyAdapter}）。闸门见 {@code FluidEngineSelfTest#testRegionAssembly}。
 *
 * <p><b>片内格</b>（交给 {@link ArrayBody#cell}）的取舍规则：
 * <ul>
 *   <li>没采过的格一律不进片（引擎看不见 = 一步都不许往那儿转移，这就是旧
 *       {@code FluidCellView.known()} 的同一条语义）；</li>
 *   <li>采过的格里只放「<b>有水</b>（水位 &gt; 0）或<b>装得下本液体</b>」的格 ——
 *       固体与可穿过（栅栏 / 树叶，装不下水位）不进片；</li>
 *   <li>坐标用<b>世界坐标</b>（引擎只认世界坐标，片不设格数上限）。</li>
 * </ul>
 *
 * <p><b>边界格</b>（交给 {@link ArrayBody#border}）= region 外一圈（6 个面 × 16×16），规则：
 * <ul>
 *   <li>没采过的面格<b>不可用</b>：根本不放进片 —— 引擎看不到它，那一侧一滴水都出不去
 *       （守恒的前提，与 {@link FluidBodyView} 的「没有边界格 = 未加载」一致）；</li>
 *   <li>装不下本液体的面格（固体）也不放：引擎对「不在片里的格」与「在片里但装不下的格」
 *       在每一条使用路径上判定完全相同（都当「撑得住 / 走不过去」），不放等于不放，
 *       但少一份邻接表开销。两个方向的等价性在离线闸门里逐条钉住；</li>
 *   <li>面格的水量照抄采集值（{@code borderAmount} 用来算还能装多少）。</li>
 * </ul>
 *
 * <p><b>本片液体</b>：按线性索引升序找第一格「有量的液体格」，它的 kind 就是这一片的液体
 * （引擎按 kind 分片，不同液体不混一片）。找不到 ⇒ 抛 {@link IllegalStateException}：
 * 调用方的契约是「只在有水的 region 上调装配」（旧路径同样只在 {@code active} 非空时才派发求解）。
 */
public final class FluidRegionAssembly {

    /** 一个 region 的边长（= 一个 chunk section）。 */
    public static final int SIZE = SectionCursor.SECTION_SIZE;
    /** 一个 region 的格数（16³ = 4096）。 */
    public static final int CELLS = SectionCursor.SECTION_CELLS;

    /** region 外的 6 个面（-X / +X / -Y / +Y / -Z / +Z）。 */
    private static final int[][] FACE = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * 采集数据的只读视图（由 neoforge 的 {@code FluidBodyAdapter} 实现；离线闸门用数组实现）。
     *
     * <p>片内一律用 section 内<b>线性索引</b>（{@link SectionCursor#linearIndex}），
     * 面格一律用<b>世界坐标</b>（面格不在本 region 里，没有线性索引）。
     * region 基坐标由 {@link #baseX()} 等给出 —— 世界坐标 = 基坐标 + 局部坐标。
     */
    public interface Source {

        /** region 基坐标（世界坐标 = 基坐标 + 局部坐标）。 */
        int baseX();

        int baseY();

        int baseZ();

        /** 这一格（section 内线性索引）是不是真的采过世界（= 旧 {@code FluidCellView.known}）。 */
        boolean insideScanned(int index);

        /** 这一格的介质种类（{@link com.hdf.cryptand.waterphysics.FluidCellKind} 常量）。 */
        int insideKind(int index);

        /** 这一格的液体量（已合并侧表的权威水位，0..8）。 */
        int insideLevel(int index);

        /** 这一格是不是恒定水源。 */
        boolean insideSource(int index);

        /** region 外面某一格（世界坐标）采过没有。 */
        boolean outsideScanned(int x, int y, int z);

        /** region 外面某一格的介质种类。 */
        int outsideKind(int x, int y, int z);

        /** region 外面某一格的液体量。 */
        int outsideLevel(int x, int y, int z);
    }

    private FluidRegionAssembly() {
    }

    /**
     * 把一次单 region 采集装配成引擎片。
     *
     * @throws IllegalStateException 片内没有任何「有量的液体格」（调用方契约：只在有水的 region 上装配）
     * @throws IllegalArgumentException 遇到未知的格子种类（见 {@link FluidKindMap#of(int)}）
     */
    public static ArrayBody assemble(final Source src) {
        if (src == null) {
            throw new IllegalArgumentException("src 不能为 null");
        }
        final int baseX = src.baseX();
        final int baseY = src.baseY();
        final int baseZ = src.baseZ();
        final FluidKind fluid = pickFluid(src);
        if (fluid == null) {
            throw new IllegalStateException("这一片里没有「有量的液体格」：装配只在有水的 region 上调用"
                    + "（base=" + baseX + "/" + baseY + "/" + baseZ + "）");
        }
        final ArrayBody body = new ArrayBody(fluid);
        for (int i = 0; i < CELLS; i++) {
            if (!src.insideScanned(i)) {
                continue;                                   // 没采过：不进片
            }
            final FluidKind kind = FluidKindMap.of(src.insideKind(i));
            final int level = src.insideLevel(i);
            if (level <= 0 && !kind.accepts(fluid)) {
                continue;                                   // 装不下又没水：固体 / 可穿过，不进片
            }
            body.cell(baseX + SectionCursor.localX(i), baseY + SectionCursor.localY(i),
                    baseZ + SectionCursor.localZ(i), kind, level, src.insideSource(i));
        }
        for (final int[] face : FACE) {
            for (int a = 0; a < SIZE; a++) {
                for (int b = 0; b < SIZE; b++) {
                    final int x;
                    final int y;
                    final int z;
                    if (face[0] != 0) {
                        x = baseX + (face[0] < 0 ? -1 : SIZE);
                        y = baseY + a;
                        z = baseZ + b;
                    } else if (face[1] != 0) {
                        x = baseX + a;
                        y = baseY + (face[1] < 0 ? -1 : SIZE);
                        z = baseZ + b;
                    } else {
                        x = baseX + a;
                        y = baseY + b;
                        z = baseZ + (face[2] < 0 ? -1 : SIZE);
                    }
                    if (!src.outsideScanned(x, y, z)) {
                        continue;                           // 未采集 ⇒ 不可用（水一滴都不许出去）
                    }
                    final FluidKind kind = FluidKindMap.of(src.outsideKind(x, y, z));
                    if (!kind.accepts(fluid)) {
                        continue;                           // 装不下 ⇒ 对引擎等价于「这一格不存在」
                    }
                    body.border(x, y, z, kind, src.outsideLevel(x, y, z));
                }
            }
        }
        return body;
    }

    /** 本片液体 = 第一格（线性索引升序）「有量的液体格」的 kind；没有返回 null。 */
    private static FluidKind pickFluid(final Source src) {
        for (int i = 0; i < CELLS; i++) {
            if (!src.insideScanned(i)) {
                continue;
            }
            final FluidKind kind = FluidKindMap.of(src.insideKind(i));
            if (kind.isLiquid() && src.insideLevel(i) > 0) {
                return kind;
            }
        }
        return null;
    }
}
