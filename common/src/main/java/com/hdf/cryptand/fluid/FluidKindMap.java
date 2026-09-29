package com.hdf.cryptand.fluid;

import com.hdf.cryptand.waterphysics.FluidCellKind;

/**
 * 格子种类映射：旧口径（waterphysics 采集层）的 {@link FluidCellKind} 常量 → 引擎的 {@link FluidKind}。
 *
 * <p>这是「旧世界口径 → 新引擎口径」的唯一一张表（转接类不许自己再判一遍，否则两处迟早漂移）：
 * <table border="1">
 *   <caption>映射表</caption>
 *   <tr><th>FluidCellKind</th><th>FluidKind</th><th>依据</th></tr>
 *   <tr><td>{@link FluidCellKind#AIR}</td><td>{@link FluidKind#AIR}</td>
 *       <td>可被水占据，对水容量 = 满格量</td></tr>
 *   <tr><td>{@link FluidCellKind#SOLID}</td><td>{@link FluidKind#SOLID}</td>
 *       <td>谁都进不去</td></tr>
 *   <tr><td>{@link FluidCellKind#WATERLOGGABLE}</td><td>{@link FluidKind#WATERLOGGABLE}</td>
 *       <td>只接受整格（原版 waterlogged 是布尔，写 1..7 级水位世界里看不见）</td></tr>
 *   <tr><td>{@link FluidCellKind#FLUID}</td><td>{@link FluidKind#WATER}</td>
 *       <td>采集层的 {@code kindOf} <b>只把水判成 FLUID</b>：岩浆（以及一切非水标签的流体）
 *           判成 SOLID —— 岩浆已不再算水，所以这张表里的 FLUID 一定是水</td></tr>
 *   <tr><td>{@link FluidCellKind#PASSABLE}</td><td>{@link FluidKind#SOLID}</td>
 *       <td>旧口径 {@code FluidCellKind.canHold(PASSABLE) == false}（栅栏 / 树叶 / 玻璃板「穿得过
 *           但装不下水位」）⇒ 按挡水处理，与旧求解器一致</td></tr>
 * </table>
 *
 * <p>容量不由本表给出：容量是「本格对某种液体」的属性，由 {@link FluidKind#capacityFor(FluidKind)}
 * 算出（空气对水 = 8，固体 = 0，含水方块 = 8），与 {@code FluidCellKind.capacity} 同口径。
 *
 * <p>未知种类一律抛 {@link IllegalArgumentException}：绝不许静默当成空气（那会让水「流进」一个
 * 世界根本装不下的格子，水就被凭空吞掉）。
 *
 * <p>纯 Java，离线闸门见 {@code FluidEngineSelfTest#testRegionAssembly}。
 */
public final class FluidKindMap {

    private FluidKindMap() {
    }

    /**
     * 旧口径的格子种类 → 引擎的格子种类。
     *
     * @param cellKind {@link FluidCellKind} 里的 int 常量
     * @throws IllegalArgumentException 未知种类
     */
    public static FluidKind of(final int cellKind) {
        switch (cellKind) {
            case FluidCellKind.AIR:
                return FluidKind.AIR;
            case FluidCellKind.SOLID:
                return FluidKind.SOLID;
            case FluidCellKind.WATERLOGGABLE:
                return FluidKind.WATERLOGGABLE;
            case FluidCellKind.FLUID:
                return FluidKind.WATER;
            case FluidCellKind.PASSABLE:
                return FluidKind.SOLID;
            default:
                throw new IllegalArgumentException("未知的 FluidCellKind: " + cellKind);
        }
    }
}
