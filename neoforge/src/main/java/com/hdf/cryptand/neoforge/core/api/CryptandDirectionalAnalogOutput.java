/**
 * ===== 方向性模拟输出（2026-09-13） =====
 *
 * 让【原版比较器】能按"读取的那一面"取值：MC 的比较器只调用
 * {@code BlockState#getAnalogOutputSignal(Level, BlockPos)}（**不带方向**），
 * 所以方向性语义必须由 {@code ComparatorBlock} 的 mixin 转接
 * （见 {@code aeronautics/mixin/ComparatorDirectionalOutputMixin}，做法照搬航空学 simulated）。
 *
 * <p>实现者的语义（外设船舵即按此实现）：负值/左舵 → 左侧面输出 0..15 的对应信号；
 * 0/回正 → 两侧都输出 0；正值/右舵 → 右侧面输出。
 */

package com.hdf.cryptand.neoforge.core.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public interface CryptandDirectionalAnalogOutput {

    /**
     * 指定面的模拟信号（0..15）。
     *
     * @param state 方块状态
     * @param level 世界
     * @param pos   方块坐标
     * @param side  读取面（比较器/读取器所在方向）
     * @return 0..15
     */
    int getAnalogSignalFrom(BlockState state, Level level, BlockPos pos, Direction side);
}
