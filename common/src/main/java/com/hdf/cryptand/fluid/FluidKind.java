package com.hdf.cryptand.fluid;

/**
 * 液体种类（引擎对「世界是什么」的全部认知，纯 Java 零 MC）。
 *
 * <p>一个 {@code FluidKind} 同时描述两件事：<b>这一格本身是不是液体</b>（水/岩浆），
 * 以及<b>这一格能不能容纳液体</b>（空气 / 可含水方块 / 另一种液体格）。
 * 引擎按 kind 分片：片内所有液体格同 kind，不同液体（水 vs 岩浆）不混一片。
 *
 * <p><b>满格量</b>只对液体本身有意义（水 = 8）。容器格的「能装多少」由
 * {@link #capacityFor(FluidKind)} 给出 —— 它等于 <i>所装液体的</i> 满格量，
 * 所以同一个空气格对水是 8、对另一种满格量不同的液体就是另一个数。
 *
 * <p>三种非液体介质（阶段 1 的口径，来自旧引擎 {@code FluidCellKind}）：
 * <ul>
 *   <li>{@link #AIR}：任何液体都能占据；</li>
 *   <li>{@link #SOLID}：谁都进不去（挡水）；</li>
 *   <li>{@link #WATERLOGGABLE}：能装，但只接受<b>整格</b>（原版 waterlogged 只有布尔，
 *       写 1..7 级水位在世界里看不见 ⇒ 填不满就一点都不放）。</li>
 * </ul>
 */
public final class FluidKind {

    /** id → 实例的登记表（先声明：常量初始化要用它）。 */
    private static final FluidKind[] BY_ID = new FluidKind[64];

    /** 空气：任何液体都能占据。 */
    public static final FluidKind AIR = medium(0, "AIR", true, false);
    /** 固体：任何液体都进不去。 */
    public static final FluidKind SOLID = medium(1, "SOLID", false, false);
    /** 可含水方块：能装液体，但只接受整格。 */
    public static final FluidKind WATERLOGGABLE = medium(2, "WATERLOGGABLE", true, true);
    /** 水：满格 8。 */
    public static final FluidKind WATER = liquid(3, "WATER", 8);
    /** 岩浆：满格 8；与水不同 kind ⇒ 不与水混成一片。 */
    public static final FluidKind LAVA = liquid(4, "LAVA", 8);

    private final int id;
    private final String name;
    /** 满格量：液体本身一格能装多少单位；非液体恒 0。 */
    private final int fill;
    /** 这一格本身是不是液体（占据本格的介质就是液体）。 */
    private final boolean liquid;
    /** 这一格能不能容纳液体（空气 / 可含水方块 / 液体格自己）。 */
    private final boolean holds;
    /** 只接受整格：填不满就一点都不放。 */
    private final boolean fullCubeOnly;

    private FluidKind(final int id, final String name, final int fill, final boolean liquid,
                      final boolean holds, final boolean fullCubeOnly) {
        if (id < 0 || id >= BY_ID.length) {
            throw new IllegalArgumentException("kind id 越界: " + id);
        }
        if (BY_ID[id] != null) {
            throw new IllegalStateException("kind id 重复登记: " + id);
        }
        this.id = id;
        this.name = name;
        this.fill = fill;
        this.liquid = liquid;
        this.holds = holds;
        this.fullCubeOnly = fullCubeOnly;
        BY_ID[id] = this;
    }

    /** 登记一种液体（水/岩浆/…）：满格量 &gt; 0。 */
    public static FluidKind liquid(final int id, final String name, final int fill) {
        if (fill <= 0) {
            throw new IllegalArgumentException("液体的满格量必须 > 0: " + fill);
        }
        return new FluidKind(id, name, fill, true, true, false);
    }

    /** 登记一种非液体介质（空气/固体/可含水方块/…）。 */
    public static FluidKind medium(final int id, final String name, final boolean holds,
                                   final boolean fullCubeOnly) {
        return new FluidKind(id, name, 0, false, holds, fullCubeOnly);
    }

    /** 按 id 取已登记的 kind；未登记返回 null。 */
    public static FluidKind byId(final int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }

    public int id() {
        return id;
    }

    public String kindName() {
        return name;
    }

    /** 满格量（只有液体本身 &gt; 0）。 */
    public int fill() {
        return fill;
    }

    /** 这一格本身是不是液体。 */
    public boolean isLiquid() {
        return liquid;
    }

    /** 这一格能不能容纳液体。 */
    public boolean holdsLiquid() {
        return holds;
    }

    /** 只接受整格。 */
    public boolean fullCubeOnly() {
        return fullCubeOnly;
    }

    /**
     * 这一格能不能接住 {@code fluid}：
     * <ul>
     *   <li>同种液体格 ⇒ 能（水接水）；</li>
     *   <li>非液体且声明能容纳（空气 / 可含水方块）⇒ 能；</li>
     *   <li>另一种液体格（岩浆）⇒ 不能（不同液体不混一片）。</li>
     * </ul>
     */
    public boolean accepts(final FluidKind fluid) {
        return this == fluid || (holds && !liquid);
    }

    /** 这一格装 {@code fluid} 的容量：接不住就是 0。 */
    public int capacityFor(final FluidKind fluid) {
        return accepts(fluid) ? fluid.fill : 0;
    }

    @Override
    public String toString() {
        return name + "#" + id;
    }
}
