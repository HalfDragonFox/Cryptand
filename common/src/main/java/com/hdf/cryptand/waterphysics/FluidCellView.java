package com.hdf.cryptand.waterphysics;

/**
 * 世界只读快照视图（平台无关）。
 *
 * <p>求解器只通过它读世界，绝不直接触碰 Level；neoforge 侧由 McFluidCellView 实现，
 * 离线测试用一个数组实现。坐标是绝对世界坐标。
 */
public interface FluidCellView {

    /** 格子种类（{@link FluidCellKind} 常量）。 */
    int kindAt(int x, int y, int z);

    /** 水位 0..8（不在水位场里的格子返回 0）。 */
    int levelAt(int x, int y, int z);

    default int capacityAt(final int x, final int y, final int z) {
        return FluidCellKind.capacity(kindAt(x, y, z));
    }

    /**
     * 这一格是否**真的读过**（采集范围内、且所在区块已加载）。
     *
     * <p>★ 这是守恒的前提：{@link SpreadSolver} 只允许把水转移到已知格。
     * 若允许转移到没读过的格，那一格既不在进度快照里、也不会被 {@code emitChanges} 输出，
     * 水就会「从源格扣掉但哪都没加上」—— 实机表现正是「源格直接变小，周围却没有水」。
     *
     * <p>默认 true 是为了让离线测试的数组视图与旧实现不受影响。
     */
    default boolean known(final int x, final int y, final int z) {
        return true;
    }
}
