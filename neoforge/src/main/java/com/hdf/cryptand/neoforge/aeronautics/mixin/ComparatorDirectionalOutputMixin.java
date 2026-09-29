/**
 * ===== 原版比较器 · 方向性模拟输出转接（2026-09-13） =====
 *
 * 用户："比较器输出按照原船舵，如果为负数则左边输出对应信号，如果 0 则两边都不输出，
 * 如果正数为右边输出"。
 *
 * <p>MC 的比较器只有 {@code BlockState#getAnalogOutputSignal(Level, BlockPos)}（无方向），
 * 因此按面取值必须 wrap 掉这个调用、改走
 * {@link com.hdf.cryptand.neoforge.core.api.CryptandDirectionalAnalogOutput}（带方向）。
 * 做法照搬航空学 simulated 的 {@code ComparatorBlockMixin}（同样是 mixin 原版比较器，
 * 不侵入任何 mod 的类）。
 *
 * <p>门控见 {@code AeronauticsMixinPlugin#shouldApplyMixin}：只要求装了 aeronautics
 * （与"轮子摩擦应力"开关无关）。
 */

package com.hdf.cryptand.neoforge.aeronautics.mixin;

import com.hdf.cryptand.neoforge.core.api.CryptandDirectionalAnalogOutput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ComparatorBlock.class)
public class ComparatorDirectionalOutputMixin {

    @WrapOperation(method = "getInputSignal",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getAnalogOutputSignal(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I"))
    private int cryptand$directionalAnalogSignal(final BlockState instance, final Level level,
                                                 final BlockPos pos, final Operation<Integer> original,
                                                 @Local(name = "direction") final Direction direction) {
        if (instance.getBlock() instanceof CryptandDirectionalAnalogOutput directional) {
            return directional.getAnalogSignalFrom(instance, level, pos, direction);
        }
        return original.call(instance, level, pos);
    }
}
