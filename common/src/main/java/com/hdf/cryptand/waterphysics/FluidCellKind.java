package com.hdf.cryptand.waterphysics;

/**
 * 格子种类（本子包对世界的全部认知，纯 int 常量，不含任何 MC 类型）。
 *
 * <p>由方块类型归纳出的判定结果（去掉方块实例，只留种类常量）。
 */
public final class FluidCellKind {

    /** 空气：可被水填充。 */
    public static final int AIR = 0;
    /** 固体：不可容纳水。 */
    public static final int SOLID = 1;
    /** 可含水方块（原版 waterlogged 语义）：水与方块共存。 */
    public static final int WATERLOGGABLE = 2;
    /** 流体方块本身（FlowingFluid 对应的方块）。 */
    public static final int FLUID = 3;
    /** 可穿过且允许水通过（栅栏/树叶等，由 FluidRules 指定）。 */
    public static final int PASSABLE = 4;

    /** 水位上限（水位范围 0..8）。 */
    public static final int MAX_LEVEL = 8;

    private FluidCellKind() {
    }

    /**
     * 该种类能否容纳水。
     *
     * <p><b>PASSABLE 不能容纳</b>（栅栏 / 树叶 / 玻璃板这类「穿得过但装不下水位」的格）：
     * 水位只能投影成「水方块」或「waterlogged 布尔」，这两种都放不进这类格子 ——
     * 一旦让水流进去，世界方块状态不会变，下一批采集就会按
     * {@code FluidLevels.resolve(世界水位 = 0, 侧表)} 判成「外部清除」⇒ 水凭空消失（守恒破掉）。
     * 宁可让这类格子像墙一样挡水。
     */
    public static boolean canHold(final int kind) {
        return kind == AIR || kind == FLUID || kind == WATERLOGGABLE;
    }

    /**
     * 该种类是否<b>只接受整格水位</b>（原版含水方块：只有「有水 / 没水」，没有中间档位）。
     *
     * <p>原版的可含水方块把水位表达成一个布尔（{@code WATERLOGGED}），往里写 1..7 级水位
     * 在世界里看不见、别的 mod 读 {@code getAmount()} 也永远是 8，侧表与世界会长期不一致。
     * 所以往这类格子转移时要求「能填满才放」，填不满就让水留在原格（守恒、且与世界一致）。
     */
    public static boolean fullCubeOnly(final int kind) {
        return kind == WATERLOGGABLE;
    }

    /** 该种类的容量（水位上限）。 */
    public static int capacity(final int kind) {
        return canHold(kind) ? MAX_LEVEL : 0;
    }

    public static String name(final int kind) {
        return switch (kind) {
            case AIR -> "AIR";
            case SOLID -> "SOLID";
            case WATERLOGGABLE -> "WATERLOGGABLE";
            case FLUID -> "FLUID";
            case PASSABLE -> "PASSABLE";
            default -> "UNKNOWN(" + kind + ")";
        };
    }
}
